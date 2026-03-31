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

import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix3f;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.shader.VarType;

import java.nio.ByteBuffer;
import java.util.function.Function;

/**
 * Serializes per-draw data into a ByteBuffer region following std430 layout.
 * <p>
 * Used by {@link IndirectBatch} and MdiNode to populate the DrawData SSBO.
 * Material parameter values are resolved via a lookup function rather than
 * requiring a direct Material reference, allowing flexible use from both
 * scene-graph-managed and manual rendering paths.
 */
public class DrawDataSerializer {

    /**
     * Writes one draw entry into the buffer at the given byte offset.
     *
     * @param layout       the DrawData layout describing field positions
     * @param buffer       the destination ByteBuffer (must be in native byte order)
     * @param baseOffset   byte offset for this draw entry (drawIndex * stride)
     * @param worldMatrix  the world matrix (may be null if layout has no WorldMatrix field)
     * @param paramLookup  function that returns a material param value by name, or null.
     *                     For texture params, should return {@code Long} (bindless handle)
     *                     or null (no texture -> handle 0).
     */
    public static void serialize(DrawDataLayout layout, ByteBuffer buffer, int baseOffset,
                                 Matrix4f worldMatrix, Function<String, Object> paramLookup) {
        for (DrawDataField field : layout.getFields()) {
            int pos = baseOffset + field.getOffset();
            buffer.position(pos);

            if (field.isWorldMatrix()) {
                writeMat4(buffer, worldMatrix != null ? worldMatrix : Matrix4f.IDENTITY);
                continue;
            }

            Object value = paramLookup.apply(field.getName());
            writeField(buffer, field.getVarType(), value);
        }
    }

    private static void writeField(ByteBuffer buf, VarType type, Object value) {
        switch (type) {
            case Float:
                buf.putFloat(value != null ? ((Number) value).floatValue() : 0f);
                break;
            case Int:
                buf.putInt(value != null ? ((Number) value).intValue() : 0);
                break;
            case Boolean:
                buf.putInt(value != null && (java.lang.Boolean) value ? 1 : 0);
                break;
            case Vector2:
                if (value instanceof Vector2f) {
                    Vector2f v = (Vector2f) value;
                    buf.putFloat(v.x).putFloat(v.y);
                } else {
                    buf.putFloat(0).putFloat(0);
                }
                break;
            case Vector3:
                if (value instanceof Vector3f) {
                    Vector3f v = (Vector3f) value;
                    buf.putFloat(v.x).putFloat(v.y).putFloat(v.z);
                } else {
                    buf.putFloat(0).putFloat(0).putFloat(0);
                }
                break;
            case Vector4:
                if (value instanceof ColorRGBA) {
                    ColorRGBA c = (ColorRGBA) value;
                    buf.putFloat(c.r).putFloat(c.g).putFloat(c.b).putFloat(c.a);
                } else if (value instanceof Vector4f) {
                    Vector4f v = (Vector4f) value;
                    buf.putFloat(v.x).putFloat(v.y).putFloat(v.z).putFloat(v.w);
                } else {
                    buf.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                }
                break;
            case Matrix3:
                writeMat3(buf, value != null ? (Matrix3f) value : Matrix3f.IDENTITY);
                break;
            case Matrix4:
                writeMat4(buf, value != null ? (Matrix4f) value : Matrix4f.IDENTITY);
                break;
            case Texture2D:
            case Texture3D:
            case TextureArray:
            case TextureCubeMap:
                // Bindless handle as long (uvec2). Null -> 0 (no texture).
                long handle = 0L;
                if (value instanceof Long) {
                    handle = (Long) value;
                }
                buf.putLong(handle);
                break;
            default:
                throw new IllegalArgumentException("Unsupported DrawData VarType: " + type);
        }
    }

    /** Writes mat4 in column-major order (std430). */
    private static void writeMat4(ByteBuffer buf, Matrix4f m) {
        buf.putFloat(m.m00).putFloat(m.m10).putFloat(m.m20).putFloat(m.m30);
        buf.putFloat(m.m01).putFloat(m.m11).putFloat(m.m21).putFloat(m.m31);
        buf.putFloat(m.m02).putFloat(m.m12).putFloat(m.m22).putFloat(m.m32);
        buf.putFloat(m.m03).putFloat(m.m13).putFloat(m.m23).putFloat(m.m33);
    }

    /** Writes mat3 as 3 columns, each padded to vec4 (std430 column alignment). */
    private static void writeMat3(ByteBuffer buf, Matrix3f m) {
        // Column 0
        buf.putFloat(m.get(0, 0)).putFloat(m.get(1, 0)).putFloat(m.get(2, 0)).putFloat(0);
        // Column 1
        buf.putFloat(m.get(0, 1)).putFloat(m.get(1, 1)).putFloat(m.get(2, 1)).putFloat(0);
        // Column 2
        buf.putFloat(m.get(0, 2)).putFloat(m.get(1, 2)).putFloat(m.get(2, 2)).putFloat(0);
    }
}
