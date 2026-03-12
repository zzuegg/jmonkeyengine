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

import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderContext;
import com.jme3.renderer.opengl.GL;
import com.jme3.renderer.opengl.GLExt;
import com.jme3.renderer.opengl.GLFbo;
import com.jme3.renderer.opengl.GLRenderer;
import com.jme3.scene.VertexBuffer.Format;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.shader.Shader;
import com.jme3.util.BufferUtils;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests that GLRenderer.setVertexAttrib correctly handles multi-slot
 * vertex buffers (components > 4) for any buffer type, not just InstanceData.
 * Also verifies that multi-slot bindings do not interfere with existing
 * single-slot attribute bindings.
 */
public class VertexBufferMultiSlotRendererTest {

    private GL gl;
    private GLExt glext;
    private GLFbo glfbo;
    private GLRenderer renderer;
    private Shader shader;

    /** Recorded glVertexAttribPointer calls: {loc, size, stride, offset} */
    private final List<long[]> attribPointerCalls = new ArrayList<>();
    /** Recorded glVertexAttribDivisorARB calls: {slot, divisor} */
    private final List<int[]> divisorCalls = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        gl = mock(GL.class);
        glext = mock(GLExt.class);
        glfbo = mock(GLFbo.class);

        // glGenBuffers must put a valid buffer ID into the IntBuffer
        doAnswer(invocation -> {
            IntBuffer buf = invocation.getArgument(0);
            buf.put(0, 42);
            return null;
        }).when(gl).glGenBuffers(any(IntBuffer.class));

        // Record glVertexAttribPointer calls
        doAnswer(invocation -> {
            int loc = invocation.getArgument(0);
            int size = invocation.getArgument(1);
            int stride = invocation.getArgument(4);
            long offset = invocation.getArgument(5);
            attribPointerCalls.add(new long[]{loc, size, stride, offset});
            return null;
        }).when(gl).glVertexAttribPointer(anyInt(), anyInt(), anyInt(), anyBoolean(), anyInt(), anyLong());

        // Record divisor calls
        doAnswer(invocation -> {
            int slot = invocation.getArgument(0);
            int divisor = invocation.getArgument(1);
            divisorCalls.add(new int[]{slot, divisor});
            return null;
        }).when(glext).glVertexAttribDivisorARB(anyInt(), anyInt());

        renderer = new GLRenderer(gl, glext, glfbo);

        // Set up a shader with known attribute locations via reflection
        shader = new Shader();

        // Inject the shader and program ID into the renderer's RenderContext
        Field contextField = GLRenderer.class.getDeclaredField("context");
        contextField.setAccessible(true);
        RenderContext context = (RenderContext) contextField.get(renderer);
        context.boundShaderProgram = 1;
        context.boundShader = shader;

