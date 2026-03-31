package com.jme3.renderer.indirect;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.shader.VarType;
import com.jme3.system.TestUtil;
import org.junit.Test;

import static org.junit.Assert.*;

public class UnshadedMdiTest {

    @Test
    public void testUnshadedMdiLoads() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/UnshadedMdi.j3md");
        assertNotNull(mat);
        assertEquals("UnshadedMdi", mat.getMaterialDef().getName());
    }

    @Test
    public void testUnshadedMdiDrawDataLayout() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/UnshadedMdi.j3md");

        DrawDataLayout layout = mat.getMaterialDef().getDrawDataLayout();
        assertNotNull(layout);
        assertEquals(3, layout.getFieldCount());

        // WorldMatrix
        assertTrue(layout.getField(0).isWorldMatrix());
        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(64, layout.getField(0).getSize());

        // Color (vec4)
        assertEquals("Color", layout.getField(1).getName());
        assertEquals(VarType.Vector4, layout.getField(1).getVarType());
        assertEquals(64, layout.getField(1).getOffset());

        // ColorMap (Texture2D -> uvec2)
        assertEquals("ColorMap", layout.getField(2).getName());
        assertEquals(VarType.Texture2D, layout.getField(2).getVarType());
        assertEquals(80, layout.getField(2).getOffset());
        assertEquals(8, layout.getField(2).getSize());

        // Stride: 88 padded to 16 = 96
        assertEquals(96, layout.getStride());
    }

    @Test
    public void testUnshadedMdiSetParams() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/UnshadedMdi.j3md");

        // Should be able to set Color without error
        mat.setColor("Color", ColorRGBA.Red);
        assertEquals(ColorRGBA.Red, mat.getParamValue("Color"));
    }

    @Test
    public void testUnshadedMdiHasDrawDataSsboParam() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/UnshadedMdi.j3md");

        // Should have DrawData SSBO param declared
        assertNotNull(mat.getMaterialDef().getMaterialParam("DrawData"));
        assertEquals(VarType.ShaderStorageBufferObject,
                mat.getMaterialDef().getMaterialParam("DrawData").getVarType());
    }
}
