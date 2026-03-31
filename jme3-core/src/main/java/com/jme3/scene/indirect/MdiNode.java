/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.scene.indirect;

import com.jme3.material.MatParam;
import com.jme3.material.Material;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.indirect.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A scene graph node that automatically batches child geometries into
 * multi-draw indirect (MDI) draw calls.
 * <p>
 * Works like {@link com.jme3.scene.instancing.InstancedNode} but uses MDI
 * instead of hardware instancing. This allows batching geometries with
 * <b>different meshes</b> into a single draw call, as long as they share
 * the same material definition with a {@code DrawData} block.
 * <p>
 * Usage:
 * <pre>
 * MdiNode mdiNode = new MdiNode("terrain");
 * rootNode.attachChild(mdiNode);
 *
 * Geometry tree = new Geometry("tree", treeMesh);
 * tree.setMaterial(mdiMaterial);  // must have DrawData in its matdef
 * mdiNode.attachChild(tree);
 *
 * Geometry rock = new Geometry("rock", rockMesh);
 * rock.setMaterial(mdiMaterial);
 * mdiNode.attachChild(rock);
 *
 * mdiNode.batch();  // builds MDI batches
 * </pre>
 * <p>
 * Child geometry transforms are updated automatically each frame via
 * the internal {@link MdiNodeControl}.
 */
public class MdiNode extends GeometryGroupNode {

    private static final Logger logger = Logger.getLogger(MdiNode.class.getName());

    /** Name of the SSBO parameter on the material for per-draw data. */
    private static final String DRAW_DATA_PARAM = "DrawData";

    private MdiNodeControl control;

    /** Maps each child geometry to its batch. */
    private final Map<Geometry, MdiBatch> batchByGeom = new HashMap<>();

    /** Maps DrawDataLayout to batch. Geometries with same layout share a batch. */
    private final Map<DrawDataLayout, MdiBatch> batchByLayout = new HashMap<>();

    /** Set of batches needing transform re-upload. */
    private final Set<MdiBatch> dirtyBatches = new HashSet<>();

    /** Whether batch() has been called. */
    private boolean batched = false;

    protected MdiNode() {
        super();
    }

    public MdiNode(String name) {
        super(name);
        control = new MdiNodeControl(this);
        addControl(control);
    }

    /**
     * Groups all child geometries into MDI batches by material DrawData layout.
     * Must be called after attaching children and before rendering.
     */
    public void batch() {
        // Clear existing batches
        for (MdiBatch batch : batchByLayout.values()) {
            if (batch.mdiGeometry != null) {
                detachChild(batch.mdiGeometry);
            }
        }
        batchByGeom.clear();
        batchByLayout.clear();
        dirtyBatches.clear();

        // Collect geometries recursively
        collectGeometries(this);

        // Build each batch
        for (MdiBatch batch : batchByLayout.values()) {
            buildBatch(batch);
        }

        batched = true;
    }

