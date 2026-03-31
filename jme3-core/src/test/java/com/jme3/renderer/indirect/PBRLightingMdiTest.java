package com.jme3.renderer.indirect;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.shader.VarType;
import com.jme3.system.TestUtil;
import org.junit.Test;

import static org.junit.Assert.*;

public class PBRLightingMdiTest {

    @Test
    public void testPBRLightingMdiLoads() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Light/PBRLightingMdi.j3md");
        assertNotNull(mat);
        assertEquals("PBRLightingMdi", mat.getMaterialDef().getName());
    }

    @Test
    public void testPBRLightingMdiDrawDataLayout() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Light/PBRLightingMdi.j3md");

        DrawDataLayout layout = mat.getMaterialDef().getDrawDataLayout();
        assertNotNull(layout);
        assertEquals(10, layout.getFieldCount());

        // Field 0: WorldMatrix (mat4, offset 0, size 64)
        assertTrue(layout.getField(0).isWorldMatrix());
        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(64, layout.getField(0).getSize());

        // Field 1: BaseColor (vec4, offset 64, size 16)
        assertEquals("BaseColor", layout.getField(1).getName());
        assertEquals(64, layout.getField(1).getOffset());
        assertEquals(16, layout.getField(1).getSize());

        // Field 2: Metallic (float, offset 80, size 4)
        assertEquals("Metallic", layout.getField(2).getName());
        assertEquals(80, layout.getField(2).getOffset());
        assertEquals(4, layout.getField(2).getSize());

        // Field 3: Roughness (float, offset 84, size 4)
        assertEquals("Roughness", layout.getField(3).getName());
        assertEquals(84, layout.getField(3).getOffset());
        assertEquals(4, layout.getField(3).getSize());

        // Field 4: Emissive (vec4, offset 96, aligned to 16, size 16)
        assertEquals("Emissive", layout.getField(4).getName());
        assertEquals(96, layout.getField(4).getOffset());
        assertEquals(16, layout.getField(4).getSize());

        // Field 5: EmissiveIntensity (float, offset 112, size 4)
        assertEquals("EmissiveIntensity", layout.getField(5).getName());
        assertEquals(112, layout.getField(5).getOffset());
        assertEquals(4, layout.getField(5).getSize());

        // Field 6: BaseColorMap (uvec2, offset 120, aligned to 8, size 8)
        assertEquals("BaseColorMap", layout.getField(6).getName());
        assertEquals(120, layout.getField(6).getOffset());
        assertEquals(8, layout.getField(6).getSize());

        // Field 7: NormalMap (uvec2, offset 128, size 8)
        assertEquals("NormalMap", layout.getField(7).getName());
        assertEquals(128, layout.getField(7).getOffset());

        // Field 8: MetallicRoughnessMap (uvec2, offset 136, size 8)
        assertEquals("MetallicRoughnessMap", layout.getField(8).getName());
        assertEquals(136, layout.getField(8).getOffset());

        // Field 9: EmissiveMap (uvec2, offset 144, size 8)
        assertEquals("EmissiveMap", layout.getField(9).getName());
        assertEquals(144, layout.getField(9).getOffset());

        // Stride: 144 + 8 = 152, padded to 16 = 160
        assertEquals(160, layout.getStride());
    }

    @Test
    public void testPBRLightingMdiSetParams() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Light/PBRLightingMdi.j3md");

        mat.setColor("BaseColor", ColorRGBA.Red);
        mat.setFloat("Metallic", 0.8f);
        mat.setFloat("Roughness", 0.2f);
        mat.setColor("Emissive", new ColorRGBA(0.5f, 0, 0, 1));
        mat.setFloat("EmissiveIntensity", 2.0f);

        assertEquals(ColorRGBA.Red, mat.getParamValue("BaseColor"));
        assertEquals(0.8f, (float) mat.getParamValue("Metallic"), 0.001f);
        assertEquals(0.2f, (float) mat.getParamValue("Roughness"), 0.001f);
    }

    @Test
    public void testGlslStructMatchesLayout() {
        // This test documents the expected GLSL struct offsets so the shader
        // author can verify their struct matches.
        // GLSL struct (std430):
        //   mat4  worldMatrix;            // offset 0,   size 64
        //   vec4  baseColor;              // offset 64,  size 16
        //   float metallic;               // offset 80,  size 4
        //   float roughness;              // offset 84,  size 4
        //   vec4  emissive;               // offset 96,  size 16 (aligned to 16, 8 bytes padding after roughness)
        //   float emissiveIntensity;      // offset 112, size 4
        //   uvec2 baseColorMapHandle;     // offset 120, size 8  (aligned to 8, 4 bytes padding)
        //   uvec2 normalMapHandle;        // offset 128, size 8
        //   uvec2 metallicRoughnessMapHandle; // offset 136, size 8
        //   uvec2 emissiveMapHandle;      // offset 144, size 8
        //   -- stride = 160 (152 padded to 16)

        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Light/PBRLightingMdi.j3md");
        DrawDataLayout layout = mat.getMaterialDef().getDrawDataLayout();

        // These offsets must match the GLSL struct exactly
        int[] expectedOffsets = {0, 64, 80, 84, 96, 112, 120, 128, 136, 144};
        for (int i = 0; i < expectedOffsets.length; i++) {
            assertEquals("Offset mismatch for field " + layout.getField(i).getName(),
                    expectedOffsets[i], layout.getField(i).getOffset());
        }
        assertEquals(160, layout.getStride());
    }
}
