package com.jme3.renderer.indirect;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.shader.VarType;
import com.jme3.system.TestUtil;
import org.junit.Test;

import static org.junit.Assert.*;

public class DrawDataParsingTest {

    @Test
    public void testDrawDataBlockParsed() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "draw-data-test.j3md");
        MaterialDef def = mat.getMaterialDef();

        DrawDataLayout layout = def.getDrawDataLayout();
        assertNotNull("DrawDataLayout should be parsed from DrawData block", layout);
        assertEquals(4, layout.getFieldCount());

        // Field 0: WorldMatrix (special)
        assertTrue(layout.getField(0).isWorldMatrix());
        assertEquals(VarType.Matrix4, layout.getField(0).getVarType());

        // Field 1: BaseColor (Color -> Vector4)
        assertEquals("BaseColor", layout.getField(1).getName());
        assertEquals(VarType.Vector4, layout.getField(1).getVarType());

        // Field 2: Roughness (Float)
        assertEquals("Roughness", layout.getField(2).getName());
        assertEquals(VarType.Float, layout.getField(2).getVarType());

        // Field 3: BaseColorMap (Texture2D -> uvec2 handle)
        assertEquals("BaseColorMap", layout.getField(3).getName());
        assertEquals(VarType.Texture2D, layout.getField(3).getVarType());
    }

    @Test
    public void testUnshadedHasDrawDataBlock() {
        // Standard Unshaded now has a DrawData block for MDI support
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        assertNotNull(mat.getMaterialDef().getDrawDataLayout());
        assertEquals(3, mat.getMaterialDef().getDrawDataLayout().getFieldCount());
    }

    @Test
    public void testDrawDataLayoutOffsets() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "draw-data-test.j3md");
        DrawDataLayout layout = mat.getMaterialDef().getDrawDataLayout();

        // WorldMatrix: mat4 at offset 0, size 64
        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(64, layout.getField(0).getSize());

        // BaseColor: vec4 at offset 64, size 16
        assertEquals(64, layout.getField(1).getOffset());
        assertEquals(16, layout.getField(1).getSize());

        // Roughness: float at offset 80, size 4
        assertEquals(80, layout.getField(2).getOffset());
        assertEquals(4, layout.getField(2).getSize());

        // BaseColorMap: uvec2 at offset 88 (8-byte aligned), size 8
        assertEquals(88, layout.getField(3).getOffset());
        assertEquals(8, layout.getField(3).getSize());

        // Stride: 96 padded to 16 = 96
        assertEquals(96, layout.getStride());
    }
}
