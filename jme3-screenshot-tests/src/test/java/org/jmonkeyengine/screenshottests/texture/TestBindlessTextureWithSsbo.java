/*
 * Copyright (c) 2025 jMonkeyEngine
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
package org.jmonkeyengine.screenshottests.texture;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.scene.Geometry;
import com.jme3.scene.instancing.InstancedNode;
import com.jme3.scene.shape.Quad;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.EnumSet;

/**
 * Screenshot test demonstrating bindless textures combined with a Shader Storage
 * Buffer Object (SSBO) to render instanced quads where each instance samples a
 * different texture. The SSBO contains the 64-bit bindless texture handles, and
 * the fragment shader indexes into it using {@code gl_InstanceID}.
 *
 * <p>Requires hardware support for both {@link Caps#BindlessTexture} and
 * {@link Caps#ShaderStorageBufferObject}. The test will throw
 * {@link UnsupportedOperationException} on unsupported hardware.</p>
 */
public class TestBindlessTextureWithSsbo extends ScreenshotTestBase {

    private static final String[] TEXTURE_PATHS = {
        "Textures/ColoredTex/Monkey.png",
        "Textures/Terrain/BrickWall/BrickWall.jpg",
        "Textures/Terrain/splat/grass.jpg",
        "Textures/Terrain/splat/dirt.jpg"
    };

    @Test
    public void testBindlessWithSsbo() {
        screenshotTest(new BaseAppState() {

            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;
                EnumSet<Caps> caps = app.getRenderer().getCaps();

                if (!caps.contains(Caps.BindlessTexture)) {
                    throw new UnsupportedOperationException(
                        "Bindless textures not supported on this hardware");
                }
                if (!caps.contains(Caps.ShaderStorageBufferObject)) {
                    throw new UnsupportedOperationException(
                        "Shader Storage Buffer Objects not supported on this hardware");
                }

                app.getRenderer().setBindlessTextureEnabled(true);

                // Use parallel projection for pixel-deterministic rendering
                app.getCamera().setParallelProjection(true);
                float aspect = (float) app.getCamera().getWidth() / app.getCamera().getHeight();
                float halfHeight = 5f;
                app.getCamera().setFrustum(-100, 100,
                    -halfHeight * aspect, halfHeight * aspect,
                    halfHeight, -halfHeight);
                app.getCamera().setLocation(new Vector3f(0, 0, 10));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                // Load textures from testdata and force GPU upload + handle creation
                Texture2D[] textures = new Texture2D[TEXTURE_PATHS.length];
                try {
                    for (int i = 0; i < TEXTURE_PATHS.length; i++) {
                        Texture loaded = app.getAssetManager().loadTexture(TEXTURE_PATHS[i]);
                        textures[i] = (Texture2D) loaded;
                        app.getRenderer().setTexture(i, textures[i]);
                    }
                } catch (com.jme3.renderer.TextureUnitException e) {
                    throw new RuntimeException("Failed to upload textures", e);
                }

                // Populate SSBO with the bindless texture handles
                BufferObject ssbo = new BufferObject();
                ssbo.initializeEmpty(TEXTURE_PATHS.length * 8);
                ByteBuffer buf = ssbo.getData();
                for (Texture2D tex : textures) {
                    buf.putLong(tex.getImage().getBindlessHandle());
                }
                buf.rewind();

                // Create the custom material
                Material mat = new Material(app.getAssetManager(),
                    "ScreenshotTests/MatDefs/BindlessInstanced.j3md");
                mat.setShaderStorageBufferObject("TextureHandles", ssbo);
                mat.setBoolean("UseInstancing", true);

                // Create instanced node with 4 quads at different positions
                InstancedNode instancedNode = new InstancedNode("Instanced");
                simpleApp.getRootNode().attachChild(instancedNode);

                Quad mesh = new Quad(2, 2);
                float spacing = 3f;
                float startX = -((TEXTURE_PATHS.length - 1) * spacing) / 2f;
                for (int i = 0; i < TEXTURE_PATHS.length; i++) {
                    Geometry quad = new Geometry("Quad" + i, mesh);
                    quad.setMaterial(mat);
                    quad.setLocalTranslation(startX + i * spacing, -1, 0);
                    instancedNode.attachChild(quad);
                }
                instancedNode.instance();
            }

            @Override
            protected void cleanup(Application app) { }

            @Override
            protected void onEnable() { }

            @Override
            protected void onDisable() { }
        })
        .setFramesToTakeScreenshotsOn(1)
        .setTestType(org.jmonkeyengine.screenshottests.testframework.TestType.NON_DETERMINISTIC)
        .run();
    }
}
