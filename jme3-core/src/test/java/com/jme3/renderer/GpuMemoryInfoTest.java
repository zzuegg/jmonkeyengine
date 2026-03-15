/*
 * Copyright (c) 2009-2026 jMonkeyEngine
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
package com.jme3.renderer;

import org.junit.Test;

import com.jme3.system.NullRenderer;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link GpuMemoryInfo}.
 */
public class GpuMemoryInfoTest {

    @Test
    public void testUnsupportedReturnsNegativeOne() {
        GpuMemoryInfo info = new GpuMemoryInfo(-1, -1, -1);
        assertEquals(-1, info.getDedicatedMemoryKB());
        assertEquals(-1, info.getTotalAvailableMemoryKB());
        assertEquals(-1, info.getCurrentAvailableMemoryKB());
        assertFalse(info.isSupported());
    }

    @Test
    public void testNvidiaValues() {
        GpuMemoryInfo info = new GpuMemoryInfo(8388608, 8500000, 7000000);
        assertEquals(8388608, info.getDedicatedMemoryKB());
        assertEquals(8500000, info.getTotalAvailableMemoryKB());
        assertEquals(7000000, info.getCurrentAvailableMemoryKB());
        assertTrue(info.isSupported());
    }

    @Test
    public void testAmdValuesPartialSupport() {
        GpuMemoryInfo info = new GpuMemoryInfo(-1, -1, 4000000);
        assertEquals(-1, info.getDedicatedMemoryKB());
        assertEquals(-1, info.getTotalAvailableMemoryKB());
        assertEquals(4000000, info.getCurrentAvailableMemoryKB());
        assertTrue(info.isSupported());
    }

    @Test
    public void testToString() {
        GpuMemoryInfo info = new GpuMemoryInfo(1024, -1, 512);
        String s = info.toString();
        assertTrue(s.contains("1024 KB"));
        assertTrue(s.contains("N/A"));
        assertTrue(s.contains("512 KB"));
    }

    @Test
    public void testDefaultRendererReturnsUnsupported() {
        // The default implementation on Renderer interface returns all -1
        Renderer renderer = new NullRenderer();
        GpuMemoryInfo info = renderer.getGpuMemoryInfo();
        assertFalse(info.isSupported());
    }
}
