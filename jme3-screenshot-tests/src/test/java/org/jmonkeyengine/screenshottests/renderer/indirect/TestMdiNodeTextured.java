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
import com.jme3.texture.Texture;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Comprehensive test for MdiNode with textured PBR objects.
 * <p>
 * Renders 6 objects in a single MdiNode using PBRLighting.j3md:
 * - 3 different meshes (box, sphere, monkey head)
 * - 3 different BaseColorMap textures (brick, grass, monkey)
 * - Varying metallic/roughness per object
 * - All batched into one MDI call with per-draw textures via bindless handles
 * <p>
 * This verifies the full pipeline: MdiNode.batch() -> texture preloading ->
 * bindless handle resolution -> DrawData SSBO serialization -> MDI rendering
 * with per-draw textures.
 * <p>
 * Requires: {@link Caps#MultiDrawIndirect}, {@link Caps#ShaderStorageBufferObject},
 * {@link Caps#BindlessTexture}.
 */
public class TestMdiNodeTextured extends ScreenshotTestBase {

    @Test
    public void testTexturedPBRMdi() {
        screenshotTest(new BaseAppState() {
            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;
                EnumSet<Caps> caps = app.getRenderer().getCaps();

                if (!caps.contains(Caps.MultiDrawIndirect)
                        || !caps.contains(Caps.ShaderStorageBufferObject)
                        || !caps.contains(Caps.BindlessTexture)) {
                    return;
                }

                app.getCamera().setLocation(new Vector3f(0, 2, 12));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                // Lighting
                DirectionalLight sun = new DirectionalLight();
                sun.setDirection(new Vector3f(-1, -2, -3).normalizeLocal());
                sun.setColor(ColorRGBA.White.mult(2.0f));
                simpleApp.getRootNode().addLight(sun);

                AmbientLight ambient = new AmbientLight();
                ambient.setColor(new ColorRGBA(0.3f, 0.3f, 0.3f, 1.0f));
                simpleApp.getRootNode().addLight(ambient);

                // Load textures
                Texture brickTex = app.getAssetManager()
                        .loadTexture("Textures/Terrain/BrickWall/BrickWall.jpg");
                Texture grassTex = app.getAssetManager()
                        .loadTexture("Textures/Terrain/splat/grass.jpg");
                Texture monkeyTex = app.getAssetManager()
                        .loadTexture("Textures/ColoredTex/Monkey.png");

                // Load monkey mesh
                Spatial monkeyModel = app.getAssetManager()
                        .loadModel("Models/MonkeyHead/MonkeyHead.mesh.xml");
                Mesh monkeyMesh;
                if (monkeyModel instanceof Geometry) {
                    monkeyMesh = ((Geometry) monkeyModel).getMesh();
                } else {
                    monkeyMesh = ((Geometry) ((com.jme3.scene.Node) monkeyModel)
                            .getChild(0)).getMesh();
                }

                MdiNode mdiNode = new MdiNode("texturedMdi");
                simpleApp.getRootNode().attachChild(mdiNode);

                // --- Top row: textured objects with varying roughness ---

                // Brick box (top-left) - rough dielectric
                Material brickMat = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                brickMat.setColor("BaseColor", ColorRGBA.White);
                brickMat.setTexture("BaseColorMap", brickTex);
                brickMat.setFloat("Metallic", 0.0f);
                brickMat.setFloat("Roughness", 0.9f);

                Geometry brickBox = new Geometry("brickBox",
                        new Box(1.0f, 1.0f, 1.0f));
                brickBox.setMaterial(brickMat);
                brickBox.setLocalTranslation(-3.5f, 1.5f, 0);
                mdiNode.attachChild(brickBox);

                // Grass sphere (top-center) - medium roughness
                Material grassMat = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                grassMat.setColor("BaseColor", ColorRGBA.White);
                grassMat.setTexture("BaseColorMap", grassTex);
                grassMat.setFloat("Metallic", 0.0f);
                grassMat.setFloat("Roughness", 0.5f);

                Geometry grassSphere = new Geometry("grassSphere",
                        new Sphere(32, 32, 1.2f));
                grassSphere.setMaterial(grassMat);
                grassSphere.setLocalTranslation(0, 1.5f, 0);
                mdiNode.attachChild(grassSphere);

                // Monkey-textured monkey head (top-right) - shiny
                Material monkeyTexMat = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                monkeyTexMat.setColor("BaseColor", ColorRGBA.White);
                monkeyTexMat.setTexture("BaseColorMap", monkeyTex);
                monkeyTexMat.setFloat("Metallic", 0.3f);
                monkeyTexMat.setFloat("Roughness", 0.2f);

                Geometry texMonkey = new Geometry("texMonkey", monkeyMesh);
                texMonkey.setMaterial(monkeyTexMat);
                texMonkey.setLocalTranslation(3.5f, 1.5f, 0);
                mdiNode.attachChild(texMonkey);

                // --- Bottom row: untextured objects with different colors ---

                // Red metallic sphere (bottom-left)
                Material redMat = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                redMat.setColor("BaseColor", new ColorRGBA(0.9f, 0.1f, 0.1f, 1.0f));
                redMat.setFloat("Metallic", 1.0f);
                redMat.setFloat("Roughness", 0.1f);

                Geometry redSphere = new Geometry("redSphere",
                        new Sphere(32, 32, 1.0f));
                redSphere.setMaterial(redMat);
                redSphere.setLocalTranslation(-3.5f, -1.5f, 0);
                mdiNode.attachChild(redSphere);

                // Green rough box (bottom-center)
                Material greenMat = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                greenMat.setColor("BaseColor", new ColorRGBA(0.1f, 0.8f, 0.1f, 1.0f));
                greenMat.setFloat("Metallic", 0.0f);
                greenMat.setFloat("Roughness", 0.8f);

                Geometry greenBox = new Geometry("greenBox",
                        new Box(0.9f, 0.9f, 0.9f));
                greenBox.setMaterial(greenMat);
                greenBox.setLocalTranslation(0, -1.5f, 0);
                mdiNode.attachChild(greenBox);

                // Gold metallic monkey (bottom-right)
                Material goldMat = new Material(app.getAssetManager(),
                        "Common/MatDefs/Light/PBRLighting.j3md");
                goldMat.setColor("BaseColor", new ColorRGBA(1.0f, 0.76f, 0.33f, 1.0f));
                goldMat.setFloat("Metallic", 1.0f);
                goldMat.setFloat("Roughness", 0.3f);

                Geometry goldMonkey = new Geometry("goldMonkey", monkeyMesh);
                goldMonkey.setMaterial(goldMat);
                goldMonkey.setLocalTranslation(3.5f, -1.5f, 0);
                mdiNode.attachChild(goldMonkey);

                // Batch all 6 into one MDI call
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
