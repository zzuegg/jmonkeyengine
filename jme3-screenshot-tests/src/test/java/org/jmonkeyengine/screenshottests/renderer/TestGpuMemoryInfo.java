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
package org.jmonkeyengine.screenshottests.renderer;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Caps;
import com.jme3.renderer.GpuMemoryInfo;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link Renderer#getGpuMemoryInfo()}.
 * <p>
 * Verifies that GPU memory queries work with a real GL context.
 * On supported hardware (NVIDIA or AMD), checks that returned values
 * are positive. On unsupported hardware, verifies graceful -1 returns.
 */
public class TestGpuMemoryInfo extends ScreenshotTestBase {

    private static final Logger logger = Logger.getLogger(TestGpuMemoryInfo.class.getName());

    @Test
    public void testGpuMemoryInfo() {
        screenshotTest(
            new BaseAppState() {
                @Override
                protected void initialize(Application app) {
                    Renderer renderer = app.getRenderer();

                    // Query memory info
                    GpuMemoryInfo info = renderer.getGpuMemoryInfo();
                    assertNotNull(info, "getGpuMemoryInfo() must never return null");
                    logger.log(Level.INFO, "GPU Memory Info: {0}", info);

                    if (renderer.getCaps().contains(Caps.GpuMemoryInfo)) {
                        // Extension is available — current available must be positive
                        assertTrue(info.isSupported(),
                                "GpuMemoryInfo should be supported when Caps.GpuMemoryInfo is present");
                        assertTrue(info.getCurrentAvailableMemoryKB() > 0,
                                "Current available memory should be positive, got: "
                                        + info.getCurrentAvailableMemoryKB());

                        // Query a second time to verify it returns a fresh snapshot
                        GpuMemoryInfo info2 = renderer.getGpuMemoryInfo();
                        assertNotNull(info2);
                        assertTrue(info2.getCurrentAvailableMemoryKB() > 0,
                                "Second query should also return positive available memory");
                    } else {
                        // No extension — all values must be -1
                        assertFalse(info.isSupported(),
                                "GpuMemoryInfo should not be supported without the extension");
                        assertEquals(-1, info.getCurrentAvailableMemoryKB());
                        assertEquals(-1, info.getDedicatedMemoryKB());
                        assertEquals(-1, info.getTotalAvailableMemoryKB());
                    }

                    // Render something so the screenshot test has output
                    Geometry box = new Geometry("Box", new Box(1, 1, 1));
                    Material mat = new Material(app.getAssetManager(),
                            "Common/MatDefs/Misc/Unshaded.j3md");
                    mat.setColor("Color", ColorRGBA.Green);
                    box.setMaterial(mat);
                    ((com.jme3.app.SimpleApplication) app).getRootNode().attachChild(box);
                }

                @Override protected void cleanup(Application app) {}
                @Override protected void onEnable() {}
                @Override protected void onDisable() {}
            }
        )
        .setFramesToTakeScreenshotsOn(2)
        .run();
    }
}
