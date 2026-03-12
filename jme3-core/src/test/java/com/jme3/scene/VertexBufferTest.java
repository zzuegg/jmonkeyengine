/*
 * Copyright (c) 2009-2024 jMonkeyEngine
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
package com.jme3.scene;

import com.jme3.scene.VertexBuffer.Format;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.util.BufferUtils;
import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.*;

/**
 * Tests for {@link VertexBuffer}, focusing on multi-slot component support.
 */
public class VertexBufferTest {

    @Test
    public void testSetupData_standardComponents() {
        // 1 to 4 components should work for any buffer type
        for (int c = 1; c <= 4; c++) {
            VertexBuffer vb = new VertexBuffer(Type.Position);
            FloatBuffer fb = BufferUtils.createFloatBuffer(3 * c);
            vb.setupData(Usage.Static, c, Format.Float, fb);
            assertEquals(c, vb.getNumComponents());
        }
    }

    @Test
    public void testSetupData_multiSlotComponents_anyBufferType() {
        // 8 components (2 slots) should work on any buffer type, not just InstanceData
        Type[] types = { Type.TexCoord2, Type.TexCoord3, Type.InstanceData };
        for (Type type : types) {
            VertexBuffer vb = new VertexBuffer(type);
            FloatBuffer fb = BufferUtils.createFloatBuffer(3 * 8);
            vb.setupData(Usage.Static, 8, Format.Float, fb);
            assertEquals(8, vb.getNumComponents());
        }
    }

    @Test
    public void testSetupData_16Components_texCoord() {
        // 16 components (4 slots, e.g. a matrix) should work on TexCoord buffers
        VertexBuffer vb = new VertexBuffer(Type.TexCoord2);
        FloatBuffer fb = BufferUtils.createFloatBuffer(2 * 16);
        vb.setupData(Usage.Static, 16, Format.Float, fb);
        assertEquals(16, vb.getNumComponents());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetupData_rejectsZeroComponents() {
        VertexBuffer vb = new VertexBuffer(Type.Position);
        FloatBuffer fb = BufferUtils.createFloatBuffer(3);
        vb.setupData(Usage.Static, 0, Format.Float, fb);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetupData_rejectsNonMultipleOf4_above4() {
        // 5, 6, 7 etc. are not valid — must be 1-4 or a multiple of 4
        VertexBuffer vb = new VertexBuffer(Type.Position);
        FloatBuffer fb = BufferUtils.createFloatBuffer(3 * 5);
        vb.setupData(Usage.Static, 5, Format.Float, fb);
    }

    @Test
    public void testCreateBuffer_multiSlotComponents() {
        // createBuffer should also accept multiples of 4 above 4
        java.nio.Buffer buf = VertexBuffer.createBuffer(Format.Float, 8, 10);
        assertEquals(80, buf.capacity());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateBuffer_rejectsNonMultipleOf4() {
        VertexBuffer.createBuffer(Format.Float, 6, 10);
    }
}
