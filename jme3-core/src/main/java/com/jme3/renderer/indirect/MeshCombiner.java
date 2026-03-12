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

import com.jme3.math.Matrix4f;
import com.jme3.math.Transform;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Format;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.util.BufferUtils;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Combines multiple meshes into a single mesh suitable for multi-draw indirect rendering.
 * <p>
 * Concatenates vertex and index buffers from all input meshes into one combined mesh,
 * tracking per-sub-mesh offsets. These offsets map directly to
 * {@link DrawElementsIndirectCommand} fields ({@code firstIndex}, {@code baseVertex})
 * for issuing multiple draw commands from a single buffer binding.
 * <p>
 * <b>Static vs dynamic transforms:</b> For objects that move each frame, use
 * {@link #combine(Mesh...)} or {@link #combine(Geometry...)} which do NOT bake
 * transforms into vertex data. Instead, pass per-draw world matrices via an SSBO
 * indexed by {@code gl_DrawID} in the vertex shader. For pre-baked static scenes,
 * use {@link #combineWithTransforms(Entry...)} which bakes transforms into positions
 * and normals.
 * <p>
 * <b>LOD support:</b> If all input meshes have LOD index buffers, the combined mesh
 * will contain combined LOD index buffers and per-LOD {@link SubMeshInfo} arrays.
 * The number of combined LOD levels is the minimum across all input meshes.
 * <p>
 * Dynamic transforms example:
 * <pre>
 * // Combine meshes (vertex data is in local space)
 * CombinedMesh combined = MeshCombiner.combine(boxMesh, sphereMesh, monkeyMesh);
 *
 * // Each frame: write world matrices to SSBO indexed by gl_DrawID
 * ByteBuffer buf = transformSsbo.getData();
 * for (Geometry geom : geometries) {
 *     geom.getWorldTransform().toTransformMatrix(tempMat);
 *     writeMat4(buf, tempMat);
 * }
 * buf.rewind();
 * transformSsbo.setUpdateNeeded();
 * </pre>
 * <p>
 * Static transforms example:
 * <pre>
 * CombinedMesh combined = MeshCombiner.combineWithTransforms(
 *     new MeshCombiner.Entry(boxMesh, boxTransform),
 *     new MeshCombiner.Entry(sphereMesh, sphereTransform)
 * );
 * </pre>
 */
public class MeshCombiner {

    /**
     * A mesh paired with a transform to bake into vertex positions.
     * Used only with {@link #combineWithTransforms(Entry...)}.
     */
    public static class Entry {
        private final Mesh mesh;
        private final Transform transform;

        /**
         * @param mesh the mesh to include
         * @param transform transform to bake into positions/normals, or null for identity
         */
        public Entry(Mesh mesh, Transform transform) {
            this.mesh = mesh;
            this.transform = transform;
        }

        /**
         * Creates an entry with no transform (identity).
         */
        public Entry(Mesh mesh) {
            this(mesh, null);
        }
    }

    /**
     * Describes a sub-mesh within the combined mesh, with offsets suitable for
     * populating a {@link DrawElementsIndirectCommand}.
     */
    public static class SubMeshInfo {
        private final int indexCount;
        private final int firstIndex;
        private final int baseVertex;

        SubMeshInfo(int indexCount, int firstIndex, int baseVertex) {
            this.indexCount = indexCount;
            this.firstIndex = firstIndex;
            this.baseVertex = baseVertex;
        }

        /** Number of indices for this sub-mesh's draw command. */
        public int getIndexCount() { return indexCount; }

        /** Offset into the combined index buffer (in indices, not bytes). */
        public int getFirstIndex() { return firstIndex; }

        /** Value added to each index value before vertex fetch. */
        public int getBaseVertex() { return baseVertex; }

        /**
         * Creates a {@link DrawElementsIndirectCommand} for this sub-mesh.
         * @param instanceCount number of instances to draw
         * @param baseInstance base instance for instanced vertex attributes
         */
        public DrawElementsIndirectCommand toCommand(int instanceCount, int baseInstance) {
            return new DrawElementsIndirectCommand(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
        }

        /**
         * Creates a single-instance {@link DrawElementsIndirectCommand} for this sub-mesh.
         */
        public DrawElementsIndirectCommand toCommand() {
            return toCommand(1, 0);
        }
    }

    /**
     * The result of combining multiple meshes.
     */
    public static class CombinedMesh {
        private final Mesh mesh;
        private final List<SubMeshInfo> subMeshes;
        private final List<List<SubMeshInfo>> lodSubMeshes;

        CombinedMesh(Mesh mesh, List<SubMeshInfo> subMeshes, List<List<SubMeshInfo>> lodSubMeshes) {
            this.mesh = mesh;
            this.subMeshes = Collections.unmodifiableList(subMeshes);
            List<List<SubMeshInfo>> unmodLod = new ArrayList<>(lodSubMeshes.size());
            for (List<SubMeshInfo> level : lodSubMeshes) {
                unmodLod.add(Collections.unmodifiableList(level));
            }
            this.lodSubMeshes = Collections.unmodifiableList(unmodLod);
        }

        /** The combined mesh containing all vertex and index data. */
        public Mesh getMesh() { return mesh; }

        /** Per-sub-mesh offset info for the base (LOD 0) index buffer. */
        public List<SubMeshInfo> getSubMeshes() { return subMeshes; }

        /**
         * Returns the number of LOD levels available (not counting the base level).
         */
        public int getNumLodLevels() { return lodSubMeshes.size(); }

        /**
         * Per-sub-mesh offset info for the given LOD level.
         * @param lod LOD level index (0-based, separate from the base level)
         */
        public List<SubMeshInfo> getLodSubMeshes(int lod) { return lodSubMeshes.get(lod); }

        /**
         * Builds an {@link IndirectCommandBuffer} from the base index buffer
         * with one draw command per sub-mesh, each drawing 1 instance.
         */
        public IndirectCommandBuffer toCommandBuffer() {
            return buildCommandBuffer(subMeshes, 1);
        }

        /**
         * Builds an {@link IndirectCommandBuffer} from the base index buffer.
         * @param instanceCount instances per draw command
         */
        public IndirectCommandBuffer toCommandBuffer(int instanceCount) {
            return buildCommandBuffer(subMeshes, instanceCount);
        }

        /**
         * Builds an {@link IndirectCommandBuffer} for the given LOD level.
         * @param lod LOD level index
         * @param instanceCount instances per draw command
         */
        public IndirectCommandBuffer toLodCommandBuffer(int lod, int instanceCount) {
            return buildCommandBuffer(lodSubMeshes.get(lod), instanceCount);
        }

        /**
         * Builds an {@link IndirectCommandBuffer} for the given LOD level,
         * each drawing 1 instance.
         * @param lod LOD level index
         */
        public IndirectCommandBuffer toLodCommandBuffer(int lod) {
            return toLodCommandBuffer(lod, 1);
        }

        private static IndirectCommandBuffer buildCommandBuffer(List<SubMeshInfo> infos, int instanceCount) {
            IndirectCommandBuffer buf = new IndirectCommandBuffer(IndirectCommandBuffer.DrawType.Elements);
            for (SubMeshInfo info : infos) {
                buf.addCommand(info.toCommand(instanceCount, 0));
            }
            return buf;
        }
    }

    // -----------------------------------------------------------------------
    // Dynamic transform variants (no baking — vertex data stays in local space)
    // -----------------------------------------------------------------------

    /**
     * Combines multiple meshes without baking any transforms.
     * <p>
     * Vertex data remains in local/model space. Per-draw world matrices should
     * be passed to the shader via an SSBO indexed by {@code gl_DrawID}.
     *
     * @param meshes the meshes to combine
     * @return the combined mesh and sub-mesh offset info (including LOD)
     * @throws IllegalArgumentException if any mesh lacks an index buffer or meshes is empty
     */
    public static CombinedMesh combine(Mesh... meshes) {
        Entry[] entries = new Entry[meshes.length];
        for (int i = 0; i < meshes.length; i++) {
            entries[i] = new Entry(meshes[i]); // null transform = no baking
        }
        return doCombine(entries, false);
    }

    /**
     * Combines geometries without baking transforms.
     * <p>
     * Only the mesh and LOD data are used from each geometry — world transforms
     * are ignored. Pass per-draw world matrices via an SSBO indexed by
     * {@code gl_DrawID}.
     *
     * @param geometries the geometries whose meshes to combine
     * @return the combined mesh and sub-mesh offset info (including LOD)
     */
    public static CombinedMesh combine(Geometry... geometries) {
        Entry[] entries = new Entry[geometries.length];
        for (int i = 0; i < geometries.length; i++) {
            entries[i] = new Entry(geometries[i].getMesh()); // no transform
        }
        return doCombine(entries, false);
    }

    // -----------------------------------------------------------------------
    // Static transform variant (bakes transforms into vertex data)
    // -----------------------------------------------------------------------

    /**
     * Combines multiple meshes, baking transforms into vertex positions and normals.
     * <p>
     * Use this for pre-baked static scenes where objects do not move.
     * The resulting vertex data is in world space.
     *
     * @param entries the meshes with transforms to bake
     * @return the combined mesh and sub-mesh offset info (including LOD)
     * @throws IllegalArgumentException if any mesh lacks an index buffer or entries is empty
     */
    public static CombinedMesh combineWithTransforms(Entry... entries) {
        return doCombine(entries, true);
    }

    // -----------------------------------------------------------------------
    // Shared implementation
    // -----------------------------------------------------------------------

    private static CombinedMesh doCombine(Entry[] entries, boolean bakeTransforms) {
        if (entries.length == 0) {
            throw new IllegalArgumentException("At least one mesh is required");
        }

        // Compute totals and LOD level count
        int totalVerts = 0;
        int totalIndices = 0;
        int minLodLevels = Integer.MAX_VALUE;
        for (Entry entry : entries) {
            Mesh m = entry.mesh;
            if (m.getBuffer(Type.Index) == null) {
                throw new IllegalArgumentException(
                        "All meshes must have an index buffer for MDI combining");
            }
            totalVerts += m.getVertexCount();
            totalIndices += m.getTriangleCount() * 3;
            minLodLevels = Math.min(minLodLevels, m.getNumLodLevels());
        }
        if (minLodLevels == Integer.MAX_VALUE) {
            minLodLevels = 0;
        }

        // Determine common vertex attributes
        boolean hasNormals = true;
        boolean hasTexCoords = true;
        for (Entry entry : entries) {
            if (entry.mesh.getBuffer(Type.Normal) == null) hasNormals = false;
            if (entry.mesh.getBuffer(Type.TexCoord) == null) hasTexCoords = false;
        }

        boolean use32Bit = totalVerts >= 65536;
        Format indexFormat = use32Bit ? Format.UnsignedInt : Format.UnsignedShort;

        // Allocate combined vertex buffers
        Mesh outMesh = new Mesh();
        outMesh.setMode(Mesh.Mode.Triangles);

        FloatBuffer combinedPos = BufferUtils.createVector3Buffer(totalVerts);
        FloatBuffer combinedNorm = hasNormals
                ? BufferUtils.createVector3Buffer(totalVerts) : null;
        FloatBuffer combinedTex = hasTexCoords
                ? BufferUtils.createVector2Buffer(totalVerts) : null;

        // Allocate base index buffer
        Buffer baseIndexData = allocIndexBuffer(totalIndices, use32Bit);

        // Allocate LOD index buffers
        Buffer[] lodIndexData = new Buffer[minLodLevels];
        for (int lod = 0; lod < minLodLevels; lod++) {
            int total = 0;
            for (Entry entry : entries) {
                total += entry.mesh.getTriangleCount(lod) * 3;
            }
            lodIndexData[lod] = allocIndexBuffer(total, use32Bit);
        }

        // Iterate entries and copy data
        int vertOffset = 0;
        int baseIndexOffset = 0;
        int[] lodIndexOffsets = new int[minLodLevels];
        List<SubMeshInfo> baseInfos = new ArrayList<>(entries.length);
        List<List<SubMeshInfo>> lodInfos = new ArrayList<>(minLodLevels);
        for (int lod = 0; lod < minLodLevels; lod++) {
            lodInfos.add(new ArrayList<>(entries.length));
        }

        for (Entry entry : entries) {
            Mesh inMesh = entry.mesh;
            int meshVertCount = inMesh.getVertexCount();
            int meshIndexCount = inMesh.getTriangleCount() * 3;

            // Base sub-mesh info
            baseInfos.add(new SubMeshInfo(meshIndexCount, baseIndexOffset, vertOffset));

            // LOD sub-mesh infos
            for (int lod = 0; lod < minLodLevels; lod++) {
                int lodIdxCount = inMesh.getTriangleCount(lod) * 3;
                lodInfos.get(lod).add(
                        new SubMeshInfo(lodIdxCount, lodIndexOffsets[lod], vertOffset));
                lodIndexOffsets[lod] += lodIdxCount;
            }

            // Build transform matrices (only used when baking)
            Matrix4f mat = null;
            Matrix4f normalMat = null;
            if (bakeTransforms && entry.transform != null) {
                mat = new Matrix4f();
                mat.setTransform(entry.transform.getTranslation(),
                        entry.transform.getScale(),
                        entry.transform.getRotation().toRotationMatrix());
                normalMat = mat.clone();
                normalMat.setTranslation(0, 0, 0);
            }

            // Copy positions
            FloatBuffer inPos = (FloatBuffer) inMesh.getBuffer(Type.Position)
                    .getDataReadOnly();
            inPos.rewind();
            for (int v = 0; v < meshVertCount; v++) {
                float x = inPos.get(), y = inPos.get(), z = inPos.get();
                if (mat != null) {
                    float tx = mat.m00 * x + mat.m01 * y + mat.m02 * z + mat.m03;
                    float ty = mat.m10 * x + mat.m11 * y + mat.m12 * z + mat.m13;
                    float tz = mat.m20 * x + mat.m21 * y + mat.m22 * z + mat.m23;
                    x = tx; y = ty; z = tz;
                }
                combinedPos.put(x).put(y).put(z);
            }

            // Copy normals
            if (hasNormals && inMesh.getBuffer(Type.Normal) != null) {
                FloatBuffer inNorm = (FloatBuffer) inMesh.getBuffer(Type.Normal)
                        .getDataReadOnly();
                inNorm.rewind();
                for (int v = 0; v < meshVertCount; v++) {
                    float nx = inNorm.get(), ny = inNorm.get(), nz = inNorm.get();
                    if (normalMat != null) {
                        float tnx = normalMat.m00 * nx + normalMat.m01 * ny
                                + normalMat.m02 * nz;
                        float tny = normalMat.m10 * nx + normalMat.m11 * ny
                                + normalMat.m12 * nz;
                        float tnz = normalMat.m20 * nx + normalMat.m21 * ny
                                + normalMat.m22 * nz;
                        float len = (float) Math.sqrt(
                                tnx * tnx + tny * tny + tnz * tnz);
                        if (len > 0) { tnx /= len; tny /= len; tnz /= len; }
                        nx = tnx; ny = tny; nz = tnz;
                    }
                    combinedNorm.put(nx).put(ny).put(nz);
                }
            }

            // Copy texcoords (never transformed)
            if (hasTexCoords && inMesh.getBuffer(Type.TexCoord) != null) {
                FloatBuffer inTex = (FloatBuffer) inMesh.getBuffer(Type.TexCoord)
                        .getDataReadOnly();
                inTex.rewind();
                for (int v = 0; v < meshVertCount; v++) {
                    combinedTex.put(inTex.get()).put(inTex.get());
                }
            }

            // Copy base indices (raw — baseVertex handles offset)
            copyIndices(inMesh.getIndicesAsList(), meshIndexCount,
                    baseIndexData, use32Bit);

            // Copy LOD indices
            for (int lod = 0; lod < minLodLevels; lod++) {
                VertexBuffer lodVb = inMesh.getLodLevel(lod);
                IndexBuffer lodIdx = IndexBuffer.wrapIndexBuffer(lodVb.getData());
                int lodCount = inMesh.getTriangleCount(lod) * 3;
                copyIndices(lodIdx, lodCount, lodIndexData[lod], use32Bit);
            }

            vertOffset += meshVertCount;
            baseIndexOffset += meshIndexCount;
        }

        // Flip all buffers and assign to output mesh
        combinedPos.flip();
        if (combinedNorm != null) combinedNorm.flip();
        if (combinedTex != null) combinedTex.flip();
        baseIndexData.flip();

        outMesh.setBuffer(Type.Position, 3, combinedPos);
        if (combinedNorm != null) {
            outMesh.setBuffer(Type.Normal, 3, combinedNorm);
        }
        if (combinedTex != null) {
            outMesh.setBuffer(Type.TexCoord, 2, combinedTex);
        }

        VertexBuffer baseIndexVb = new VertexBuffer(Type.Index);
        baseIndexVb.setupData(Usage.Static, 3, indexFormat, baseIndexData);
        outMesh.setBuffer(baseIndexVb);

        // Set LOD levels on output mesh
        if (minLodLevels > 0) {
            VertexBuffer[] lodLevels = new VertexBuffer[minLodLevels];
            for (int lod = 0; lod < minLodLevels; lod++) {
                lodIndexData[lod].flip();
                VertexBuffer lodVb = new VertexBuffer(Type.Index);
                lodVb.setupData(Usage.Static, 3, indexFormat, lodIndexData[lod]);
                lodLevels[lod] = lodVb;
            }
            outMesh.setLodLevels(lodLevels);
        }

        outMesh.updateCounts();
        outMesh.updateBound();

        return new CombinedMesh(outMesh, baseInfos, lodInfos);
    }

    /**
     * Writes a {@link Matrix4f} to a {@link ByteBuffer} in column-major order
     * (std430 layout). Convenience for building per-draw transform SSBOs.
     *
     * @param buf the buffer to write to (must have at least 64 bytes remaining)
     * @param mat the matrix to write
     */
    public static void writeMat4(ByteBuffer buf, Matrix4f mat) {
        // Column 0
        buf.putFloat(mat.m00).putFloat(mat.m10).putFloat(mat.m20).putFloat(mat.m30);
        // Column 1
        buf.putFloat(mat.m01).putFloat(mat.m11).putFloat(mat.m21).putFloat(mat.m31);
        // Column 2
        buf.putFloat(mat.m02).putFloat(mat.m12).putFloat(mat.m22).putFloat(mat.m32);
        // Column 3
        buf.putFloat(mat.m03).putFloat(mat.m13).putFloat(mat.m23).putFloat(mat.m33);
    }

    private static Buffer allocIndexBuffer(int count, boolean use32Bit) {
        return use32Bit
                ? BufferUtils.createIntBuffer(count)
                : BufferUtils.createShortBuffer(count);
    }

    private static void copyIndices(IndexBuffer src, int count,
            Buffer dest, boolean use32Bit) {
        if (use32Bit) {
            IntBuffer ib = (IntBuffer) dest;
            for (int i = 0; i < count; i++) {
                ib.put(src.get(i));
            }
        } else {
            ShortBuffer sb = (ShortBuffer) dest;
            for (int i = 0; i < count; i++) {
                sb.put((short) src.get(i));
            }
        }
    }
}
