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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes the std430 memory layout of per-draw data for MDI rendering.
 * <p>
 * Built from a sequence of named fields (material parameter names + optional
 * WorldMatrix built-in). Computes byte offsets, sizes, and total stride
 * following GLSL std430 alignment rules.
 * <p>
 * Used by {@link IndirectBatch} and MdiNode to automatically serialize
 * material parameters into an SSBO indexed by {@code gl_DrawID}.
 */
public class DrawDataLayout {

    private final List<DrawDataField> fields;
    private final int stride;

    private DrawDataLayout(List<DrawDataField> fields, int stride) {
        this.fields = Collections.unmodifiableList(fields);
        this.stride = stride;
    }

    /** All fields in declaration order. */
    public List<DrawDataField> getFields() { return fields; }

    /** Number of fields. */
    public int getFieldCount() { return fields.size(); }

    /** Field at the given index. */
    public DrawDataField getField(int index) { return fields.get(index); }

    /** Total bytes per draw entry, padded to the struct's alignment. */
    public int getStride() { return stride; }

    /** Find a field by name, or null if not found. */
    public DrawDataField findField(String name) {
        for (DrawDataField f : fields) {
            if (f.getName().equals(name)) return f;
        }
        return null;
    }

    /**
     * Returns the std430 size in bytes for a VarType.
     * Texture types become uvec2 (bindless handle, 8 bytes).
     */
    public static int sizeOf(VarType type) {
        switch (type) {
            case Float:    return 4;
            case Int:      return 4;
            case Boolean:  return 4;
            case Vector2:  return 8;
            case Vector3:  return 12;
            case Vector4:  return 16;
            case Matrix3:  return 48;  // 3 columns x 16 bytes (vec4-padded)
            case Matrix4:  return 64;  // 4 columns x 16 bytes
            case Texture2D:
            case Texture3D:
            case TextureArray:
            case TextureCubeMap:
                return 8;  // uvec2 bindless handle
            default:
                throw new IllegalArgumentException("Unsupported VarType for DrawData: " + type);
        }
    }

    /**
     * Returns the std430 alignment in bytes for a VarType.
     */
    public static int alignmentOf(VarType type) {
        switch (type) {
            case Float:    return 4;
            case Int:      return 4;
            case Boolean:  return 4;
            case Vector2:  return 8;
            case Vector3:  return 16; // vec3 has vec4 alignment in std430
            case Vector4:  return 16;
            case Matrix3:  return 16; // column alignment
            case Matrix4:  return 16; // column alignment
            case Texture2D:
            case Texture3D:
            case TextureArray:
            case TextureCubeMap:
                return 8;  // uvec2 alignment
            default:
                throw new IllegalArgumentException("Unsupported VarType for DrawData: " + type);
        }
    }

    private static int align(int offset, int alignment) {
        int remainder = offset % alignment;
        return remainder == 0 ? offset : offset + (alignment - remainder);
    }

    /**
     * Builder for constructing a DrawDataLayout.
     */
    public static class Builder {
        private final List<FieldEntry> entries = new ArrayList<>();

        /** Add a field that maps to a material parameter by name. */
        public Builder addField(String paramName, VarType type) {
            entries.add(new FieldEntry(paramName, type, false));
            return this;
        }

        /** Add the special WorldMatrix field (mat4 from geometry transform). */
        public Builder addWorldMatrix() {
            entries.add(new FieldEntry("WorldMatrix", VarType.Matrix4, true));
            return this;
        }

        public DrawDataLayout build() {
            List<DrawDataField> fields = new ArrayList<>(entries.size());
            int offset = 0;
            int maxAlignment = 1;

            for (FieldEntry e : entries) {
                int size = sizeOf(e.type);
                int alignment = alignmentOf(e.type);
                maxAlignment = Math.max(maxAlignment, alignment);

                offset = align(offset, alignment);
                fields.add(new DrawDataField(e.name, e.type, offset, size, alignment, e.worldMatrix));
                offset += size;
            }

            // Stride is padded to struct alignment (max field alignment)
            int stride = align(offset, maxAlignment);
            return new DrawDataLayout(fields, stride);
        }

        private static class FieldEntry {
            final String name;
            final VarType type;
            final boolean worldMatrix;

            FieldEntry(String name, VarType type, boolean worldMatrix) {
                this.name = name;
                this.type = type;
                this.worldMatrix = worldMatrix;
            }
        }
    }
}
