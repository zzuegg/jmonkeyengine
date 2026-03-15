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
import com.jme3.math.Matrix4f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
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
 * Renders a 3x3 grid combining multi-draw indirect with bindless textures and
 * dynamic per-draw transforms via SSBO.
 * <p>
 * Three mesh types (box, monkey head, sphere) are combined via {@link MeshCombiner}
 * into a single vertex/index buffer in local space (no transform baking). Per-draw
 * world matrices are uploaded each frame to a transform SSBO, and the vertex shader
 * reads {@code worldMatrices[gl_DrawID]}. Each object spins around a different axis.
 * <p>
 * A second SSBO holds bindless texture handles indexed by {@code gl_DrawID}.
 * <p>
 * All 9 objects are rendered in a single {@code renderMeshMultiIndirect} call.
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

    private static final int DRAW_COUNT = 9;
    private static final float SPACING_X = 3.5f;
    private static final float SPACING_Y = 3.0f;

    @Test
    public void testMdiBindlessGrid() {
        screenshotTest(new BaseAppState() {

            // Per-draw rotation axes — each object gets a unique axis
            private final Vector3f[] rotationAxes = {
                Vector3f.UNIT_Y,
                new Vector3f(1, 1, 0).normalizeLocal(),
                Vector3f.UNIT_X,
                new Vector3f(0, 1, 1).normalizeLocal(),
                Vector3f.UNIT_Z,
                new Vector3f(1, 0, 1).normalizeLocal(),
                new Vector3f(1, 1, 1).normalizeLocal(),
                new Vector3f(-1, 1, 0).normalizeLocal(),
                new Vector3f(0, -1, 1).normalizeLocal(),
            };

            // Per-draw rotation speeds (radians/sec)
            private final float[] rotationSpeeds = {
                2.0f, 1.5f, 3.0f, 2.5f, 1.8f, 2.2f, 1.3f, 2.8f, 1.7f
            };

            private float[] angles;
            private Vector3f[] positions;
            private BufferObject transformSsbo;
            private final Matrix4f tempMat = new Matrix4f();
            private final Quaternion tempQuat = new Quaternion();

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

                // Camera
                app.getCamera().setLocation(new Vector3f(0, 0, 14));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                // Load meshes
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

                // Combine meshes WITHOUT transforms (local space)
                // Grid layout: columns = box, monkey, sphere; rows = textures
                Mesh[] meshTypes = { boxMesh, monkeyMesh, sphereMesh };
                Mesh[] meshesForCombine = new Mesh[DRAW_COUNT];
                int[] textureIndices = new int[DRAW_COUNT];
                positions = new Vector3f[DRAW_COUNT];
                angles = new float[DRAW_COUNT];

                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        int idx = row * 3 + col;
                        meshesForCombine[idx] = meshTypes[col];
                        textureIndices[idx] = row;
                        positions[idx] = new Vector3f(
                                (col - 1) * SPACING_X,
                                (1 - row) * SPACING_Y,
                                0);
                    }
                }

                MeshCombiner.CombinedMesh combined = MeshCombiner.combine(meshesForCombine);
                Mesh combinedMesh = combined.getMesh();

                // Indirect command buffer
                IndirectCommandBuffer cmdBuf = combined.toCommandBuffer();
                cmdBuf.update();

                // Load textures and get bindless handles
                Texture2D[] textures = new Texture2D[TEXTURE_PATHS.length];
                try {
                    for (int i = 0; i < TEXTURE_PATHS.length; i++) {
                        Texture loaded = app.getAssetManager()
                                .loadTexture(TEXTURE_PATHS[i]);
                        textures[i] = (Texture2D) loaded;
                        renderer.setTexture(i, textures[i]);
                    }
                } catch (com.jme3.renderer.TextureUnitException e) {
                    throw new RuntimeException("Failed to upload textures", e);
                }

                // Texture handles SSBO (static — set once)
                BufferObject texHandlesSsbo = new BufferObject();
                texHandlesSsbo.initializeEmpty(DRAW_COUNT * 8);
                ByteBuffer texBuf = texHandlesSsbo.getData();
                for (int i = 0; i < DRAW_COUNT; i++) {
                    texBuf.putLong(textures[textureIndices[i]].getImage()
                            .getBindlessHandle());
                }
                texBuf.rewind();

                // Transform SSBO (dynamic — updated each frame)
                // 64 bytes per mat4 × 9 draws
                transformSsbo = new BufferObject();
                transformSsbo.setAccessHint(BufferObject.AccessHint.Dynamic);
                transformSsbo.setNatureHint(BufferObject.NatureHint.Draw);
                transformSsbo.initializeEmpty(DRAW_COUNT * 64);

                // Material
                Material mat = new Material(app.getAssetManager(),
                        "ScreenshotTests/MatDefs/MdiBindless.j3md");
                mat.setShaderStorageBufferObject("TextureHandles", texHandlesSsbo);
                mat.setShaderStorageBufferObject("DrawTransforms", transformSsbo);

                // Geometry for GL state setup
                Geometry geom = new Geometry("MdiBindlessGrid", combinedMesh);
                geom.setMaterial(mat);
                simpleApp.getRootNode().attachChild(geom);

                // Write initial transforms
                updateTransforms(0);

                // SceneProcessor for MDI rendering
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
                        rm.getRenderer().clearBuffers(true, true, true);
                        rm.renderGeometryIndirect(geom, cmdBuf);
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
            public void update(float tpf) {
                if (transformSsbo == null) return;
                // Advance rotation angles
                for (int i = 0; i < DRAW_COUNT; i++) {
                    angles[i] += tpf * rotationSpeeds[i];
                }
                updateTransforms(0);
            }

            private void updateTransforms(float tpf) {
                ByteBuffer buf = transformSsbo.getData();
                buf.rewind();
                for (int i = 0; i < DRAW_COUNT; i++) {
                    tempQuat.fromAngleAxis(angles[i], rotationAxes[i]);
                    tempMat.loadIdentity();
                    tempMat.setTranslation(positions[i]);
                    tempMat.setRotationQuaternion(tempQuat);
                    MeshCombiner.writeMat4(buf, tempMat);
                }
                buf.rewind();
                transformSsbo.setUpdateNeeded();
            }

            @Override
            protected void cleanup(Application app) {}

            @Override
            protected void onEnable() {}

            @Override
            protected void onDisable() {}
        })
        .setFramesToTakeScreenshotsOn(10)
        .setTestType(TestType.NON_DETERMINISTIC)
        .run();
    }
}