    private void collectGeometries(Spatial spatial) {
        if (spatial instanceof Geometry) {
            Geometry geom = (Geometry) spatial;
            if (geom instanceof MdiGeometry) return;
            if (geom.isGrouped()) return;
            if (geom.getBatchHint() == Spatial.BatchHint.Never) return;

            addToBatch(geom);
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                if (child instanceof GeometryGroupNode) continue;
                collectGeometries(child);
            }
        }
    }

    private void addToBatch(Geometry geom) {
        Material material = geom.getMaterial();
        if (material == null) {
            logger.log(Level.WARNING, "Geometry {0} has no material, skipping MDI batching", geom.getName());
            return;
        }

        DrawDataLayout layout = material.getMaterialDef().getDrawDataLayout();
        if (layout == null) {
            logger.log(Level.WARNING, "Material on {0} has no DrawData block, skipping MDI batching", geom.getName());
            return;
        }

        MdiBatch batch = batchByLayout.get(layout);
        if (batch == null) {
            batch = new MdiBatch(layout, material);
            batchByLayout.put(layout, batch);
        }

        batch.geometries.add(geom);
        batchByGeom.put(geom, batch);
        geom.associateWithGroupNode(this, batch.geometries.size() - 1);
    }

    private void buildBatch(MdiBatch batch) {
        DrawDataLayout layout = batch.layout;
        List<Geometry> geoms = batch.geometries;

        // Combine meshes
        Mesh[] meshes = new Mesh[geoms.size()];
        for (int i = 0; i < geoms.size(); i++) {
            meshes[i] = geoms.get(i).getMesh();
        }
        batch.combinedMesh = MeshCombiner.combine(meshes);

        // Build command buffer
        batch.commandBuffer = batch.combinedMesh.toCommandBuffer();
        batch.commandBuffer.update();

        // Allocate DrawData SSBO
        int totalBytes = geoms.size() * layout.getStride();
        batch.drawDataCpu = BufferUtils.createByteBuffer(totalBytes);
        batch.drawDataSsbo = new BufferObject();
        batch.drawDataSsbo.setAccessHint(BufferObject.AccessHint.Dynamic);
        batch.drawDataSsbo.setNatureHint(BufferObject.NatureHint.Draw);

        // Serialize initial material params
        serializeBatch(batch);

        // Create MdiGeometry and attach to scene
        batch.mdiGeometry = new MdiGeometry(
                "MdiBatch-" + System.identityHashCode(batch),
                batch.combinedMesh.getMesh(),
                batch.commandBuffer);

        // Clone the material and set the DrawData SSBO on it
        Material batchMaterial = batch.templateMaterial.clone();
        batchMaterial.setShaderStorageBufferObject(DRAW_DATA_PARAM, batch.drawDataSsbo);
        batch.mdiGeometry.setMaterial(batchMaterial);
        batch.mdiGeometry.setCullHint(CullHint.Never);
        batch.mdiGeometry.setShadowMode(RenderQueue.ShadowMode.Inherit);

        attachChild(batch.mdiGeometry);
        dirtyBatches.add(batch);
    }

    private void serializeBatch(MdiBatch batch) {
        DrawDataLayout layout = batch.layout;
        ByteBuffer buf = batch.drawDataCpu;
        buf.rewind();

        for (int i = 0; i < batch.geometries.size(); i++) {
            Geometry geom = batch.geometries.get(i);
            Material mat = geom.getMaterial();
            Matrix4f worldMatrix = geom.getWorldMatrix();

            DrawDataSerializer.serialize(layout, buf, i * layout.getStride(),
                    worldMatrix, new ParamResolver(mat));
        }

        buf.rewind();
        batch.drawDataSsbo.setData(buf);
        batch.drawDataSsbo.setUpdateNeeded();
    }

    /**
     * Called by MdiNodeControl each render frame. Updates dirty batch SSBOs.
     */
    void updateBatches() {
        if (!batched) return;

        // Always mark all batches dirty so transforms stay up to date.
        // A more optimized version could track per-geometry dirtiness.
        for (MdiBatch batch : batchByLayout.values()) {
            updateBatchTransforms(batch);
        }
        dirtyBatches.clear();
    }

    private void updateBatchTransforms(MdiBatch batch) {
        DrawDataLayout layout = batch.layout;
        DrawDataField wmField = layout.findField("WorldMatrix");
        if (wmField == null) return;

        ByteBuffer buf = batch.drawDataCpu;
        for (int i = 0; i < batch.geometries.size(); i++) {
            Geometry geom = batch.geometries.get(i);
            int pos = i * layout.getStride() + wmField.getOffset();
            buf.position(pos);
            Matrix4f wm = geom.getWorldMatrix();
            buf.putFloat(wm.m00).putFloat(wm.m10).putFloat(wm.m20).putFloat(wm.m30);
            buf.putFloat(wm.m01).putFloat(wm.m11).putFloat(wm.m21).putFloat(wm.m31);
            buf.putFloat(wm.m02).putFloat(wm.m12).putFloat(wm.m22).putFloat(wm.m32);
            buf.putFloat(wm.m03).putFloat(wm.m13).putFloat(wm.m23).putFloat(wm.m33);
        }

        buf.rewind();
        batch.drawDataSsbo.setData(buf);
        batch.drawDataSsbo.setUpdateNeeded();
    }

    // --- GeometryGroupNode callbacks ---

    @Override
    public void onTransformChange(Geometry geom) {
        MdiBatch batch = batchByGeom.get(geom);
        if (batch != null) {
            dirtyBatches.add(batch);
        }
    }

    @Override
    public void onMaterialChange(Geometry geom) {
        if (batched) {
            logger.log(Level.WARNING,
                    "Material changed on {0} after batch(). Call batch() again to rebuild.",
                    geom.getName());
        }
    }

    @Override
    public void onMeshChange(Geometry geom) {
        if (batched) {
            logger.log(Level.WARNING,
                    "Mesh changed on {0} after batch(). Call batch() again to rebuild.",
                    geom.getName());
        }
    }

    @Override
    public void onGeometryUnassociated(Geometry geom) {
        MdiBatch batch = batchByGeom.remove(geom);
        if (batch != null) {
            batch.geometries.remove(geom);
            if (batched) {
                logger.log(Level.INFO,
                        "Geometry {0} removed from MdiNode. Call batch() to rebuild.",
                        geom.getName());
            }
        }
    }

    @Override
    public Spatial detachChildAt(int index) {
        Spatial s = super.detachChildAt(index);
        if (s instanceof MdiGeometry) {
            batchByLayout.values().removeIf(new java.util.function.Predicate<MdiBatch>() {
                @Override
                public boolean test(MdiBatch b) {
                    return b.mdiGeometry == s;
                }
            });
        }
        return s;
    }

    /** Resolves material parameter values, converting textures to bindless handles. */
    private static class ParamResolver implements java.util.function.Function<String, Object> {
        private final Material material;

        ParamResolver(Material material) {
            this.material = material;
        }

        @Override
        public Object apply(String paramName) {
            MatParam param = material.getParam(paramName);
            if (param == null) return null;

            Object value = param.getValue();
            if (param.getVarType().isTextureType() && value instanceof Texture) {
                Texture tex = (Texture) value;
                long handle = tex.getImage().getBindlessHandle();
                return handle != 0 ? handle : null;
            }
            return value;
        }
    }

    /** Internal batch state for one group of geometries sharing a DrawData layout. */
    private static class MdiBatch {
        final DrawDataLayout layout;
        final Material templateMaterial;
        final List<Geometry> geometries = new ArrayList<>();

        MeshCombiner.CombinedMesh combinedMesh;
        IndirectCommandBuffer commandBuffer;
        BufferObject drawDataSsbo;
        ByteBuffer drawDataCpu;
        MdiGeometry mdiGeometry;

        MdiBatch(DrawDataLayout layout, Material templateMaterial) {
            this.layout = layout;
            this.templateMaterial = templateMaterial;
        }
    }
}
