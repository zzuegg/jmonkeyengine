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
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
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
 * Tests mixing Unshaded.j3md and PBRLighting.j3md geometries in the same
 * MdiNode. They should end up in separate MDI batches (different DrawData
 * layouts) but both render correctly.
 */
public class TestMdiNodeMixedMaterials extends ScreenshotTestBase {

    @Test
    public void testMixedUnshadedAndPBR() {
        screenshotTest(new BaseAppState() {
            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;
                EnumSet<Caps> caps = app.getRenderer().getCaps();

                if (!caps.contains(Caps.MultiDrawIndirect)
                        || !caps.contains(Caps.ShaderStorageBufferObject)) {
                    return;
                }

                app.getCamera().setLocation(new Vector3f(0, 0, 10));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                // Lights for PBR objects
                DirectionalLight sun = new DirectionalLight();
                sun.setDirection(new Vector3f(-1, -2, -3).normalizeLocal());
                sun.setColor(ColorRGBA.White.mult(1.5f));
                simpleApp.getRootNode().addLight(sun);

                AmbientLight ambient = new AmbientLight();
                ambient.setColor(new ColorRGBA(0.2f, 0.2f, 0.2f, 1.0f));
                simpleApp.getRootNode().addLight(ambient);

                MdiNode mdiNode = new MdiNode("mixedMdi");
                simpleApp.getRootNode().attachChild(mdiNode);

                // Unshaded red box (left)
                Material unshadedMat = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/Unshaded.j3md");
                unshadedMat.setColor("Color", ColorRGBA.Red);

                Geometry unshadedBox = new Geometry("unshadedBox",
                        new Box(0.8f, 0.8f, 0.8f));
                unshadedBox.setMaterial(unshadedMat);
                unshadedBox.setLocalTranslation(-3, 0, 0);
                mdiNode.attachChild(unshadedBox);

                // Unshaded yellow sphere (far left)
                Material unshadedMat2 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/Unshaded.j3md");
                unshadedMat2.setColor("Color", ColorRGBA.Yellow);

                Geometry unshadedSphere = new Geometry("unshadedSphere",
                        new Sphere(16, 16, 0.7f));
                unshadedSphere.setMaterial(unshadedMat2);
                unshadedSphere.setLocalTranslation(-1, 0, 0);
                mdiNode.attachChild(unshadedSphere);

                // PBR gold metallic sphere (right)
                Material pbrMat1 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                pbrMat1.setColor("BaseColor", new ColorRGBA(1.0f, 0.76f, 0.33f, 1.0f));
                pbrMat1.setFloat("Metallic", 1.0f);
                pbrMat1.setFloat("Roughness", 0.2f);

                Geometry pbrSphere = new Geometry("pbrSphere",
                        new Sphere(32, 32, 1.0f));
                pbrSphere.setMaterial(pbrMat1);
                pbrSphere.setLocalTranslation(1.5f, 0, 0);
                mdiNode.attachChild(pbrSphere);

                // PBR green rough box (far right)
                Material pbrMat2 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                pbrMat2.setColor("BaseColor", new ColorRGBA(0.1f, 0.8f, 0.1f, 1.0f));
                pbrMat2.setFloat("Metallic", 0.0f);
                pbrMat2.setFloat("Roughness", 0.8f);

                Geometry pbrBox = new Geometry("pbrBox",
                        new Box(0.7f, 0.7f, 0.7f));
                pbrBox.setMaterial(pbrMat2);
                pbrBox.setLocalTranslation(3.5f, 0, 0);
                mdiNode.attachChild(pbrBox);

                // Batch all 4 — should create 2 separate MDI batches
                // (one for Unshaded, one for PBR)
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