        // Add MeshInstancing cap for instanced buffer tests
        Field capsField = GLRenderer.class.getDeclaredField("caps");
        capsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        EnumSet<Caps> caps = (EnumSet<Caps>) capsField.get(renderer);
        caps.add(Caps.MeshInstancing);
    }

    /**
     * Helper to create a VertexBuffer with a given type, component count,
     * and pre-set attribute location in the shader.
     */
    private VertexBuffer createVB(Type type, int components, int attribLoc) {
        VertexBuffer vb = new VertexBuffer(type);
        FloatBuffer fb = BufferUtils.createFloatBuffer(3 * components);
        vb.setupData(Usage.Static, components, Format.Float, fb);

        // Pre-set the attribute location so the renderer doesn't call glGetAttribLocation
        shader.getAttribute(type).setLocation(attribLoc);

        return vb;
    }

    @Test
    public void testMultiSlotTexCoord_splitsInto4ComponentSlots() {
        // 8 components on TexCoord2 should produce 2 attribute slots of 4 components each
        VertexBuffer vb = createVB(Type.TexCoord2, 8, 5);

        renderer.setVertexAttrib(vb, null);

        // Should have called glVertexAttribPointer twice (slots at loc 5 and 6)
        assertEquals("Expected 2 glVertexAttribPointer calls for 8-component buffer",
                2, attribPointerCalls.size());

        // Slot 0: loc=5, size=4, stride=4*4*2=32, offset=0
        long[] call0 = attribPointerCalls.get(0);
        assertEquals(5, call0[0]);  // location
        assertEquals(4, call0[1]);  // size (4 components per slot)
        assertEquals(32, call0[2]); // stride = 4 bytes * 4 floats * 2 slots
        assertEquals(0, call0[3]);  // offset = 0

        // Slot 1: loc=6, size=4, stride=32, offset=16
        long[] call1 = attribPointerCalls.get(1);
        assertEquals(6, call1[0]);  // location
        assertEquals(4, call1[1]);  // size
        assertEquals(32, call1[2]); // stride
        assertEquals(16, call1[3]); // offset = 4 bytes * 4 floats * 1

        // Both slots should have been enabled
        verify(gl).glEnableVertexAttribArray(5);
        verify(gl).glEnableVertexAttribArray(6);
    }

    @Test
    public void testMultiSlotTexCoord16_splitsInto4Slots() {
        // 16 components on TexCoord3 (a full 4x4 matrix) should produce 4 attribute slots
        VertexBuffer vb = createVB(Type.TexCoord3, 16, 8);

        renderer.setVertexAttrib(vb, null);

        assertEquals("Expected 4 glVertexAttribPointer calls for 16-component buffer",
                4, attribPointerCalls.size());

        for (int i = 0; i < 4; i++) {
            long[] call = attribPointerCalls.get(i);
            assertEquals(8 + i, call[0]);     // location
            assertEquals(4, call[1]);          // size
            assertEquals(4 * 4 * 4, call[2]);  // stride = 4 bytes * 4 floats * 4 slots = 64
            assertEquals(4 * 4 * i, call[3]);  // offset = 4 bytes * 4 floats * slot index
        }

        for (int i = 0; i < 4; i++) {
            verify(gl).glEnableVertexAttribArray(8 + i);
        }
    }

    @Test
    public void testMultiSlotDoesNotAffectExistingSingleSlotBinding() {
        // First bind Position (3 components) at location 0
        VertexBuffer posVB = createVB(Type.Position, 3, 0);
        renderer.setVertexAttrib(posVB, null);

        // Record how many calls we have after Position binding
        int callsAfterPosition = attribPointerCalls.size();
        assertEquals("Position should use 1 slot", 1, callsAfterPosition);

        long[] posCall = attribPointerCalls.get(0);
        assertEquals("Position at loc 0", 0, posCall[0]);
        assertEquals("Position has 3 components", 3, posCall[1]);

        // Now bind TexCoord2 (8 components) at location 5 — should not touch loc 0
        attribPointerCalls.clear();
        VertexBuffer texVB = createVB(Type.TexCoord2, 8, 5);
        renderer.setVertexAttrib(texVB, null);

        assertEquals("TexCoord2 should use 2 slots", 2, attribPointerCalls.size());
        // Verify none of the new calls touched location 0
        for (long[] call : attribPointerCalls) {
            assertTrue("Multi-slot binding should not touch Position's location (0), but touched loc " + call[0],
                    call[0] >= 5);
        }
    }

    @Test
    public void testInstancedMultiSlotTexCoord_setsDivisorOnAllSlots() {
        // An instanced 8-component TexCoord2 should set the divisor on both attribute slots
        VertexBuffer vb = createVB(Type.TexCoord2, 8, 5);
        vb.setInstanced(true);

        renderer.setVertexAttrib(vb, null);

        // Both slots (5 and 6) should have divisor set to 1
        assertEquals("Expected 2 divisor calls for 2-slot instanced buffer",
                2, divisorCalls.size());

        int[] div0 = divisorCalls.get(0);
        assertEquals(5, div0[0]);  // slot
        assertEquals(1, div0[1]);  // divisor (instanceSpan = 1)

        int[] div1 = divisorCalls.get(1);
        assertEquals(6, div1[0]);  // slot
        assertEquals(1, div1[1]);  // divisor
    }

    @Test
    public void testSingleSlotStillWorks() {
        // Sanity: a standard 3-component Position buffer should still bind normally
        VertexBuffer vb = createVB(Type.Position, 3, 0);

        renderer.setVertexAttrib(vb, null);

        assertEquals(1, attribPointerCalls.size());
        long[] call = attribPointerCalls.get(0);
        assertEquals(0, call[0]);  // location
        assertEquals(3, call[1]);  // 3 components
        assertEquals(0, call[2]);  // stride 0 (tightly packed)
        assertEquals(0, call[3]);  // offset 0
    }

    @Test
    public void testTwoMultiSlotBuffersBoundSimultaneously() {
        // Bind TexCoord2 (8 components) at loc 5 and TexCoord3 (12 components) at loc 10
        VertexBuffer tc2 = createVB(Type.TexCoord2, 8, 5);
        VertexBuffer tc3 = createVB(Type.TexCoord3, 12, 10);

        renderer.setVertexAttrib(tc2, null);
        renderer.setVertexAttrib(tc3, null);

        // tc2: 2 slots at 5,6; tc3: 3 slots at 10,11,12 = 5 total calls
        assertEquals(5, attribPointerCalls.size());

        // Verify tc2 slots
        assertEquals(5, attribPointerCalls.get(0)[0]);
        assertEquals(6, attribPointerCalls.get(1)[0]);

        // Verify tc3 slots
        assertEquals(10, attribPointerCalls.get(2)[0]);
        assertEquals(11, attribPointerCalls.get(3)[0]);
        assertEquals(12, attribPointerCalls.get(4)[0]);

        // tc3 stride should be 4*4*3=48 (3 slots)
        for (int i = 2; i < 5; i++) {
            assertEquals(48, attribPointerCalls.get(i)[2]);
        }

        // Verify all 5 attribute arrays were enabled
        verify(gl).glEnableVertexAttribArray(5);
        verify(gl).glEnableVertexAttribArray(6);
        verify(gl).glEnableVertexAttribArray(10);
        verify(gl).glEnableVertexAttribArray(11);
        verify(gl).glEnableVertexAttribArray(12);
    }

    @Test
    public void testClearVertexAttribs_disablesAllMultiSlots() {
        // Bind an instanced 8-component buffer, then clear
        VertexBuffer vb = createVB(Type.TexCoord2, 8, 5);
        vb.setInstanced(true);

        renderer.setVertexAttrib(vb, null);

        // clearVertexAttribs moves the "new" list to "old" — we need to
        // call it once to populate oldList, then again to trigger cleanup.
        // First call: copies new -> old (slots 5,6 are now in old list)
        renderer.clearVertexAttribs();
        // Second call: old list has 5,6, new list is empty -> disables them
        renderer.clearVertexAttribs();

        // Both slots should have been disabled
        verify(gl).glDisableVertexAttribArray(5);
        verify(gl).glDisableVertexAttribArray(6);

        // Both instanced slots should have had their divisor reset to 0
        // (first 2 calls set divisor=1, next 2 reset to 0)
        verify(glext, times(2)).glVertexAttribDivisorARB(eq(5), anyInt());
        verify(glext, times(2)).glVertexAttribDivisorARB(eq(6), anyInt());

        // Verify the reset calls specifically
        verify(glext).glVertexAttribDivisorARB(5, 0);
        verify(glext).glVertexAttribDivisorARB(6, 0);
    }

    @Test
    public void testMultiSlotInstancedToNonInstanced() {
        // Bind an instanced 8-component TexCoord2, then rebind the same
        // type as non-instanced — divisor should be reset to 0 on all slots
        VertexBuffer instancedVB = createVB(Type.TexCoord2, 8, 5);
        instancedVB.setInstanced(true);
        renderer.setVertexAttrib(instancedVB, null);

        divisorCalls.clear();
        attribPointerCalls.clear();

        // Now bind a non-instanced buffer at the same location
        // We need a different VB object so the renderer detects the change
        VertexBuffer nonInstancedVB = new VertexBuffer(Type.TexCoord2);
        FloatBuffer fb = BufferUtils.createFloatBuffer(3 * 8);
        nonInstancedVB.setupData(Usage.Static, 8, Format.Float, fb);
        // Reuse the same shader attribute location
        renderer.setVertexAttrib(nonInstancedVB, null);

        // Should have 2 glVertexAttribPointer calls for the new binding
        assertEquals(2, attribPointerCalls.size());

        // Divisor should be reset to 0 on both slots
        assertEquals(2, divisorCalls.size());
        assertEquals(5, divisorCalls.get(0)[0]);
        assertEquals(0, divisorCalls.get(0)[1]);
        assertEquals(6, divisorCalls.get(1)[0]);
        assertEquals(0, divisorCalls.get(1)[1]);
    }
}
