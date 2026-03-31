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
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.indirect.MdiNode;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Tests that MdiNode works with the STANDARD material definitions
 * (Unshaded.j3md and PBRLighting.j3md) — not special MDI-only matdefs.
 * <p>
 * The existing matdefs now include a DrawData block and an "Mdi" technique.
 * The rendering pipeline auto-selects the Mdi technique for MdiGeometry.
 */
public class TestMdiNodeWithStandardMaterials extends ScreenshotTestBase {

    @Test
    public void testUnshadedMdi() {
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

                MdiNode mdiNode = new MdiNode("unshadedMdi");
                simpleApp.getRootNode().attachChild(mdiNode);

                // Standard Unshaded.j3md — not UnshadedMdi.j3md!
                Material mat1 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/Unshaded.j3md");
                mat1.setColor("Color", ColorRGBA.Red);

                Geometry box = new Geometry("box", new Box(0.8f, 0.8f, 0.8f));
                box.setMaterial(mat1);
                box.setLocalTranslation(-2, 0, 0);
                mdiNode.attachChild(box);

                Material mat2 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/Unshaded.j3md");
                mat2.setColor("Color", ColorRGBA.Green);

                Geometry sphere = new Geometry("sphere", new Sphere(16, 16, 1f));
                sphere.setMaterial(mat2);
                sphere.setLocalTranslation(2, 0, 0);
                mdiNode.attachChild(sphere);

                mdiNode.batch();
            }

            @Override protected void cleanup(Application app) {}
            @Override protected void onEnable() {}
            @Override protected void onDisable() {}
        })
        .setFramesToTakeScreenshotsOn(3)
        .run();
    }

    @Test
    public void testPBRLightingMdi() {
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

                DirectionalLight sun = new DirectionalLight();
                sun.setDirection(new Vector3f(-1, -2, -3).normalizeLocal());
                sun.setColor(ColorRGBA.White.mult(1.5f));
                simpleApp.getRootNode().addLight(sun);

                AmbientLight ambient = new AmbientLight();
                ambient.setColor(new ColorRGBA(0.2f, 0.2f, 0.2f, 1.0f));
                simpleApp.getRootNode().addLight(ambient);

                MdiNode mdiNode = new MdiNode("pbrMdi");
                simpleApp.getRootNode().attachChild(mdiNode);

                // Standard PBR Lighting.j3md — not PBRLightingMdi.j3md!
                Material mat1 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                mat1.setColor("BaseColor", new ColorRGBA(0.9f, 0.1f, 0.1f, 1.0f));
                mat1.setFloat("Metallic", 1.0f);
                mat1.setFloat("Roughness", 0.1f);

                Geometry sphere = new Geometry("metalSphere",
                        new Sphere(32, 32, 1.2f));
                sphere.setMaterial(mat1);
                sphere.setLocalTranslation(-3, 0, 0);
                mdiNode.attachChild(sphere);

                Material mat2 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                mat2.setColor("BaseColor", new ColorRGBA(1.0f, 0.76f, 0.33f, 1.0f));
                mat2.setFloat("Metallic", 1.0f);
                mat2.setFloat("Roughness", 0.3f);

                Spatial monkeyModel = app.getAssetManager()
                        .loadModel("Models/MonkeyHead/MonkeyHead.mesh.xml");
                Mesh monkeyMesh;
                if (monkeyModel instanceof Geometry) {
                    monkeyMesh = ((Geometry) monkeyModel).getMesh();
                } else {
                    monkeyMesh = ((Geometry) ((com.jme3.scene.Node) monkeyModel)
                            .getChild(0)).getMesh();
                }

                Geometry monkey = new Geometry("goldMonkey", monkeyMesh);
                monkey.setMaterial(mat2);
                monkey.setLocalTranslation(3, 0, 0);
                mdiNode.attachChild(monkey);

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
