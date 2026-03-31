package com.jme3.renderer.indirect;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.shader.VarType;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

public class DrawDataSerializerTest {

    @Test
    public void testSerializeWorldMatrixAndFloat() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .addField("Roughness", VarType.Float)
                .build();

        Matrix4f worldMatrix = new Matrix4f();
        worldMatrix.setTranslation(1f, 2f, 3f);

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        DrawDataSerializer.serialize(layout, buf, 0, worldMatrix, (name) -> {
            if ("Roughness".equals(name)) return 0.75f;
            return null;
        });

        // Column 3 (translation) at offset 48 (col-major: col3 = 3*16=48)
        buf.position(48);
        assertEquals(1f, buf.getFloat(), 0.001f);
        assertEquals(2f, buf.getFloat(), 0.001f);
        assertEquals(3f, buf.getFloat(), 0.001f);

        // Roughness at offset 64
        buf.position(64);
        assertEquals(0.75f, buf.getFloat(), 0.001f);
    }

    @Test
    public void testSerializeVec4AsColorRGBA() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("BaseColor", VarType.Vector4)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> {
            if ("BaseColor".equals(name)) return new ColorRGBA(0.1f, 0.2f, 0.3f, 1.0f);
            return null;
        });

        buf.position(0);
        assertEquals(0.1f, buf.getFloat(), 0.001f);
        assertEquals(0.2f, buf.getFloat(), 0.001f);
        assertEquals(0.3f, buf.getFloat(), 0.001f);
        assertEquals(1.0f, buf.getFloat(), 0.001f);
    }

    @Test
    public void testSerializeTextureHandle() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("AlbedoMap", VarType.Texture2D)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        long handle = 0x12345678ABCDEF00L;
        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> {
            if ("AlbedoMap".equals(name)) return handle;
            return null;
        });

        buf.position(0);
        assertEquals(handle, buf.getLong());
    }

    @Test
    public void testSerializeNullTextureWritesZero() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("NormalMap", VarType.Texture2D)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());
        buf.putLong(0, 0xFFFFFFFFFFFFFFFFL);

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> null);

        buf.position(0);
        assertEquals(0L, buf.getLong());
    }

    @Test
    public void testSerializeMultipleDrawEntries() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .addField("Color", VarType.Vector4)
                .build();

        int stride = layout.getStride();
        ByteBuffer buf = ByteBuffer.allocateDirect(stride * 2).order(ByteOrder.nativeOrder());

        // Draw 0: identity matrix, red
        DrawDataSerializer.serialize(layout, buf, 0, Matrix4f.IDENTITY, (name) -> {
            if ("Color".equals(name)) return new ColorRGBA(1, 0, 0, 1);
            return null;
        });

        // Draw 1: translated matrix, green
        Matrix4f mat1 = new Matrix4f();
        mat1.setTranslation(5, 0, 0);
        DrawDataSerializer.serialize(layout, buf, stride, mat1, (name) -> {
            if ("Color".equals(name)) return new ColorRGBA(0, 1, 0, 1);
            return null;
        });

        // Verify draw 0 color at offset 64
        buf.position(64);
        assertEquals(1f, buf.getFloat(), 0.001f); // R
        assertEquals(0f, buf.getFloat(), 0.001f); // G

        // Verify draw 1 color at offset stride + 64
        buf.position(stride + 64);
        assertEquals(0f, buf.getFloat(), 0.001f); // R
        assertEquals(1f, buf.getFloat(), 0.001f); // G

        // Verify draw 1 world matrix translation (column 3 at stride+48)
        buf.position(stride + 48);
        assertEquals(5f, buf.getFloat(), 0.001f); // tx
    }

    @Test
    public void testSerializeVec2() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("TexScale", VarType.Vector2)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> {
            if ("TexScale".equals(name)) return new Vector2f(2.0f, 3.0f);
            return null;
        });

        buf.position(0);
        assertEquals(2.0f, buf.getFloat(), 0.001f);
        assertEquals(3.0f, buf.getFloat(), 0.001f);
    }

    @Test
    public void testSerializeVec3() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("Normal", VarType.Vector3)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> {
            if ("Normal".equals(name)) return new Vector3f(0, 1, 0);
            return null;
        });

        buf.position(0);
        assertEquals(0f, buf.getFloat(), 0.001f);
        assertEquals(1f, buf.getFloat(), 0.001f);
        assertEquals(0f, buf.getFloat(), 0.001f);
    }

    @Test
    public void testSerializeInt() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("MaterialId", VarType.Int)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> {
            if ("MaterialId".equals(name)) return 42;
            return null;
        });

        buf.position(0);
        assertEquals(42, buf.getInt());
    }

    @Test
    public void testSerializeBoolean() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("UseAlpha", VarType.Boolean)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> {
            if ("UseAlpha".equals(name)) return true;
            return null;
        });

        buf.position(0);
        assertEquals(1, buf.getInt()); // true -> 1

        // Test false
        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> {
            if ("UseAlpha".equals(name)) return false;
            return null;
        });

        buf.position(0);
        assertEquals(0, buf.getInt()); // false -> 0
    }

    @Test
    public void testSerializeNullDefaultsToZero() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("Value", VarType.Float)
                .addField("Color", VarType.Vector4)
                .addField("Tex", VarType.Texture2D)
                .build();

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());
        // Fill with non-zero
        for (int i = 0; i < buf.capacity(); i++) buf.put(i, (byte) 0xFF);

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> null);

        // Float should be 0
        buf.position(0);
        assertEquals(0f, buf.getFloat(), 0.001f);

        // Vec4 should be all zeros
        buf.position(layout.getField(1).getOffset());
        assertEquals(0f, buf.getFloat(), 0.001f);
        assertEquals(0f, buf.getFloat(), 0.001f);
        assertEquals(0f, buf.getFloat(), 0.001f);
        assertEquals(0f, buf.getFloat(), 0.001f);

        // Texture handle should be 0
        buf.position(layout.getField(2).getOffset());
        assertEquals(0L, buf.getLong());
    }
}
