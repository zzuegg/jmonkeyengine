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
 * Tests MdiNode with PBRLightingMdi material: batches geometries with different
 * meshes, colors, and metallic/roughness values under an MdiNode with PBR
 * lighting. Verifies that per-draw material params work through the SSBO and
 * lighting is applied correctly.
 * <p>
 * Requires: {@link Caps#MultiDrawIndirect}, {@link Caps#ShaderStorageBufferObject}.
 */
public class TestPBRLightingMdiNode extends ScreenshotTestBase {

    @Test
    public void testPBRMdiNodeBatching() {
        screenshotTest(new BaseAppState() {
            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;
                EnumSet<Caps> caps = app.getRenderer().getCaps();

                if (!caps.contains(Caps.MultiDrawIndirect)
                        || !caps.contains(Caps.ShaderStorageBufferObject)) {
                    return;
                }

                app.getCamera().setLocation(new Vector3f(0, 2, 10));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                // Add lights
                DirectionalLight sun = new DirectionalLight();
                sun.setDirection(new Vector3f(-1, -2, -3).normalizeLocal());
                sun.setColor(ColorRGBA.White.mult(1.5f));
                simpleApp.getRootNode().addLight(sun);

                AmbientLight ambient = new AmbientLight();
                ambient.setColor(new ColorRGBA(0.2f, 0.2f, 0.2f, 1.0f));
                simpleApp.getRootNode().addLight(ambient);

                MdiNode mdiNode = new MdiNode("pbrMdi");
                simpleApp.getRootNode().attachChild(mdiNode);

                // Shiny red metallic sphere (left)
                Material mat1 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                mat1.setColor("BaseColor", new ColorRGBA(0.9f, 0.1f, 0.1f, 1.0f));
                mat1.setFloat("Metallic", 1.0f);
                mat1.setFloat("Roughness", 0.1f);

                Geometry sphere1 = new Geometry("metalSphere",
                        new Sphere(32, 32, 1.2f));
                sphere1.setMaterial(mat1);
                sphere1.setLocalTranslation(-3, 0, 0);
                mdiNode.attachChild(sphere1);

                // Rough green dielectric box (center)
                Material mat2 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                mat2.setColor("BaseColor", new ColorRGBA(0.1f, 0.8f, 0.1f, 1.0f));
                mat2.setFloat("Metallic", 0.0f);
                mat2.setFloat("Roughness", 0.9f);

                Geometry box = new Geometry("roughBox", new Box(0.9f, 0.9f, 0.9f));
                box.setMaterial(mat2);
                box.setLocalTranslation(0, 0, 0);
                mdiNode.attachChild(box);

                // Gold metallic monkey head (right)
                Material mat3 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                mat3.setColor("BaseColor", new ColorRGBA(1.0f, 0.76f, 0.33f, 1.0f));
                mat3.setFloat("Metallic", 1.0f);
                mat3.setFloat("Roughness", 0.3f);

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
                monkey.setMaterial(mat3);
                monkey.setLocalTranslation(3, 0, 0);
                mdiNode.attachChild(monkey);

                // Blue emissive sphere (top)
                Material mat4 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                mat4.setColor("BaseColor", new ColorRGBA(0.1f, 0.1f, 0.3f, 1.0f));
                mat4.setFloat("Metallic", 0.0f);
                mat4.setFloat("Roughness", 0.5f);
                mat4.setColor("Emissive", new ColorRGBA(0.2f, 0.4f, 1.0f, 1.0f));
                mat4.setFloat("EmissiveIntensity", 2.0f);

                Geometry sphere2 = new Geometry("emissiveSphere",
                        new Sphere(24, 24, 0.8f));
                sphere2.setMaterial(mat4);
                sphere2.setLocalTranslation(0, 2.5f, 0);
                mdiNode.attachChild(sphere2);

                // Batch all 4 objects into one MDI call
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
