package com.jme3.renderer.indirect;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.scene.Mesh;
import com.jme3.scene.indirect.MdiGeometry;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.function.Function;

import static org.junit.Assert.*;

public class IndirectBatchTest {

    @Test
    public void testBuildCreatesCommandBuffer() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .addField("BaseColor", VarType.Vector4)
                .build();

        IndirectBatch batch = new IndirectBatch(layout);
        batch.addDraw(new Box(1, 1, 1), new Function<String, Object>() {
            @Override public Object apply(String name) {
                if ("BaseColor".equals(name)) return new ColorRGBA(1, 0, 0, 1);
                return null;
            }
        });
        batch.addDraw(new Sphere(8, 8, 1f), new Function<String, Object>() {
            @Override public Object apply(String name) {
                if ("BaseColor".equals(name)) return new ColorRGBA(0, 1, 0, 1);
                return null;
            }
        });
        batch.build();

        assertEquals(2, batch.getDrawCount());
        assertNotNull(batch.getCombinedMesh());
        assertNotNull(batch.getCommandBuffer());
        assertNotNull(batch.getDrawDataBuffer());
    }

    @Test
    public void testUpdateTransform() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .build();

        IndirectBatch batch = new IndirectBatch(layout);
        batch.addDraw(new Box(1, 1, 1), new Function<String, Object>() {
            @Override public Object apply(String name) { return null; }
        });
        batch.build();

        Matrix4f mat = new Matrix4f();
        mat.setTranslation(5, 10, 15);
        batch.setWorldMatrix(0, mat);
        batch.update();

        // Verify the SSBO was updated
        ByteBuffer data = batch.getDrawDataBuffer().getData();
        assertNotNull(data);
        // Column 3 (translation) at offset 48
        data.position(48);
        assertEquals(5f, data.getFloat(), 0.001f);
        assertEquals(10f, data.getFloat(), 0.001f);
        assertEquals(15f, data.getFloat(), 0.001f);
    }

    @Test
    public void testCreateMdiGeometry() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .build();

        IndirectBatch batch = new IndirectBatch(layout);
        batch.addDraw(new Box(1, 1, 1), new Function<String, Object>() {
            @Override public Object apply(String name) { return null; }
        });
        batch.build();

        MdiGeometry geom = batch.createGeometry("test");
        assertNotNull(geom);
        assertEquals("test", geom.getName());
        assertNotNull(geom.getMesh());
        assertNotNull(geom.getCommandBuffer());
        assertEquals(1, geom.getDrawCount());
    }

    @Test(expected = IllegalStateException.class)
    public void testBuildRequiresEntries() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .build();

        IndirectBatch batch = new IndirectBatch(layout);
        batch.build(); // should throw
    }

    @Test(expected = IllegalStateException.class)
    public void testOperationsBeforeBuildThrow() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .build();

        IndirectBatch batch = new IndirectBatch(layout);
        batch.getCombinedMesh(); // should throw
    }

    @Test
    public void testInitialParamsSerialized() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("Color", VarType.Vector4)
                .build();

        IndirectBatch batch = new IndirectBatch(layout);
        batch.addDraw(new Box(1, 1, 1), new Function<String, Object>() {
            @Override public Object apply(String name) {
                if ("Color".equals(name)) return new ColorRGBA(0.5f, 0.6f, 0.7f, 1.0f);
                return null;
            }
        });
        batch.build();

        ByteBuffer data = batch.getDrawDataBuffer().getData();
        data.position(0);
        assertEquals(0.5f, data.getFloat(), 0.001f);
        assertEquals(0.6f, data.getFloat(), 0.001f);
        assertEquals(0.7f, data.getFloat(), 0.001f);
        assertEquals(1.0f, data.getFloat(), 0.001f);
    }
}
