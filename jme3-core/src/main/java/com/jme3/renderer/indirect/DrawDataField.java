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

import com.jme3.shader.VarType;

/**
 * A single field within a {@link DrawDataLayout}.
 * <p>
 * Each field maps to either a material parameter (looked up by name) or the
 * special {@code WorldMatrix} built-in (read from the geometry's world transform).
 * Texture types are stored as bindless handles (uvec2, 8 bytes).
 */
public class DrawDataField {

    private final String name;
    private final VarType varType;
    private final int offset;
    private final int size;
    private final int alignment;
    private final boolean worldMatrix;

    DrawDataField(String name, VarType varType, int offset, int size, int alignment, boolean worldMatrix) {
        this.name = name;
        this.varType = varType;
        this.offset = offset;
        this.size = size;
        this.alignment = alignment;
        this.worldMatrix = worldMatrix;
    }

    /** The material parameter name, or "WorldMatrix" for the built-in. */
    public String getName() { return name; }

    /** The declared VarType from the material parameter. */
    public VarType getVarType() { return varType; }

    /** Byte offset within the per-draw struct (std430). */
    public int getOffset() { return offset; }

    /** Size in bytes of this field in the SSBO. */
    public int getSize() { return size; }

    /** std430 alignment requirement in bytes. */
    public int getAlignment() { return alignment; }

    /** True if this is the special WorldMatrix field (read from geometry transform). */
    public boolean isWorldMatrix() { return worldMatrix; }
}
