package com.jme3.renderer.indirect;

import com.jme3.shader.VarType;
import org.junit.Test;
import static org.junit.Assert.*;

public class DrawDataLayoutTest {

    @Test
    public void testSingleFloat() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("value", VarType.Float)
                .build();

        assertEquals(1, layout.getFieldCount());
        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(4, layout.getField(0).getSize());
        assertEquals(4, layout.getStride());
    }

    @Test
    public void testMat4ThenFloat() {
        // mat4 = 64 bytes at offset 0, float = 4 bytes at offset 64
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("worldMatrix", VarType.Matrix4)
                .addField("roughness", VarType.Float)
                .build();

        assertEquals(2, layout.getFieldCount());
        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(64, layout.getField(0).getSize());
        assertEquals(64, layout.getField(1).getOffset());
        assertEquals(4, layout.getField(1).getSize());
        // stride = 68, padded to max alignment (16 for mat4) = 80
        assertEquals(80, layout.getStride());
    }

    @Test
    public void testVec4ThenFloatThenVec2() {
        // vec4 at 0 (16), float at 16 (4), vec2 at 24 (8, needs 8-byte align)
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("color", VarType.Vector4)
                .addField("roughness", VarType.Float)
                .addField("uv", VarType.Vector2)
                .build();

        assertEquals(0, layout.getField(0).getOffset());   // vec4
        assertEquals(16, layout.getField(1).getOffset());   // float
        assertEquals(24, layout.getField(2).getOffset());   // vec2 (aligned to 8)
        assertEquals(32, layout.getStride());               // 24+8=32, already aligned to 16
    }

    @Test
    public void testTexture2dBecomesUvec2() {
        // Texture2D in DrawData = bindless handle = uvec2 = 8 bytes, 8-byte align
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("albedoMap", VarType.Texture2D)
                .build();

        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(8, layout.getField(0).getSize());
        assertEquals(8, layout.getStride());
    }

    @Test
    public void testWorldMatrixSpecialField() {
        // "WorldMatrix" is a reserved name - type is always mat4
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .addField("color", VarType.Vector4)
                .build();

        DrawDataField wmField = layout.getField(0);
        assertTrue(wmField.isWorldMatrix());
        assertEquals(0, wmField.getOffset());
        assertEquals(64, wmField.getSize());
        assertEquals(64, layout.getField(1).getOffset());
        assertEquals(80, layout.getStride()); // 64+16=80, aligned to 16
    }

    @Test
    public void testFindFieldByName() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .addField("BaseColor", VarType.Vector4)
                .addField("Roughness", VarType.Float)
                .build();

        assertNotNull(layout.findField("BaseColor"));
        assertEquals(VarType.Vector4, layout.findField("BaseColor").getVarType());
        assertNull(layout.findField("NonExistent"));
    }

    @Test
    public void testVec3Alignment() {
        // vec3 has 16-byte alignment in std430 but only 12 bytes of data
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("normal", VarType.Vector3)
                .addField("roughness", VarType.Float)
                .build();

        assertEquals(0, layout.getField(0).getOffset());   // vec3 at 0
        assertEquals(12, layout.getField(0).getSize());     // 12 bytes
        assertEquals(12, layout.getField(1).getOffset());   // float at 12 (4-byte align, fits)
        assertEquals(16, layout.getStride());               // padded to 16 (max align)
    }

    @Test
    public void testMat3Size() {
        // mat3 = 3 columns of vec4-padded vec3 = 48 bytes
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("normalMatrix", VarType.Matrix3)
                .build();

        assertEquals(48, layout.getField(0).getSize());
        assertEquals(48, layout.getStride()); // 48 padded to 16 = 48
    }

    @Test
    public void testComplexLayout() {
        // Realistic PBR-like layout
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()                            // offset 0, size 64
                .addField("BaseColor", VarType.Vector4)      // offset 64, size 16
                .addField("Metallic", VarType.Float)         // offset 80, size 4
                .addField("Roughness", VarType.Float)        // offset 84, size 4
                .addField("BaseColorMap", VarType.Texture2D) // offset 88, size 8
                .addField("NormalMap", VarType.Texture2D)    // offset 96, size 8
                .build();

        assertEquals(6, layout.getFieldCount());
        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(64, layout.getField(1).getOffset());
        assertEquals(80, layout.getField(2).getOffset());
        assertEquals(84, layout.getField(3).getOffset());
        assertEquals(88, layout.getField(4).getOffset());
        assertEquals(96, layout.getField(5).getOffset());
        // 96 + 8 = 104, padded to 16 = 112
        assertEquals(112, layout.getStride());
    }
}
