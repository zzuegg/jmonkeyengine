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
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;
import org.jmonkeyengine.screenshottests.testframework.Scenario;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

/**
 * Screenshot test that verifies bindless texture rendering produces identical
 * output to traditional texture binding. Uses a multi-scenario comparison
 * between traditional and bindless paths.
 *
 * <p>On hardware that does not support bindless textures, both scenarios
 * silently fall back to traditional binding, so the test still passes.</p>
 */
public class TestBindlessTexture extends ScreenshotTestBase {

    @Test
    public void testBindlessTexture() {
        screenshotMultiScenarioTest(
            new Scenario("Traditional", createAppState(false)),
            new Scenario("Bindless", createAppState(true))
        )
        .setFramesToTakeScreenshotsOn(1)
        .run();
    }

    private static BaseAppState createAppState(boolean enableBindless) {
        return new BaseAppState() {
            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;

                app.getCamera().setLocation(new Vector3f(0.5f, 0.5f, 2f));
                app.getCamera().lookAt(new Vector3f(0.5f, 0.5f, 0), Vector3f.UNIT_Y);

                if (enableBindless) {
                    app.getRenderer().setBindlessTextureEnabled(true);
                }

                Texture tex = app.getAssetManager().loadTexture("Textures/ColoredTex/Monkey.png");

                Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
                mat.setTexture("ColorMap", tex);

                Geometry quad = new Geometry("Quad", new Quad(1, 1));
                quad.setMaterial(mat);
                simpleApp.getRootNode().attachChild(quad);
            }

            @Override
            protected void cleanup(Application app) { }

            @Override
            protected void onEnable() { }

            @Override
            protected void onDisable() { }
        };
    }
}
