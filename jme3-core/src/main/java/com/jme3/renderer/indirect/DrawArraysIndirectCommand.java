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
package com.jme3.renderer.indirect;

/**
 * Represents a single GL DrawArraysIndirect command.
 * Matches the layout of the OpenGL DrawArraysIndirectCommand struct:
 * <pre>
 * typedef struct {
 *     uint count;         // number of vertices
 *     uint instanceCount; // number of instances
 *     uint first;         // first vertex
 *     uint baseInstance;  // base instance for instanced vertex attribs
 * } DrawArraysIndirectCommand;
 * </pre>
 * Total size: 16 bytes (4 x 4-byte unsigned integers).
 */
public class DrawArraysIndirectCommand {

    /** Number of vertices to draw per instance. */
    public int count;

    /** Number of instances to draw. */
    public int instanceCount;

    /** Index of the first vertex to draw. */
    public int first;

    /** Base instance for instanced vertex attributes. */
    public int baseInstance;

    /** Size in bytes of this command struct. */
    public static final int STRIDE = 16;

    public DrawArraysIndirectCommand() {
    }

    public DrawArraysIndirectCommand(int count, int instanceCount, int first, int baseInstance) {
        this.count = count;
        this.instanceCount = instanceCount;
        this.first = first;
        this.baseInstance = baseInstance;
    }
}
