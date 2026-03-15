/*
 * Copyright (c) 2024-2026 jMonkeyEngine
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
package org.jmonkeyengine.screenshottests.compute;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.opengl.GL4;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureImage;
import com.jme3.texture.image.ColorSpace;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * Screenshot tests for the integrated compute shader pipeline via Material.dispatch().
 * <p>
 * Compute dispatch must happen during the render phase (GL context is active),
 * so we use a {@link SceneProcessor} to run dispatches in {@code postQueue()}.
 */
public class TestComputeShader extends ScreenshotTestBase {

    /**
     * Basic compute dispatch test: dispatches a checkerboard pattern shader
     * via Material.dispatch(), renders the result on a fullscreen quad.
     */
    @Test
    public void testComputeCheckerboard() {
        screenshotTest(new ComputeTestState(TestMode.CHECKERBOARD))
                .setFramesToTakeScreenshotsOn(10)
                .run();
    }

    /**
     * Multi-technique test: same material, two techniques (FillPattern + FillGradient),
     * displayed side by side. Verifies technique switching in compute materials.
     */
    @Test
    public void testComputeMultiTechnique() {
        screenshotTest(new ComputeTestState(TestMode.MULTI_TECHNIQUE))
                .setFramesToTakeScreenshotsOn(10)
                .run();
    }



    private enum TestMode {
        CHECKERBOARD, MULTI_TECHNIQUE
    }

    private static class ComputeTestState extends BaseAppState {
        private final TestMode mode;
        private AssetManager assetManager;
        private Node rootNode;

        ComputeTestState(TestMode mode) {
            this.mode = mode;
        }

        @Override
        public void initialize(Application app) {
            assetManager = app.getAssetManager();
            // Use guiNode for simple 2D rendering (always has orthographic camera)
            rootNode = ((SimpleApplication) app).getGuiNode();

            // Add a scene processor to dispatch compute during the render phase
            // Use guiViewPort so it runs in the GUI pass
            app.getGuiViewPort().addProcessor(new ComputeDispatcher());
        }

        private class ComputeDispatcher implements SceneProcessor {
            private RenderManager renderManager;
            private boolean dispatched = false;

            @Override
            public void initialize(RenderManager rm, ViewPort vp) {
                this.renderManager = rm;
            }

            @Override
            public void reshape(ViewPort vp, int w, int h) {}

            @Override
            public boolean isInitialized() {
                return renderManager != null;
            }

            @Override
            public void preFrame(float tpf) {}

            @Override
            public void postQueue(RenderQueue rq) {
                if (dispatched) return;
                dispatched = true;

                Renderer renderer = renderManager.getRenderer();
                int texSize = 256;

                switch (mode) {
                    case CHECKERBOARD:
                        doCheckerboard(renderer, renderManager, texSize);
                        break;
                    case MULTI_TECHNIQUE:
                        doMultiTechnique(renderer, renderManager, texSize);
                        break;
                }
            }

            @Override
            public void postFrame(FrameBuffer out) {}

            @Override
            public void cleanup() {}

            @Override
            public void setProfiler(AppProfiler profiler) {}
        }

        // --- Test implementations ---

        private void doCheckerboard(Renderer renderer, RenderManager renderManager, int texSize) {
            Texture2D tex = createEmptyTexture(texSize);

            Material computeMat = new Material(assetManager, "Common/MatDefs/Compute/TestCompute.j3md");
            computeMat.setInt("Width", texSize);
            computeMat.setInt("Height", texSize);
            computeMat.setFloat("Time", 0f);
            computeMat.setImage("OutputImage", tex, TextureImage.Access.WriteOnly);
            computeMat.dispatch("FillPattern", renderManager,
                    divRoundUp(texSize, 16), divRoundUp(texSize, 16), 1);
            renderer.memoryBarrier(GL4.GL_ALL_BARRIER_BITS);

            attachQuad(tex, 0, 0, 500, 400);
        }

        private void doMultiTechnique(Renderer renderer, RenderManager renderManager, int texSize) {
            Texture2D texPattern = createEmptyTexture(texSize);
            Texture2D texGradient = createEmptyTexture(texSize);

            Material computeMat = new Material(assetManager, "Common/MatDefs/Compute/TestCompute.j3md");
            computeMat.setInt("Width", texSize);
            computeMat.setInt("Height", texSize);
            computeMat.setFloat("Time", 0f);

            // Dispatch pattern
            computeMat.setImage("OutputImage", texPattern, TextureImage.Access.WriteOnly);
            computeMat.dispatch("FillPattern", renderManager,
                    divRoundUp(texSize, 16), divRoundUp(texSize, 16), 1);
            renderer.memoryBarrier(GL4.GL_ALL_BARRIER_BITS);

            // Dispatch gradient
            computeMat.setImage("OutputImage", texGradient, TextureImage.Access.WriteOnly);
            computeMat.dispatch("FillGradient", renderManager,
                    divRoundUp(texSize, 16), divRoundUp(texSize, 16), 1);
            renderer.memoryBarrier(GL4.GL_ALL_BARRIER_BITS);

            attachQuad(texPattern, 0, 0, 250, 400);
            attachQuad(texGradient, 250, 0, 250, 400);
        }

        private void attachQuad(Texture2D tex, float x, float y, float w, float h) {
            Quad quad = new Quad(w, h);
            Geometry geom = new Geometry("Quad", quad);
            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setTexture("ColorMap", tex);
            geom.setMaterial(mat);
            geom.setLocalTranslation(x, y, 0);
            rootNode.attachChild(geom);
        }

        // --- BaseAppState ---

        @Override protected void cleanup(Application app) {}
        @Override protected void onEnable() {}
        @Override protected void onDisable() {}
    }

    private static Texture2D createEmptyTexture(int size) {
        // Create image with red data to verify the quad is visible even without compute
        ByteBuffer data = ByteBuffer.allocateDirect(size * size * 4);
        for (int i = 0; i < size * size; i++) {
            data.put((byte) 255); // R
            data.put((byte) 0);   // G
            data.put((byte) 0);   // B
            data.put((byte) 255); // A
        }
        data.flip();
        Image img = new Image(Image.Format.RGBA8, size, size, data, null, ColorSpace.Linear);
        Texture2D tex = new Texture2D(img);
        tex.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        tex.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        return tex;
    }

    private static int divRoundUp(int a, int b) {
        return (a + b - 1) / b;
    }
}
