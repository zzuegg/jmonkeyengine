/*
 * Copyright (c) 2026 jMonkeyEngine
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
package org.jmonkeyengine.screenshottests.renderer.indirect;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.indirect.DrawElementsIndirectCommand;
import com.jme3.renderer.indirect.IndirectCommandBuffer;
import com.jme3.renderer.indirect.MeshCombiner;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.jmonkeyengine.screenshottests.testframework.TestType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.EnumSet;

/**
 * Renders a 3x3 grid combining multi-draw indirect with bindless textures.
 * <p>
 * Three mesh types (box, monkey head, sphere) are combined via {@link MeshCombiner}
 * into a single vertex/index buffer. Each mesh appears three times at different
 * grid positions, each with a different texture. A custom shader uses
 * {@code gl_DrawID} to index into an SSBO of bindless texture handles.
 * <p>
 * All 9 objects are rendered in a single {@code renderMeshMultiIndirect} call
 * with 9 draw commands.
 * <p>
 * Requires: {@link Caps#MultiDrawIndirect}, {@link Caps#BindlessTexture},
 * {@link Caps#ShaderStorageBufferObject}, OpenGL 4.6 (for {@code gl_DrawID}).
 */
public class TestMdiBindlessTextures extends ScreenshotTestBase {

    private static final String[] TEXTURE_PATHS = {
        "Textures/ColoredTex/Monkey.png",
        "Textures/Terrain/BrickWall/BrickWall.jpg",
        "Textures/Terrain/splat/grass.jpg",
    };

    @Test
    public void testMdiBindlessGrid() {
        screenshotTest(new BaseAppState() {

            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;
                Renderer renderer = app.getRenderer();
                EnumSet<Caps> caps = renderer.getCaps();

                if (!caps.contains(Caps.MultiDrawIndirect)
                        || !caps.contains(Caps.BindlessTexture)
                        || !caps.contains(Caps.ShaderStorageBufferObject)) {
                    return;
                }

                renderer.setBindlessTextureEnabled(true);

                // Camera setup to see the full 3x3 grid
                app.getCamera().setLocation(new Vector3f(0, 0, 14));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                // Load the three mesh types
                Box boxMesh = new Box(0.8f, 0.8f, 0.8f);
                Sphere sphereMesh = new Sphere(16, 16, 0.9f);
                Spatial monkeyModel = app.getAssetManager()
                        .loadModel("Models/MonkeyHead/MonkeyHead.mesh.xml");
                Mesh monkeyMesh;
                if (monkeyModel instanceof Geometry) {
                    monkeyMesh = ((Geometry) monkeyModel).getMesh();
                } else {
                    monkeyMesh = ((Geometry) ((com.jme3.scene.Node) monkeyModel)
                            .getChild(0)).getMesh();
                }

                // Load and upload textures to get bindless handles
                Texture2D[] textures = new Texture2D[TEXTURE_PATHS.length];
                try {
                    for (int i = 0; i < TEXTURE_PATHS.length; i++) {
                        Texture loaded = app.getAssetManager().loadTexture(TEXTURE_PATHS[i]);
                        textures[i] = (Texture2D) loaded;
                        renderer.setTexture(i, textures[i]);
                    }
                } catch (com.jme3.renderer.TextureUnitException e) {
                    throw new RuntimeException("Failed to upload textures", e);
                }

                // 3x3 grid layout:
                // Rows = texture (0,1,2 from top to bottom)
                // Columns = mesh type (box, monkey, sphere)
                Mesh[] meshTypes = { boxMesh, monkeyMesh, sphereMesh };
                float spacingX = 3.5f;
                float spacingY = 3.0f;

                MeshCombiner.Entry[] entries = new MeshCombiner.Entry[9];
                int[] textureIndices = new int[9]; // maps drawID -> texture index

                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        int idx = row * 3 + col;
                        float x = (col - 1) * spacingX;
                        float y = (1 - row) * spacingY;
                        entries[idx] = new MeshCombiner.Entry(
                                meshTypes[col],
                                new Transform(new Vector3f(x, y, 0)));
                        textureIndices[idx] = row; // each row gets a different texture
                    }
                }

                MeshCombiner.CombinedMesh combined = MeshCombiner.combine(entries);
                Mesh combinedMesh = combined.getMesh();

                // Build indirect command buffer — 9 draw commands
                IndirectCommandBuffer cmdBuf = new IndirectCommandBuffer(
                        IndirectCommandBuffer.DrawType.Elements);
                for (MeshCombiner.SubMeshInfo info : combined.getSubMeshes()) {
                    cmdBuf.addCommand(info.toCommand());
                }
                cmdBuf.update();

                // Build SSBO with texture handles — one per draw command
                // Layout: drawID -> sampler2D handle (8 bytes each, std430)
                BufferObject ssbo = new BufferObject();
                ssbo.initializeEmpty(9 * 8);
                ByteBuffer buf = ssbo.getData();
                for (int i = 0; i < 9; i++) {
                    buf.putLong(textures[textureIndices[i]].getImage().getBindlessHandle());
                }
                buf.rewind();

                // Material with custom MDI + bindless shader
                Material mat = new Material(app.getAssetManager(),
                        "ScreenshotTests/MatDefs/MdiBindless.j3md");
                mat.setShaderStorageBufferObject("TextureHandles", ssbo);

                // Geometry for GL state setup
                Geometry geom = new Geometry("MdiBindlessGrid", combinedMesh);
                geom.setMaterial(mat);
                simpleApp.getRootNode().attachChild(geom);

                // SceneProcessor to intercept and render via MDI
                final IndirectCommandBuffer finalCmdBuf = cmdBuf;

                simpleApp.getViewPort().addProcessor(new SceneProcessor() {
                    private boolean initialized = false;
                    private RenderManager rm;

                    @Override
                    public void initialize(RenderManager rm, ViewPort vp) {
                        this.rm = rm;
                        this.initialized = true;
                    }

                    @Override
                    public void reshape(ViewPort vp, int w, int h) {}

                    @Override
                    public boolean isInitialized() { return initialized; }

                    @Override
                    public void preFrame(float tpf) {}

                    @Override
                    public void postQueue(RenderQueue rq) {
                        Renderer r = rm.getRenderer();

                        // Establish GL state
                        rm.renderGeometry(geom);

                        // Clear — only the MDI call will be visible
                        r.clearBuffers(true, true, true);

                        // 9 objects, 1 draw call
                        r.renderMeshMultiIndirect(
                                combinedMesh,
                                finalCmdBuf.getBufferObject(),
                                finalCmdBuf.getCommandCount(),
                                0);

                        rq.clear();
                    }

                    @Override
                    public void postFrame(FrameBuffer out) {}

                    @Override
                    public void cleanup() {}

                    @Override
                    public void setProfiler(AppProfiler profiler) {}
                });
            }

            @Override
            protected void cleanup(Application app) {}

            @Override
            protected void onEnable() {}

            @Override
            protected void onDisable() {}
        })
        .setFramesToTakeScreenshotsOn(2)
        .setTestType(TestType.NON_DETERMINISTIC)
        .run();
    }
}
