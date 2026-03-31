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
import com.jme3.renderer.Caps;
import com.jme3.scene.Geometry;
import com.jme3.scene.indirect.MdiNode;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Tests MdiNode automatic batching: attaches geometries with different meshes
 * and colors under an MdiNode, calls batch(), and verifies they render correctly
 * in a single MDI call via the normal scene graph pipeline (no SceneProcessor needed).
 * <p>
 * Requires: {@link Caps#MultiDrawIndirect}, {@link Caps#ShaderStorageBufferObject}.
 */
public class TestMdiNode extends ScreenshotTestBase {

    @Test
    public void testMdiNodeBatching() {
        screenshotTest(new BaseAppState() {
            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;
                EnumSet<Caps> caps = app.getRenderer().getCaps();

                if (!caps.contains(Caps.MultiDrawIndirect)
                        || !caps.contains(Caps.ShaderStorageBufferObject)) {
                    return;
                }

                app.getCamera().setLocation(new Vector3f(0, 0, 8));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                MdiNode mdiNode = new MdiNode("testMdi");
                simpleApp.getRootNode().attachChild(mdiNode);

                // Red box on the left
                Material mat1 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/UnshadedMdi.j3md");
                mat1.setColor("Color", ColorRGBA.Red);

                Geometry box = new Geometry("box", new Box(0.8f, 0.8f, 0.8f));
                box.setMaterial(mat1);
                box.setLocalTranslation(-2, 0, 0);
                mdiNode.attachChild(box);

                // Green sphere on the right
                Material mat2 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/UnshadedMdi.j3md");
                mat2.setColor("Color", ColorRGBA.Green);

                Geometry sphere = new Geometry("sphere", new Sphere(16, 16, 1f));
                sphere.setMaterial(mat2);
                sphere.setLocalTranslation(2, 0, 0);
                mdiNode.attachChild(sphere);

                // Blue box in the center
                Material mat3 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/UnshadedMdi.j3md");
                mat3.setColor("Color", ColorRGBA.Blue);

                Geometry box2 = new Geometry("box2", new Box(0.5f, 0.5f, 0.5f));
                box2.setMaterial(mat3);
                box2.setLocalTranslation(0, 0, 0);
                mdiNode.attachChild(box2);

                // Batch — combines all three into one MDI call
                mdiNode.batch();
            }

            @Override protected void cleanup(Application app) {}
            @Override protected void onEnable() {}
            @Override protected void onDisable() {}
        })
        .setFramesToTakeScreenshotsOn(3)
        .run();
    }
}
