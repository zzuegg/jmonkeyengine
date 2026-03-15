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
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;
import org.jmonkeyengine.screenshottests.testframework.Scenario;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

/**
 * Screenshot test that verifies texture sampling options (wrap mode) work
 * correctly with bindless textures, including runtime changes.
 *
 * <p>Renders a quad with UV coordinates in the range [0, 3] so the wrap
 * mode is clearly visible. Frame 1 uses {@link Texture.WrapMode#Repeat},
 * frame 3 switches to {@link Texture.WrapMode#EdgeClamp}. Both
 * traditional and bindless paths are compared for identical output.</p>
 *
 * <p>On hardware without bindless support, both scenarios silently
 * fall back to traditional binding, so the test still passes.</p>
 */
public class TestBindlessTextureSamplerChange extends ScreenshotTestBase {

    @Test
    public void testBindlessSamplerChange() {
        screenshotMultiScenarioTest(
            new Scenario("Traditional", createAppState(false)),
            new Scenario("Bindless", createAppState(true))
        )
        .setFramesToTakeScreenshotsOn(1, 3)
        .run();
    }

    private static BaseAppState createAppState(boolean enableBindless) {
        return new BaseAppState() {
            private Texture texture;

            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;

                app.getCamera().setLocation(new Vector3f(1.5f, 1.5f, 5f));
                app.getCamera().lookAt(new Vector3f(1.5f, 1.5f, 0), Vector3f.UNIT_Y);

                if (enableBindless) {
                    app.getRenderer().setBindlessTextureEnabled(true);
                }

                texture = app.getAssetManager().loadTexture("Textures/ColoredTex/Monkey.png");
                texture.setWrap(Texture.WrapMode.Repeat);

                Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
                mat.setTexture("ColorMap", texture);

                // Create a quad with UVs in [0, 3] to make wrap mode visible
                Geometry quad = new Geometry("Quad", createTiledQuad(3f, 3f, 3f));
                quad.setMaterial(mat);
                simpleApp.getRootNode().attachChild(quad);
            }

            @Override
            public void update(float tpf) {
                // On frame 2, switch wrap mode to EdgeClamp so frame 3
                // captures the changed state.
                // IsoTimer runs at 60 FPS, so frame 2 happens on the second update.
                // We count based on update calls since initialize.
                if (texture != null && frameCount == 2) {
                    texture.setWrap(Texture.WrapMode.EdgeClamp);
                }
                frameCount++;
            }

            private int frameCount = 0;

            @Override
            protected void cleanup(Application app) { }

            @Override
            protected void onEnable() { }

            @Override
            protected void onDisable() { }
        };
    }

    /**
     * Creates a quad mesh with UV coordinates tiled to the given factor.
     * The quad spans from (0,0) to (width, height) in the XY plane.
     */
    private static Mesh createTiledQuad(float width, float height, float uvTile) {
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(
            0, 0, 0,
            width, 0, 0,
            width, height, 0,
            0, height, 0
        ));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(
            0, 0,
            uvTile, 0,
            uvTile, uvTile,
            0, uvTile
        ));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createShortBuffer(
            (short) 0, (short) 1, (short) 2,
            (short) 0, (short) 2, (short) 3
        ));
        mesh.updateBound();
        return mesh;
    }
}
