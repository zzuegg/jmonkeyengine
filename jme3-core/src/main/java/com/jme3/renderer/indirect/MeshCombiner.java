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
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Format;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.util.BufferUtils;

import java.nio.Buffer;
import java.nio.FloatBuffer;
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
 * Positions and normals are optionally transformed by per-mesh {@link Transform}s,
 * baking world-space placement into the vertex data.
 * <p>
 * Example:
 * <pre>
 * CombinedMesh combined = MeshCombiner.combine(
 *     new MeshCombiner.Entry(boxMesh, boxTransform),
 *     new MeshCombiner.Entry(sphereMesh, sphereTransform),
 *     new MeshCombiner.Entry(monkeyMesh, monkeyTransform)
 * );
 *
 * // Build indirect command buffer from the sub-mesh info
 * IndirectCommandBuffer cmdBuf = combined.toCommandBuffer();
 * cmdBuf.update();
 *
 * renderer.renderMeshMultiIndirect(combined.getMesh(), cmdBuf.getBufferObject(),
 *     cmdBuf.getCommandCount(), 0);
 * </pre>
 */
public class MeshCombiner {

    /**
     * A mesh paired with an optional transform to bake into vertex positions.
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

        CombinedMesh(Mesh mesh, List<SubMeshInfo> subMeshes) {
            this.mesh = mesh;
            this.subMeshes = Collections.unmodifiableList(subMeshes);
        }

        /** The combined mesh containing all vertex and index data. */
        public Mesh getMesh() { return mesh; }

        /** Per-sub-mesh offset info, in the order they were provided. */
        public List<SubMeshInfo> getSubMeshes() { return subMeshes; }

        /**
         * Builds an {@link IndirectCommandBuffer} with one draw command per sub-mesh,
         * each drawing 1 instance.
         */
        public IndirectCommandBuffer toCommandBuffer() {
            return toCommandBuffer(1);
        }

