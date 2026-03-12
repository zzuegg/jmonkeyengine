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
import com.jme3.math.Transform;
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
import com.jme3.texture.FrameBuffer;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

/**
 * End-to-end rendering test for multi-draw indirect commands.
 * <p>
 * Combines a box, sphere, and monkey head into a single mesh using
 * {@link MeshCombiner}, then renders all three objects in one
 * {@code renderMeshMultiIndirect} call with 3 draw commands.
 * <p>
 * Uses a {@link SceneProcessor} to intercept rendering: establishes GL state
 * via {@code renderGeometry}, clears the framebuffer, then issues the multi-draw
 * indirect call as the only visible output.
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
                        return;
                    }

                    // Camera setup — pulled back to see all three objects
                    app.getCamera().setLocation(new Vector3f(0, 2, 10));
                    app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                    // Create three different meshes
                    Box boxMesh = new Box(0.8f, 0.8f, 0.8f);
                    Sphere sphereMesh = new Sphere(16, 16, 1f);
                    Spatial monkeyModel = app.getAssetManager()
                            .loadModel("Models/MonkeyHead/MonkeyHead.mesh.xml");
                    // MonkeyHead loads as a Node — find the first Geometry child
                    Mesh monkeyMesh;
                    if (monkeyModel instanceof Geometry) {
                        monkeyMesh = ((Geometry) monkeyModel).getMesh();
                    } else {
                        monkeyMesh = ((Geometry) ((com.jme3.scene.Node) monkeyModel)
                                .getChild(0)).getMesh();
                    }

                    // Combine all three into one mesh with transforms for positioning
                    Transform boxTransform = new Transform(new Vector3f(-3, 0, 0));
                    Transform sphereTransform = new Transform(new Vector3f(3, 0, 0));
                    Transform monkeyTransform = new Transform(new Vector3f(0, 0, 0));

                    MeshCombiner.CombinedMesh combined = MeshCombiner.combine(
                            new MeshCombiner.Entry(boxMesh, boxTransform),
                            new MeshCombiner.Entry(sphereMesh, sphereTransform),
                            new MeshCombiner.Entry(monkeyMesh, monkeyTransform)
                    );

                    Mesh combinedMesh = combined.getMesh();

                    // Build indirect command buffer — one draw command per sub-mesh
                    IndirectCommandBuffer cmdBuf = combined.toCommandBuffer();
                    cmdBuf.update();

                    // Material for state setup
                    Material mat = new Material(app.getAssetManager(),
                            "Common/MatDefs/Misc/Unshaded.j3md");
                    mat.setColor("Color", ColorRGBA.Blue);

                    // Geometry for GL state setup — uses the combined mesh
                    Geometry geom = new Geometry("CombinedIndirect", combinedMesh);
                    geom.setMaterial(mat);
                    simpleApp.getRootNode().attachChild(geom);

                    // Capture references for the processor
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
                            Renderer renderer = rm.getRenderer();

                            // Establish GL state (shader, uniforms, render state)
                            rm.renderGeometry(geom);

                            // Clear framebuffer — only the indirect draw will be visible
                            renderer.clearBuffers(true, true, true);

                            // Issue multi-draw indirect: 3 objects in 1 call
                            renderer.renderMeshMultiIndirect(
                                    combinedMesh,
                                    finalCmdBuf.getBufferObject(),
                                    finalCmdBuf.getCommandCount(),
                                    0);

                            // Clear queue so flushQueue renders nothing
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
            }
        )
        .setFramesToTakeScreenshotsOn(2)
        .run();
    }
}
