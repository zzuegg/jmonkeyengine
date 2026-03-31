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
package com.jme3.renderer.indirect;

import com.jme3.material.MatParam;
import com.jme3.math.Matrix4f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.indirect.MdiGeometry;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Mid-level API for multi-draw indirect rendering.
 * <p>
 * Combines multiple meshes into one, builds an indirect command buffer, and
 * manages a DrawData SSBO that is automatically populated from per-draw
 * parameter values. Provides per-frame transform updates.
 * <p>
 * Usage:
 * <pre>
 * DrawDataLayout layout = myMaterial.getMaterialDef().getDrawDataLayout();
 * IndirectBatch batch = new IndirectBatch(layout);
 * batch.addDraw(boxMesh, name -&gt; material1.getParam(name).getValue());
 * batch.addDraw(sphereMesh, name -&gt; material2.getParam(name).getValue());
 * batch.build();
 *
 * MdiGeometry geom = batch.createGeometry("myBatch");
 * geom.setMaterial(mdiMaterial);
 * rootNode.attachChild(geom);
 *
 * // Each frame:
 * batch.setWorldMatrix(0, geom1WorldMatrix);
 * batch.setWorldMatrix(1, geom2WorldMatrix);
 * batch.update();
 * </pre>
 */
public class IndirectBatch {

    private final DrawDataLayout layout;
    private final List<DrawEntry> entries = new ArrayList<>();

    // Built state
    private MeshCombiner.CombinedMesh combinedMesh;
    private IndirectCommandBuffer commandBuffer;
    private BufferObject drawDataBuffer;
    private ByteBuffer drawDataCpu;
    private boolean built = false;

    public IndirectBatch(DrawDataLayout layout) {
        this.layout = layout;
    }

    /**
     * Adds a draw entry with a mesh and parameter lookup function.
     * The lookup function resolves material parameter values by name.
     * For texture params, return {@code Long} (bindless handle) or null.
     *
     * @param mesh        the mesh for this draw
     * @param paramLookup resolves param name to value
     */
    public void addDraw(Mesh mesh, Function<String, Object> paramLookup) {
        entries.add(new DrawEntry(mesh, paramLookup));
    }

    /**
     * Adds a draw entry from an existing Geometry, reading param values
     * from its material.
     */
    public void addDraw(Geometry geometry) {
        entries.add(new DrawEntry(geometry.getMesh(), new Function<String, Object>() {
            @Override
            public Object apply(String name) {
                MatParam param = geometry.getMaterial().getParam(name);
                return param != null ? param.getValue() : null;
            }
        }));
    }

    /**
     * Combines meshes, builds the command buffer, and populates the DrawData SSBO.
     * Must be called after all draws are added and before rendering.
     */
    public void build() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("No draws added to batch");
        }

        // Combine meshes
        Mesh[] meshes = new Mesh[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            meshes[i] = entries.get(i).mesh;
        }
        combinedMesh = MeshCombiner.combine(meshes);

        // Build command buffer
        commandBuffer = combinedMesh.toCommandBuffer();
        commandBuffer.update();

        // Allocate DrawData SSBO
        int totalBytes = entries.size() * layout.getStride();
        drawDataCpu = BufferUtils.createByteBuffer(totalBytes);
        drawDataBuffer = new BufferObject();
        drawDataBuffer.setAccessHint(BufferObject.AccessHint.Dynamic);
        drawDataBuffer.setNatureHint(BufferObject.NatureHint.Draw);

        // Serialize initial param values
        for (int i = 0; i < entries.size(); i++) {
            DrawDataSerializer.serialize(layout, drawDataCpu, i * layout.getStride(),
                    Matrix4f.IDENTITY, entries.get(i).paramLookup);
        }
        drawDataCpu.rewind();
        drawDataBuffer.setData(drawDataCpu);
        drawDataBuffer.setUpdateNeeded();

        built = true;
    }

    /**
     * Sets the world matrix for draw at the given index.
     * Call {@link #update()} after setting all transforms.
     */
    public void setWorldMatrix(int drawIndex, Matrix4f worldMatrix) {
        checkBuilt();
        DrawDataField wmField = layout.findField("WorldMatrix");
        if (wmField == null || !wmField.isWorldMatrix()) return;

        int pos = drawIndex * layout.getStride() + wmField.getOffset();
        drawDataCpu.position(pos);
        writeMat4(drawDataCpu, worldMatrix);
    }

    /**
     * Uploads the DrawData SSBO to the GPU. Call after modifying transforms.
     */
    public void update() {
        checkBuilt();
        drawDataCpu.rewind();
        drawDataBuffer.setData(drawDataCpu);
        drawDataBuffer.setUpdateNeeded();
    }

    /**
     * Creates an MdiGeometry using the combined mesh and command buffer.
     * Set a material on the returned geometry and attach it to the scene graph.
     * The DrawData SSBO must be set on the material:
     * {@code material.setShaderStorageBufferObject("DrawData", batch.getDrawDataBuffer())}
     */
    public MdiGeometry createGeometry(String name) {
        checkBuilt();
        return new MdiGeometry(name, combinedMesh.getMesh(), commandBuffer);
    }

    public int getDrawCount() { return entries.size(); }
    public DrawDataLayout getLayout() { return layout; }
    public Mesh getCombinedMesh() { checkBuilt(); return combinedMesh.getMesh(); }
    public IndirectCommandBuffer getCommandBuffer() { checkBuilt(); return commandBuffer; }
    public BufferObject getDrawDataBuffer() { checkBuilt(); return drawDataBuffer; }
    public MeshCombiner.CombinedMesh getCombinedMeshInfo() { checkBuilt(); return combinedMesh; }

    private void checkBuilt() {
        if (!built) throw new IllegalStateException("IndirectBatch not built yet. Call build() first.");
    }

    private static void writeMat4(ByteBuffer buf, Matrix4f m) {
        buf.putFloat(m.m00).putFloat(m.m10).putFloat(m.m20).putFloat(m.m30);
        buf.putFloat(m.m01).putFloat(m.m11).putFloat(m.m21).putFloat(m.m31);
        buf.putFloat(m.m02).putFloat(m.m12).putFloat(m.m22).putFloat(m.m32);
        buf.putFloat(m.m03).putFloat(m.m13).putFloat(m.m23).putFloat(m.m33);
    }

    private static class DrawEntry {
        final Mesh mesh;
        final Function<String, Object> paramLookup;

        DrawEntry(Mesh mesh, Function<String, Object> paramLookup) {
            this.mesh = mesh;
            this.paramLookup = paramLookup;
        }
    }
}