        /**
         * Builds an {@link IndirectCommandBuffer} with one draw command per sub-mesh.
         * @param instanceCount instances per draw command
         */
        public IndirectCommandBuffer toCommandBuffer(int instanceCount) {
            IndirectCommandBuffer buf = new IndirectCommandBuffer(IndirectCommandBuffer.DrawType.Elements);
            for (SubMeshInfo info : subMeshes) {
                buf.addCommand(info.toCommand(instanceCount, 0));
            }
            return buf;
        }
    }

    /**
     * Combines multiple meshes into a single mesh.
     * <p>
     * All input meshes must be indexed (have an Index buffer) and use Triangles mode.
     * Vertex buffers common to all meshes (Position, Normal, TexCoord) are concatenated.
     * Positions and normals are transformed by each entry's transform if provided.
     *
     * @param entries the meshes to combine with optional transforms
     * @return the combined mesh and sub-mesh offset info
     * @throws IllegalArgumentException if any mesh lacks an index buffer or entries is empty
     */
    public static CombinedMesh combine(Entry... entries) {
        if (entries.length == 0) {
            throw new IllegalArgumentException("At least one mesh entry is required");
        }

        // Compute totals
        int totalVerts = 0;
        int totalIndices = 0;
        for (Entry entry : entries) {
            Mesh m = entry.mesh;
            if (m.getBuffer(Type.Index) == null) {
                throw new IllegalArgumentException("All meshes must have an index buffer for MDI combining");
            }
            totalVerts += m.getVertexCount();
            totalIndices += m.getTriangleCount() * 3;
        }

        // Determine which buffer types are common to ALL meshes
        boolean hasNormals = true;
        boolean hasTexCoords = true;
        for (Entry entry : entries) {
            if (entry.mesh.getBuffer(Type.Normal) == null) hasNormals = false;
            if (entry.mesh.getBuffer(Type.TexCoord) == null) hasTexCoords = false;
        }

        // Allocate combined buffers
        Mesh outMesh = new Mesh();
        outMesh.setMode(Mesh.Mode.Triangles);

        FloatBuffer combinedPos = BufferUtils.createVector3Buffer(totalVerts);
        FloatBuffer combinedNorm = hasNormals ? BufferUtils.createVector3Buffer(totalVerts) : null;
        FloatBuffer combinedTex = hasTexCoords ? BufferUtils.createVector2Buffer(totalVerts) : null;

        // Use int indices if total vertex count exceeds short range
        boolean use32Bit = totalVerts >= 65536;
        Buffer indexData;
        Format indexFormat;
        if (use32Bit) {
            indexData = BufferUtils.createIntBuffer(totalIndices);
            indexFormat = Format.UnsignedInt;
        } else {
            indexData = BufferUtils.createShortBuffer(totalIndices);
            indexFormat = Format.UnsignedShort;
        }

        // Iterate and copy
        int vertOffset = 0;
        int indexOffset = 0;
        List<SubMeshInfo> subMeshInfos = new ArrayList<>(entries.length);

        for (Entry entry : entries) {
            Mesh inMesh = entry.mesh;
            int meshVertCount = inMesh.getVertexCount();
            int meshIndexCount = inMesh.getTriangleCount() * 3;

            // Record sub-mesh info (using baseVertex, no index adjustment needed)
            subMeshInfos.add(new SubMeshInfo(meshIndexCount, indexOffset, vertOffset));

            // Transform matrix (if any)
            Matrix4f mat = null;
            Matrix4f normalMat = null;
            if (entry.transform != null) {
                mat = new Matrix4f();
                mat.setTransform(entry.transform.getTranslation(),
                        entry.transform.getScale(),
                        entry.transform.getRotation().toRotationMatrix());
                // Normal matrix = transpose of inverse of upper-left 3x3
                // For uniform scale + rotation, just the rotation part suffices
                normalMat = mat.clone();
                normalMat.setTranslation(0, 0, 0);
            }

            // Copy positions (with transform)
            FloatBuffer inPos = (FloatBuffer) inMesh.getBuffer(Type.Position).getDataReadOnly();
            inPos.rewind();
            for (int v = 0; v < meshVertCount; v++) {
                float x = inPos.get();
                float y = inPos.get();
                float z = inPos.get();
                if (mat != null) {
                    float tx = mat.m00 * x + mat.m01 * y + mat.m02 * z + mat.m03;
                    float ty = mat.m10 * x + mat.m11 * y + mat.m12 * z + mat.m13;
                    float tz = mat.m20 * x + mat.m21 * y + mat.m22 * z + mat.m23;
                    x = tx; y = ty; z = tz;
                }
                combinedPos.put(x).put(y).put(z);
            }

            // Copy normals (with rotation only)
            if (hasNormals && inMesh.getBuffer(Type.Normal) != null) {
                FloatBuffer inNorm = (FloatBuffer) inMesh.getBuffer(Type.Normal).getDataReadOnly();
                inNorm.rewind();
                for (int v = 0; v < meshVertCount; v++) {
                    float nx = inNorm.get();
                    float ny = inNorm.get();
                    float nz = inNorm.get();
                    if (normalMat != null) {
                        float tnx = normalMat.m00 * nx + normalMat.m01 * ny + normalMat.m02 * nz;
                        float tny = normalMat.m10 * nx + normalMat.m11 * ny + normalMat.m12 * nz;
                        float tnz = normalMat.m20 * nx + normalMat.m21 * ny + normalMat.m22 * nz;
                        // Normalize
                        float len = (float) Math.sqrt(tnx * tnx + tny * tny + tnz * tnz);
                        if (len > 0) { tnx /= len; tny /= len; tnz /= len; }
                        nx = tnx; ny = tny; nz = tnz;
                    }
                    combinedNorm.put(nx).put(ny).put(nz);
                }
            }

            // Copy texcoords (no transform)
            if (hasTexCoords && inMesh.getBuffer(Type.TexCoord) != null) {
                FloatBuffer inTex = (FloatBuffer) inMesh.getBuffer(Type.TexCoord).getDataReadOnly();
                inTex.rewind();
                for (int v = 0; v < meshVertCount; v++) {
                    combinedTex.put(inTex.get()).put(inTex.get());
                }
            }

            // Copy indices (raw values — baseVertex in the draw command handles the offset)
            IndexBuffer inIdx = inMesh.getIndicesAsList();
            for (int i = 0; i < meshIndexCount; i++) {
                int idx = inIdx.get(i);
                if (use32Bit) {
                    ((java.nio.IntBuffer) indexData).put(idx);
                } else {
                    ((java.nio.ShortBuffer) indexData).put((short) idx);
                }
            }

            vertOffset += meshVertCount;
            indexOffset += meshIndexCount;
        }

        // Flip all buffers
        combinedPos.flip();
        if (combinedNorm != null) combinedNorm.flip();
        if (combinedTex != null) combinedTex.flip();
        indexData.flip();

        // Set buffers on output mesh
        outMesh.setBuffer(Type.Position, 3, combinedPos);
        if (combinedNorm != null) {
            outMesh.setBuffer(Type.Normal, 3, combinedNorm);
        }
        if (combinedTex != null) {
            outMesh.setBuffer(Type.TexCoord, 2, combinedTex);
        }

        VertexBuffer indexVb = new VertexBuffer(Type.Index);
        indexVb.setupData(Usage.Static, 3, indexFormat, indexData);
        outMesh.setBuffer(indexVb);

        outMesh.updateCounts();
        outMesh.updateBound();

        return new CombinedMesh(outMesh, subMeshInfos);
    }
}
