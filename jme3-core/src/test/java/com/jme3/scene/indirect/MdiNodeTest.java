package com.jme3.scene.indirect;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.indirect.DrawDataLayout;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.TestUtil;
import org.junit.Test;

import static org.junit.Assert.*;

public class MdiNodeTest {

    @Test
    public void testBatchGroupsGeometries() {
        AssetManager assetManager = TestUtil.createAssetManager();

        MdiNode mdiNode = new MdiNode("testMdi");

        Material mat1 = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat1.setColor("Color", ColorRGBA.Red);

        Material mat2 = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat2.setColor("Color", ColorRGBA.Green);

        Geometry box = new Geometry("box", new Box(1, 1, 1));
        box.setMaterial(mat1);
        mdiNode.attachChild(box);

        Geometry sphere = new Geometry("sphere", new Sphere(8, 8, 1f));
        sphere.setMaterial(mat2);
        mdiNode.attachChild(sphere);

        // Before batching - should have 2 children (box, sphere)
        assertEquals(2, mdiNode.getChildren().size());

        mdiNode.batch();

        // After batching - should have 2 original + 1 MdiGeometry batch
        // (original geometries stay as children but are grouped, the MdiGeometry is added)
        boolean hasMdiGeom = false;
        for (int i = 0; i < mdiNode.getChildren().size(); i++) {
            if (mdiNode.getChildren().get(i) instanceof MdiGeometry) {
                hasMdiGeom = true;
                MdiGeometry mdiGeom = (MdiGeometry) mdiNode.getChildren().get(i);
                assertEquals(2, mdiGeom.getDrawCount());
                assertNotNull(mdiGeom.getMaterial());
                break;
            }
        }
        assertTrue("MdiNode should create an MdiGeometry batch", hasMdiGeom);
    }

    @Test
    public void testGroupedGeometriesAreAssociated() {
        AssetManager assetManager = TestUtil.createAssetManager();

        MdiNode mdiNode = new MdiNode("testMdi");

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");

        Geometry box = new Geometry("box", new Box(1, 1, 1));
        box.setMaterial(mat);
        mdiNode.attachChild(box);

        mdiNode.batch();

        assertTrue("Geometry should be grouped after batch()", box.isGrouped());
    }

    @Test
    public void testNestedGeometries() {
        AssetManager assetManager = TestUtil.createAssetManager();

        MdiNode mdiNode = new MdiNode("testMdi");

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");

        // Create nested structure
        Node subNode = new Node("sub");
        Geometry box = new Geometry("box", new Box(1, 1, 1));
        box.setMaterial(mat);
        subNode.attachChild(box);
        mdiNode.attachChild(subNode);

        Geometry sphere = new Geometry("sphere", new Sphere(8, 8, 1f));
        sphere.setMaterial(mat);
        mdiNode.attachChild(sphere);

        mdiNode.batch();

        assertTrue("Nested geometry should be grouped", box.isGrouped());
        assertTrue("Direct child geometry should be grouped", sphere.isGrouped());
    }

    @Test
    public void testStandardUnshadedWorksWithMdiNode() {
        AssetManager assetManager = TestUtil.createAssetManager();

        MdiNode mdiNode = new MdiNode("testMdi");

        // Standard Unshaded.j3md now has a DrawData block
        Material unshadedMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        unshadedMat.setColor("Color", ColorRGBA.Blue);

        Geometry box = new Geometry("box", new Box(1, 1, 1));
        box.setMaterial(unshadedMat);
        mdiNode.attachChild(box);

        mdiNode.batch();

        assertTrue("Standard Unshaded geometry should be grouped by MdiNode", box.isGrouped());
    }

    @Test
    public void testBatchMaterialHasDrawDataSsbo() {
        AssetManager assetManager = TestUtil.createAssetManager();

        MdiNode mdiNode = new MdiNode("testMdi");

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.Red);

        Geometry box = new Geometry("box", new Box(1, 1, 1));
        box.setMaterial(mat);
        mdiNode.attachChild(box);

        mdiNode.batch();

        // Find the MdiGeometry and check its material has DrawData SSBO
        for (int i = 0; i < mdiNode.getChildren().size(); i++) {
            if (mdiNode.getChildren().get(i) instanceof MdiGeometry) {
                MdiGeometry mdiGeom = (MdiGeometry) mdiNode.getChildren().get(i);
                assertNotNull("Batch material should have DrawData SSBO param",
                        mdiGeom.getMaterial().getParam("DrawData"));
                return;
            }
        }
        fail("Should have found an MdiGeometry child");
    }

    @Test
    public void testDrawDataLayoutFromMatDef() {
        AssetManager assetManager = TestUtil.createAssetManager();
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        DrawDataLayout layout = mat.getMaterialDef().getDrawDataLayout();

        assertNotNull(layout);
        assertEquals(3, layout.getFieldCount());
        assertTrue(layout.getField(0).isWorldMatrix());
    }
}
