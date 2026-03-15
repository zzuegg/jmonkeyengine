/*
 * Copyright (c) 2024 jMonkeyEngine
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
package org.jmonkeyengine.screenshottests.scene;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

/**
 * Verifies that a non-InstanceData VertexBuffer with >4 components (multi-slot)
 * renders correctly through the real GL pipeline. A custom shader reads from
 * all 4 rows of a 16-component TexCoord3 attribute (bound as a mat4), using
 * each row's .x to build the final RGBA color. This proves that all 4 attribute
 * slots carry data correctly through the pipeline.
 *
 * <p>The quad should render with each vertex showing a different color:
 * red (bottom-left), green (bottom-right), blue (top-left), yellow (top-right).
 * If any slot fails to deliver data, the corresponding color channel will be
 * wrong (e.g. missing alpha makes vertices transparent against the black background).</p>
 */
public class TestMultiSlotVertexBuffer extends ScreenshotTestBase {

    @Test
    public void testMultiSlotVertexBuffer() {
        screenshotTest(new BaseAppState() {
            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;

                Mesh mesh = new Mesh();

                // A simple quad: 4 vertices, 2 triangles
                FloatBuffer positions = BufferUtils.createFloatBuffer(
                    -1, -1, 0,   // bottom-left
                     1, -1, 0,   // bottom-right
                    -1,  1, 0,   // top-left
                     1,  1, 0    // top-right
                );
                mesh.setBuffer(VertexBuffer.Type.Position, 3, positions);

                // Index buffer for 2 triangles
                mesh.setBuffer(VertexBuffer.Type.Index, 3,
                        new short[]{0, 1, 2, 1, 3, 2});

                // 16-component TexCoord3 buffer (mat4 per vertex).
                // The shader reads row[i].x from each of the 4 rows to build
                // the final RGBA color, ensuring all 4 attribute slots are used:
                //   R = row0.x, G = row1.x, B = row2.x, A = row3.x
                FloatBuffer texCoord3 = BufferUtils.createFloatBuffer(
                    // Vertex 0 (bottom-left): RED (R=1, G=0, B=0, A=1)
                    1, 0, 0, 0,   // row 0 (slot 0): R=1
                    0, 0, 0, 0,   // row 1 (slot 1): G=0
                    0, 0, 0, 0,   // row 2 (slot 2): B=0
                    1, 0, 0, 0,   // row 3 (slot 3): A=1

                    // Vertex 1 (bottom-right): GREEN (R=0, G=1, B=0, A=1)
                    0, 0, 0, 0,   // row 0: R=0
                    1, 0, 0, 0,   // row 1: G=1
                    0, 0, 0, 0,   // row 2: B=0
                    1, 0, 0, 0,   // row 3: A=1

                    // Vertex 2 (top-left): BLUE (R=0, G=0, B=1, A=1)
                    0, 0, 0, 0,   // row 0: R=0
                    0, 0, 0, 0,   // row 1: G=0
                    1, 0, 0, 0,   // row 2: B=1
                    1, 0, 0, 0,   // row 3: A=1

                    // Vertex 3 (top-right): YELLOW (R=1, G=1, B=0, A=1)
                    1, 0, 0, 0,   // row 0: R=1
                    1, 0, 0, 0,   // row 1: G=1
                    0, 0, 0, 0,   // row 2: B=0
                    1, 0, 0, 0    // row 3: A=1
                );
                mesh.setBuffer(VertexBuffer.Type.TexCoord3, 16, texCoord3);

                mesh.updateBound();

                Geometry geo = new Geometry("multiSlotQuad", mesh);
                Material mat = new Material(simpleApp.getAssetManager(),
                        "TestMultiSlot/MultiSlot.j3md");
                geo.setMaterial(mat);

                simpleApp.getRootNode().attachChild(geo);

                // Position camera to see the quad
                simpleApp.getCamera().setLocation(new Vector3f(0, 0, 3));
                simpleApp.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
            }

            @Override
            protected void cleanup(Application app) {}

            @Override
            protected void onEnable() {}

            @Override
            protected void onDisable() {}
        }).run();
    }
}
