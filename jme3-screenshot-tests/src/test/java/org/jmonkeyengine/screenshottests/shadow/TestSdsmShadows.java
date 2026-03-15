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
package org.jmonkeyengine.screenshottests.shadow;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.asset.AssetManager;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.Materials;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.CenterQuad;
import com.jme3.scene.shape.Sphere;
import com.jme3.shadow.SdsmDirectionalLightShadowFilter;
import com.jme3.texture.Texture;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

/**
 * Screenshot tests for SDSM (Sample Distribution Shadow Mapping) compute shader shadows.
 * These tests verify that the compute-shader-based shadow fitting produces correct shadows.
 */
public class TestSdsmShadows extends ScreenshotTestBase {

    /**
     * Basic SDSM test: a single box casting a shadow onto a ground plane.
     * This establishes the baseline that SDSM shadows render correctly.
     */
    @Test
    public void testSdsmBasicShadow() {
        screenshotTest(new SdsmBasicShadowState())
                .setFramesToTakeScreenshotsOn(60)
                .run();
    }

    /**
     * Complex SDSM test: multiple objects at varying distances from the camera
     * with different shadow cascade splits. Tests that the compute shader
     * correctly fits frustums across a scene with large depth range.
     */
    @Test
    public void testSdsmComplexScene() {
        screenshotTest(new SdsmComplexSceneState())
                .setFramesToTakeScreenshotsOn(60)
                .run();
    }

    // ---- Basic shadow: box on ground ----

    private static class SdsmBasicShadowState extends BaseAppState {
        @Override
        public void initialize(Application app) {
            AssetManager assetManager = app.getAssetManager();
            Node rootNode = ((SimpleApplication) app).getRootNode();
            Camera cam = app.getCamera();
            ViewPort viewPort = app.getViewPort();

            // Camera
            cam.setLocation(new Vector3f(0, 5f, 10f));
            cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

            // Ground
            CenterQuad groundMesh = new CenterQuad(20, 20);
            groundMesh.scaleTextureCoordinates(new Vector2f(4, 4));
            Geometry ground = new Geometry("Ground", groundMesh);
            Material groundMat = new Material(assetManager, Materials.LIGHTING);
            Texture tex = assetManager.loadTexture("Common/Textures/MissingTexture.png");
            tex.setWrap(Texture.WrapMode.Repeat);
            groundMat.setTexture("DiffuseMap", tex);
            ground.setMaterial(groundMat);
            ground.rotate(-FastMath.HALF_PI, 0, 0);
            ground.setShadowMode(RenderQueue.ShadowMode.Receive);
            rootNode.attachChild(ground);

            // Box casting shadow
            Box boxMesh = new Box(1, 1, 1);
            Geometry box = new Geometry("Box", boxMesh);
            Material boxMat = new Material(assetManager, Materials.LIGHTING);
            boxMat.setColor("Diffuse", ColorRGBA.Blue);
            boxMat.setBoolean("UseMaterialColors", true);
            box.setMaterial(boxMat);
            box.setLocalTranslation(0, 1, 0);
            box.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            rootNode.attachChild(box);

            // Lights
            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-0.5f, -1f, -0.5f).normalizeLocal());
            sun.setColor(ColorRGBA.White);
            rootNode.addLight(sun);

            AmbientLight ambient = new AmbientLight();
            ambient.setColor(new ColorRGBA(0.3f, 0.3f, 0.3f, 1f));
            rootNode.addLight(ambient);

            // SDSM shadow filter
            SdsmDirectionalLightShadowFilter sdsmFilter =
                    new SdsmDirectionalLightShadowFilter(assetManager, 2048, 3);
            sdsmFilter.setLight(sun);
            sdsmFilter.setShadowIntensity(0.5f);

            FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
            fpp.addFilter(sdsmFilter);
            viewPort.addProcessor(fpp);
        }

        @Override protected void cleanup(Application app) {}
        @Override protected void onEnable() {}
        @Override protected void onDisable() {}
    }

    // ---- Complex scene: multiple objects, varying depths ----

    private static class SdsmComplexSceneState extends BaseAppState {
        @Override
        public void initialize(Application app) {
            AssetManager assetManager = app.getAssetManager();
            Node rootNode = ((SimpleApplication) app).getRootNode();
            Camera cam = app.getCamera();
            ViewPort viewPort = app.getViewPort();

            // Camera looking across a large depth range
            cam.setLocation(new Vector3f(0, 8f, 20f));
            cam.lookAt(new Vector3f(0, 0, -10), Vector3f.UNIT_Y);
            cam.setFrustumPerspective(45, (float) cam.getWidth() / cam.getHeight(), 0.5f, 200f);

            // Large ground plane
            CenterQuad groundMesh = new CenterQuad(100, 100);
            groundMesh.scaleTextureCoordinates(new Vector2f(20, 20));
            Geometry ground = new Geometry("Ground", groundMesh);
            Material groundMat = new Material(assetManager, Materials.LIGHTING);
            groundMat.setColor("Diffuse", new ColorRGBA(0.6f, 0.6f, 0.6f, 1f));
            groundMat.setBoolean("UseMaterialColors", true);
            ground.setMaterial(groundMat);
            ground.rotate(-FastMath.HALF_PI, 0, 0);
            ground.setShadowMode(RenderQueue.ShadowMode.Receive);
            rootNode.attachChild(ground);

            // Near objects — small cubes in a row
            for (int i = 0; i < 5; i++) {
                Box boxMesh = new Box(0.5f, 0.5f + i * 0.3f, 0.5f);
                Geometry box = new Geometry("NearBox" + i, boxMesh);
                Material mat = new Material(assetManager, Materials.LIGHTING);
                mat.setColor("Diffuse", new ColorRGBA(0.2f + i * 0.15f, 0.3f, 0.8f - i * 0.1f, 1f));
                mat.setBoolean("UseMaterialColors", true);
                box.setMaterial(mat);
                box.setLocalTranslation(-4 + i * 2, 0.5f + i * 0.3f, 5);
                box.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                rootNode.attachChild(box);
            }

            // Mid-distance objects — spheres
            for (int i = 0; i < 3; i++) {
                Sphere sphereMesh = new Sphere(16, 16, 1.0f + i * 0.5f);
                Geometry sphere = new Geometry("MidSphere" + i, sphereMesh);
                Material mat = new Material(assetManager, Materials.LIGHTING);
                mat.setColor("Diffuse", new ColorRGBA(0.8f, 0.4f + i * 0.2f, 0.2f, 1f));
                mat.setBoolean("UseMaterialColors", true);
                sphere.setMaterial(mat);
                sphere.setLocalTranslation(-3 + i * 3, 1.0f + i * 0.5f, -5);
                sphere.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                rootNode.attachChild(sphere);
            }

            // Far objects — tall pillars testing deep cascade splits
            for (int i = 0; i < 3; i++) {
                Box pillarMesh = new Box(0.8f, 3f, 0.8f);
                Geometry pillar = new Geometry("FarPillar" + i, pillarMesh);
                Material mat = new Material(assetManager, Materials.LIGHTING);
                mat.setColor("Diffuse", new ColorRGBA(0.5f, 0.5f, 0.5f + i * 0.15f, 1f));
                mat.setBoolean("UseMaterialColors", true);
                pillar.setMaterial(mat);
                pillar.setLocalTranslation(-4 + i * 4, 3, -25 - i * 10);
                pillar.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                rootNode.attachChild(pillar);
            }

            // Object casting long shadow across scene
            Box wallMesh = new Box(6, 2, 0.3f);
            Geometry wall = new Geometry("Wall", wallMesh);
            Material wallMat = new Material(assetManager, Materials.LIGHTING);
            wallMat.setColor("Diffuse", new ColorRGBA(0.7f, 0.3f, 0.3f, 1f));
            wallMat.setBoolean("UseMaterialColors", true);
            wall.setMaterial(wallMat);
            wall.setLocalTranslation(0, 2, 0);
            wall.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            rootNode.attachChild(wall);

            // Lights — sun at an angle that creates interesting cascade distribution
            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-0.3f, -0.8f, -0.5f).normalizeLocal());
            sun.setColor(ColorRGBA.White.mult(0.9f));
            rootNode.addLight(sun);

            AmbientLight ambient = new AmbientLight();
            ambient.setColor(new ColorRGBA(0.25f, 0.25f, 0.3f, 1f));
            rootNode.addLight(ambient);

            // SDSM with 4 cascades — max splits to test all cascade fitting
            SdsmDirectionalLightShadowFilter sdsmFilter =
                    new SdsmDirectionalLightShadowFilter(assetManager, 2048, 4);
            sdsmFilter.setLight(sun);
            sdsmFilter.setShadowIntensity(0.5f);
            sdsmFilter.setFitExpansionFactor(1.05f);

            FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
            fpp.addFilter(sdsmFilter);
            viewPort.addProcessor(fpp);
        }

        @Override protected void cleanup(Application app) {}
        @Override protected void onEnable() {}
        @Override protected void onDisable() {}
    }
}
