/*
 * Copyright (c) 2009-2021 jMonkeyEngine
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
package com.jme3.renderer;

import com.jme3.scene.Mesh;
import com.jme3.shader.Shader;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.IntMap;

/**
 * Allows tracking of real-time rendering statistics.
 *
 * <p>The <code>Statistics</code> can be retrieved by using {@link Renderer#getStatistics() }.
 *
 * @author Kirill Vainer
 */
public class Statistics {

    protected boolean enabled = false;

    // Per-frame draw call counters
    protected int numDrawCalls;
    protected int numInstancedDrawCalls;
    protected int numInstancedObjects;
    protected int numIndirectDrawCalls;
    protected int numIndirectObjects;

    protected int numTriangles;
    protected int numVertices;
    protected int numShaderSwitches;
    protected int numTextureBinds;
    protected int numFboSwitches;
    protected int numUniformsSet;

    // Memory counters (lifetime)
    protected int memoryShaders;
    protected int memoryFrameBuffers;
    protected int memoryTextures;

    protected IntMap<Void> shadersUsed = new IntMap<>();
    protected IntMap<Void> texturesUsed = new IntMap<>();
    protected IntMap<Void> fbosUsed = new IntMap<>();

    protected int lastShader = -1;

    public String[] getLabels(){
        return new String[]{ "Vertices",
                             "Triangles",
                             "Uniforms",

                             "DrawCalls",
                             "InstancedDrawCalls",
                             "InstancedObjects",
                             "IndirectDrawCalls",
                             "IndirectObjects",

                             "Shaders (S)",
                             "Shaders (F)",
                             "Shaders (M)",

                             "Textures (S)",
                             "Textures (F)",
                             "Textures (M)",

                             "FrameBuffers (S)",
                             "FrameBuffers (F)",
                             "FrameBuffers (M)" };
    }

    public void getData(int[] data) {
        data[0] = numVertices;
        data[1] = numTriangles;
        data[2] = numUniformsSet;

        data[3] = numDrawCalls;
        data[4] = numInstancedDrawCalls;
        data[5] = numInstancedObjects;
        data[6] = numIndirectDrawCalls;
        data[7] = numIndirectObjects;

        data[8] = numShaderSwitches;
        data[9] = shadersUsed.size();
        data[10] = memoryShaders;

        data[11] = numTextureBinds;
        data[12] = texturesUsed.size();
        data[13] = memoryTextures;

        data[14] = numFboSwitches;
        data[15] = fbosUsed.size();
        data[16] = memoryFrameBuffers;
    }

    /**
     * Called by the Renderer when a mesh has been drawn via a standard draw call.
     */
    public void onMeshDrawn(Mesh mesh, int lod, int count) {
        if (!enabled) return;

        if (count > 1) {
            numInstancedDrawCalls++;
            numInstancedObjects += count;
        } else {
            numDrawCalls++;
        }
        numTriangles += mesh.getTriangleCount(lod) * count;
        numVertices += mesh.getVertexCount() * count;
    }

    public void onMeshDrawn(Mesh mesh, int lod) {
        onMeshDrawn(mesh, lod, 1);
    }

    /**
     * Called by the Renderer when a mesh has been drawn via indirect draw.
     *
     * @param mesh the combined mesh
     * @param drawCount number of draw commands in the indirect buffer
     */
    public void onMeshDrawnIndirect(Mesh mesh, int drawCount) {
        if (!enabled) return;

        numIndirectDrawCalls++;
        numIndirectObjects += drawCount;
        numTriangles += mesh.getTriangleCount();
        numVertices += mesh.getVertexCount();
    }

    public void onShaderUse(Shader shader, boolean wasSwitched) {
        assert shader.getId() >= 1;
        if (!enabled) return;

        if (lastShader != shader.getId()) {
            lastShader = shader.getId();
            if (!shadersUsed.containsKey(shader.getId())) {
                shadersUsed.put(shader.getId(), null);
            }
        }

        if (wasSwitched) {
            numShaderSwitches++;
        }
    }

    public void onUniformSet() {
        if (!enabled) return;
        numUniformsSet++;
    }

    public void onTextureUse(Image image, boolean wasSwitched) {
        assert image.getId() >= 1;
        if (!enabled) return;

        if (!texturesUsed.containsKey(image.getId())) {
            texturesUsed.put(image.getId(), null);
        }

        if (wasSwitched) {
            numTextureBinds++;
        }
    }

    public void onFrameBufferUse(FrameBuffer fb, boolean wasSwitched) {
        if (!enabled) return;

        if (fb != null) {
            assert fb.getId() >= 1;
            if (!fbosUsed.containsKey(fb.getId())) {
                fbosUsed.put(fb.getId(), null);
            }
        }

        if (wasSwitched) {
            numFboSwitches++;
        }
    }

    public void clearFrame() {
        shadersUsed.clear();
        texturesUsed.clear();
        fbosUsed.clear();

        numDrawCalls = 0;
        numInstancedDrawCalls = 0;
        numInstancedObjects = 0;
        numIndirectDrawCalls = 0;
        numIndirectObjects = 0;
        numTriangles = 0;
        numVertices = 0;
        numShaderSwitches = 0;
        numTextureBinds = 0;
        numFboSwitches = 0;
        numUniformsSet = 0;

        lastShader = -1;
    }

    public void onNewShader() { if (enabled) memoryShaders++; }
    public void onNewTexture() { if (enabled) memoryTextures++; }
    public void onNewFrameBuffer() { if (enabled) memoryFrameBuffers++; }
    public void onDeleteShader() { if (enabled) memoryShaders--; }
    public void onDeleteTexture() { if (enabled) memoryTextures--; }
    public void onDeleteFrameBuffer() { if (enabled) memoryFrameBuffers--; }

    public void clearMemory() {
        memoryFrameBuffers = 0;
        memoryShaders = 0;
        memoryTextures = 0;
    }

    public void setEnabled(boolean f) { this.enabled = f; }
    public boolean isEnabled() { return enabled; }
}
