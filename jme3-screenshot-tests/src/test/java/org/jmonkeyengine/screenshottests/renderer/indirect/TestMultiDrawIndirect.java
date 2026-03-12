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
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.indirect.DrawElementsIndirectCommand;
import com.jme3.renderer.indirect.IndirectCommandBuffer;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.shape.Box;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.FrameBuffer;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

/**
 * End-to-end rendering test for indirect draw commands.
 * <p>
 * Uses a {@link SceneProcessor} to intercept rendering after the queue is built
 * but before it is flushed. The processor uses {@code renderGeometry} to set up
 * all GL state (shader, uniforms, vertex attribs), then clears the framebuffer
 * to remove the normal render output, and finally calls
 * {@code renderer.renderMeshIndirect()} to issue the draw via the indirect
 * command buffer. The render queue is then cleared so {@code flushQueue} has
 * nothing to render.
 * <p>
 * If indirect drawing works correctly, the box drawn by the indirect call is
 * visible and the screenshot matches the reference image. If it is broken, the
 * screenshot is empty and the test fails.
 */
public class TestMultiDrawIndirect extends ScreenshotTestBase {

    @Test
    public void testMultiDrawIndirect() {
        screenshotTest(
            new BaseAppState() {
                @Override
                protected void initialize(Application app) {
                    SimpleApplication simpleApp = (SimpleApplication) app;

                    if (!app.getRenderer().getCaps().contains(Caps.MultiDrawIndirect)) {
                        // Skip gracefully on hardware without MDI support
                        return;
                    }

                    // Camera setup
                    app.getCamera().setLocation(new Vector3f(0, 3, 8));
                    app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                    // Mesh and material
                    Box box = new Box(1, 1, 1);
                    Material mat = new Material(app.getAssetManager(),
                            "Common/MatDefs/Misc/Unshaded.j3md");
                    mat.setColor("Color", ColorRGBA.Blue);

                    // Geometry for state setup — must be in the scene graph
                    // so updateGeometricState populates world transforms
                    Geometry geom = new Geometry("IndirectBox", box);
                    geom.setMaterial(mat);
                    simpleApp.getRootNode().attachChild(geom);

                    // Build indirect command buffer: one draw command for
                    // the box's 36 indices, 1 instance
                    IndirectCommandBuffer cmdBuf = new IndirectCommandBuffer(
                            IndirectCommandBuffer.DrawType.Elements);
                    cmdBuf.addCommand(new DrawElementsIndirectCommand(36, 1, 0, 0, 0));
                    cmdBuf.update();

                    BufferObject commandBuffer = cmdBuf.getBufferObject();
                    Mesh mesh = box;

                    // Add a SceneProcessor that intercepts rendering
                    simpleApp.getViewPort().addProcessor(new SceneProcessor() {
                        private boolean initialized = false;
                        private RenderManager rm;

                        @Override
                        public void initialize(RenderManager rm, ViewPort vp) {
                            this.rm = rm;
                            this.initialized = true;
                        }

                        @Override
                        public void reshape(ViewPort vp, int w, int h) {
                        }

                        @Override
                        public boolean isInitialized() {
                            return initialized;
                        }

                        @Override
                        public void preFrame(float tpf) {
                        }

                        @Override
                        public void postQueue(RenderQueue rq) {
                            Renderer renderer = rm.getRenderer();

                            // Step 1: Use renderGeometry to establish all GL
                            // state: shader program, uniforms, render state.
                            // This renders the geometry normally as a side
                            // effect.
                            rm.renderGeometry(geom);

                            // Step 2: Clear the framebuffer to remove the
                            // normal render output. Only the indirect draw
                            // that follows will be visible.
                            renderer.clearBuffers(true, true, true);

                            // Step 3: Issue the indirect draw call. This
                            // re-sets vertex attribs via setupMeshVertexAttribs
                            // internally, binds the indirect buffer, and
                            // issues the GL draw.
                            renderer.renderMeshIndirect(mesh, commandBuffer, 0);

                            // Step 4: Clear the render queue so flushQueue
                            // has nothing to render — only our indirect
                            // draw output remains.
                            rq.clear();
                        }

                        @Override
                        public void postFrame(FrameBuffer out) {
                        }

                        @Override
                        public void cleanup() {
                        }

                        @Override
                        public void setProfiler(AppProfiler profiler) {
                        }
                    });
                }

                @Override
                protected void cleanup(Application app) {
                }

                @Override
                protected void onEnable() {
                }

                @Override
                protected void onDisable() {
                }
            }
        )
        .setFramesToTakeScreenshotsOn(2)
        .run();
    }
}
