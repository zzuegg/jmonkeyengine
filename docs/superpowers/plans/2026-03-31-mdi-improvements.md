# MDI Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a layered MDI system with a `DrawData` matdef declaration for automatic per-draw SSBO serialization, a mid-level `IndirectBatch` API, and a high-level `MdiNode` that automatically batches child geometries — all supporting custom materials.

**Architecture:** The `DrawData` block in `.j3md` declares which material params are per-draw. The engine computes std430 layout, serializes material params into an SSBO automatically. `MdiGeometry` (like `InstancedGeometry`) overrides the render path to issue MDI calls. `MdiNode` (like `InstancedNode`) extends `GeometryGroupNode` to auto-batch children by material. `IndirectBatch` provides a mid-level manual API.

**Tech Stack:** Java 11, OpenGL 4.3+ (MDI), OpenGL 4.6 (`gl_DrawID`), GLSL 460, bindless textures (ARB_bindless_texture), SSBOs

---

## File Structure

### New files (core)
| File | Responsibility |
|------|---------------|
| `jme3-core/.../renderer/indirect/DrawDataField.java` | Single field in a DrawData layout (name, VarType, std430 offset, size) |
| `jme3-core/.../renderer/indirect/DrawDataLayout.java` | Computed std430 layout from a list of DrawDataFields. Knows stride, can serialize a Geometry's params into a ByteBuffer region. |
| `jme3-core/.../renderer/indirect/IndirectBatch.java` | Mid-level API: wraps MeshCombiner + IndirectCommandBuffer + DrawData SSBO. Users add meshes+materials, call build(), update transforms per frame. |
| `jme3-core/.../scene/indirect/MdiGeometry.java` | Geometry subclass holding combined mesh + IndirectCommandBuffer. Rendering pipeline detects it and issues MDI call instead of regular draw. |
| `jme3-core/.../scene/indirect/MdiNode.java` | GeometryGroupNode subclass that auto-batches children by DrawData layout. |
| `jme3-core/.../scene/indirect/MdiNodeControl.java` | Control that triggers per-frame SSBO updates (like InstancedNodeControl). |

### New files (matdefs & shaders)
| File | Responsibility |
|------|---------------|
| `jme3-core/.../Common/MatDefs/Misc/UnshadedMdi.j3md` | Built-in MDI unshaded matdef with DrawData block |
| `jme3-core/.../Common/ShaderLib/MdiUtils.glsllib` | Shared GLSL utilities: DrawData struct access, bindless texture helpers |
| `jme3-core/.../Common/MatDefs/Misc/UnshadedMdi.vert` | MDI unshaded vertex shader |
| `jme3-core/.../Common/MatDefs/Misc/UnshadedMdi.frag` | MDI unshaded fragment shader |

### New files (tests)
| File | Responsibility |
|------|---------------|
| `jme3-core/.../test/.../indirect/DrawDataLayoutTest.java` | Unit tests for std430 layout computation |
| `jme3-core/.../test/.../indirect/IndirectBatchTest.java` | Unit tests for IndirectBatch serialization |
| `jme3-screenshot-tests/.../indirect/TestMdiNode.java` | Integration test for MdiNode scene graph |

### Modified files
| File | Change |
|------|--------|
| `jme3-core/.../material/MaterialDef.java` | Add `drawDataFieldNames` list storage + getter/setter |
| `jme3-core/.../material/plugins/J3MLoader.java:820-827` | Parse `DrawData` block alongside `MaterialParameters` and `Technique` |
| `jme3-core/.../material/logic/DefaultTechniqueDefLogic.java:63-75` | Add `MdiGeometry` branch in `renderMeshFromGeometry()` |

---

## Task 1: DrawDataField and DrawDataLayout

Core data structures for computing std430 layout from material param types.

**Files:**
- Create: `jme3-core/src/main/java/com/jme3/renderer/indirect/DrawDataField.java`
- Create: `jme3-core/src/main/java/com/jme3/renderer/indirect/DrawDataLayout.java`
- Create: `jme3-core/src/test/java/com/jme3/renderer/indirect/DrawDataLayoutTest.java`

### std430 alignment rules (reference for implementation)

| GLSL type | Size (bytes) | Alignment (bytes) |
|-----------|-------------|-------------------|
| float     | 4           | 4                 |
| int       | 4           | 4                 |
| bool      | 4           | 4                 |
| vec2      | 8           | 8                 |
| vec3      | 12          | 16                |
| vec4      | 16          | 16                |
| mat3      | 48          | 16 (3 × vec4-padded columns) |
| mat4      | 64          | 16 (4 × vec4 columns) |
| uvec2     | 8           | 8 (bindless texture handle) |

Note: std430 differs from std140 in that arrays and structs are NOT rounded up to vec4. But vec3 still has vec4 alignment.

- [ ] **Step 1: Write failing tests for DrawDataLayout**

```java
package com.jme3.renderer.indirect;

import com.jme3.shader.VarType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DrawDataLayoutTest {

    @Test
    public void testSingleFloat() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addField("value", VarType.Float)
                .build();

        assertEquals(1, layout.getFieldCount());
        assertEquals(0, layout.getField(0).getOffset());
        assertEquals(4, layout.getField(0).getSize());
        assertEquals(4, layout.getStride()); // padded to alignment
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :jme3-core:test --tests "com.jme3.renderer.indirect.DrawDataLayoutTest" --info`
Expected: Compilation failure — classes don't exist yet.

- [ ] **Step 3: Implement DrawDataField**

```java
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
```

- [ ] **Step 4: Implement DrawDataLayout**

```java
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
            case Matrix3:  return 48;  // 3 columns × 16 bytes (vec4-padded)
            case Matrix4:  return 64;  // 4 columns × 16 bytes
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :jme3-core:test --tests "com.jme3.renderer.indirect.DrawDataLayoutTest" --info`
Expected: All 6 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add jme3-core/src/main/java/com/jme3/renderer/indirect/DrawDataField.java \
       jme3-core/src/main/java/com/jme3/renderer/indirect/DrawDataLayout.java \
       jme3-core/src/test/java/com/jme3/renderer/indirect/DrawDataLayoutTest.java
git commit -m "feat(indirect): add DrawDataField and DrawDataLayout for std430 layout computation"
```

---

## Task 2: DrawData block parsing in MaterialDef

Add `DrawData` block support to `.j3md` files and store the field list in `MaterialDef`.

**Files:**
- Modify: `jme3-core/src/main/java/com/jme3/material/MaterialDef.java`
- Modify: `jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java`
- Create: `jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderDrawDataTest.java`

The `DrawData` block syntax in a `.j3md` file:

```
DrawData {
    WorldMatrix
    BaseColor
    Roughness
    BaseColorMap
}
```

Each line is either `WorldMatrix` (special built-in) or a material parameter name (looked up from `MaterialParameters`). The type is inferred from the parameter definition.

- [ ] **Step 1: Write failing test for DrawData parsing**

```java
package com.jme3.material.plugins;

import com.jme3.asset.AssetManager;
import com.jme3.material.MaterialDef;
import com.jme3.renderer.indirect.DrawDataLayout;
import com.jme3.shader.VarType;
import com.jme3.system.TestUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class J3MLoaderDrawDataTest {

    private static AssetManager assetManager;

    @BeforeAll
    public static void setUp() {
        assetManager = TestUtil.createAssetManager();
    }

    @Test
    public void testDrawDataBlockParsed() {
        // Load a matdef that has a DrawData block
        MaterialDef def = (MaterialDef) assetManager.loadAsset("TestMatDefs/DrawDataTest.j3md");

        DrawDataLayout layout = def.getDrawDataLayout();
        assertNotNull(layout, "DrawDataLayout should be parsed from DrawData block");
        assertEquals(4, layout.getFieldCount());

        // Field 0: WorldMatrix (special)
        assertTrue(layout.getField(0).isWorldMatrix());
        assertEquals(VarType.Matrix4, layout.getField(0).getVarType());

        // Field 1: BaseColor (Color → Vector4)
        assertEquals("BaseColor", layout.getField(1).getName());
        assertEquals(VarType.Vector4, layout.getField(1).getVarType());

        // Field 2: Roughness (Float)
        assertEquals("Roughness", layout.getField(2).getName());
        assertEquals(VarType.Float, layout.getField(2).getVarType());

        // Field 3: BaseColorMap (Texture2D → uvec2 handle)
        assertEquals("BaseColorMap", layout.getField(3).getName());
        assertEquals(VarType.Texture2D, layout.getField(3).getVarType());
    }

    @Test
    public void testNoDrawDataBlock() {
        // Standard matdef without DrawData block → null layout
        MaterialDef def = (MaterialDef) assetManager.loadAsset("Common/MatDefs/Misc/Unshaded.j3md");
        assertNull(def.getDrawDataLayout());
    }
}
```

Also create the test matdef resource file at `jme3-core/src/test/resources/TestMatDefs/DrawDataTest.j3md`:

```
MaterialDef DrawDataTest {

    MaterialParameters {
        Color BaseColor : 1.0 1.0 1.0 1.0
        Float Roughness : 0.5
        Texture2D BaseColorMap
    }

    DrawData {
        WorldMatrix
        BaseColor
        Roughness
        BaseColorMap
    }

    Technique {
        // Minimal technique — shaders not needed for this test
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderDrawDataTest" --info`
Expected: Compilation failure or `getDrawDataLayout()` not found.

- [ ] **Step 3: Add DrawDataLayout storage to MaterialDef**

In `jme3-core/src/main/java/com/jme3/material/MaterialDef.java`, add after the `matParams` field (line 57):

```java
    private DrawDataLayout drawDataLayout;
```

Add import at top:
```java
import com.jme3.renderer.indirect.DrawDataLayout;
```

Add getter/setter after `getMaterialParams()` (after line 162):

```java
    /**
     * Returns the DrawData layout declared in this material definition,
     * or null if no DrawData block was declared.
     *
     * @return the DrawData layout, or null
     */
    public DrawDataLayout getDrawDataLayout() {
        return drawDataLayout;
    }

    /**
     * Sets the DrawData layout for this material definition.
     *
     * @param layout the computed DrawData layout
     */
    public void setDrawDataLayout(DrawDataLayout layout) {
        this.drawDataLayout = layout;
    }
```

- [ ] **Step 4: Add DrawData parsing to J3MLoader**

In `jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java`:

Add a new method after `readMaterialParams()`:

```java
    private void readDrawData(List<Statement> drawDataStatements) {
        DrawDataLayout.Builder builder = new DrawDataLayout.Builder();
        for (Statement statement : drawDataStatements) {
            String fieldName = statement.getLine().trim();
            if (fieldName.isEmpty()) continue;

            if (fieldName.equals("WorldMatrix")) {
                builder.addWorldMatrix();
            } else {
                // Look up the material parameter to get its type
                MatParam param = materialDef.getMaterialParam(fieldName);
                if (param == null) {
                    throw new AssetLoadException("DrawData field '" + fieldName
                            + "' does not match any MaterialParameter in "
                            + materialDef.getName());
                }
                builder.addField(fieldName, param.getVarType());
            }
        }
        materialDef.setDrawDataLayout(builder.build());
    }
```

Add the required import at the top of J3MLoader.java:

```java
import com.jme3.renderer.indirect.DrawDataLayout;
```

Then in `loadFromRoot()`, in the `else` block (non-extending matdef, around line 820-827), add the `DrawData` case:

Change:
```java
                if (statType.equals("Technique")) {
                    readTechnique(statement);
                } else if (statType.equals("MaterialParameters")) {
                    readMaterialParams(statement.getContents());
                } else {
                    throw new MatParseException("Expected material statement, got '" + statType + "'", statement);
                }
```

To:
```java
                if (statType.equals("Technique")) {
                    readTechnique(statement);
                } else if (statType.equals("MaterialParameters")) {
                    readMaterialParams(statement.getContents());
                } else if (statType.equals("DrawData")) {
                    readDrawData(statement.getContents());
                } else {
                    throw new MatParseException("Expected material statement, got '" + statType + "'", statement);
                }
```

- [ ] **Step 5: Create the test matdef resource file**

Create `jme3-core/src/test/resources/TestMatDefs/DrawDataTest.j3md` with content shown in Step 1.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderDrawDataTest" --info`
Expected: Both tests PASS.

- [ ] **Step 7: Commit**

```bash
git add jme3-core/src/main/java/com/jme3/material/MaterialDef.java \
       jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java \
       jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderDrawDataTest.java \
       jme3-core/src/test/resources/TestMatDefs/DrawDataTest.j3md
git commit -m "feat(material): parse DrawData block from j3md files"
```

---

## Task 3: DrawData serializer

Serializes a Geometry's material params + world matrix into a byte region of the DrawData SSBO.

**Files:**
- Create: `jme3-core/src/main/java/com/jme3/renderer/indirect/DrawDataSerializer.java`
- Create: `jme3-core/src/test/java/com/jme3/renderer/indirect/DrawDataSerializerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.jme3.renderer.indirect;

import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.shader.VarType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

public class DrawDataSerializerTest {

    @Test
    public void testSerializeWorldMatrixAndFloat() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .addField("Roughness", VarType.Float)
                .build();
        // stride = 80 (64 for mat4 + 4 for float + 12 padding to 16-align)

        Matrix4f worldMatrix = new Matrix4f();
        worldMatrix.setTranslation(1f, 2f, 3f);

        ByteBuffer buf = ByteBuffer.allocateDirect(layout.getStride()).order(ByteOrder.nativeOrder());

        // Simulate: no Material, just worldMatrix + a param map
        DrawDataSerializer.serialize(layout, buf, 0, worldMatrix, (name) -> {
            if ("Roughness".equals(name)) return 0.75f;
            return null;
        });

        // Check mat4 column 3 (translation) at offset 48..63 (col-major: col3 = bytes 48-63)
        buf.position(48); // column 3 starts at 3*16=48
        assertEquals(1f, buf.getFloat(), 0.001f); // m03
        assertEquals(2f, buf.getFloat(), 0.001f); // m13
        assertEquals(3f, buf.getFloat(), 0.001f); // m23

        // Check roughness at offset 64
        buf.position(64);
        assertEquals(0.75f, buf.getFloat(), 0.001f);
    }

    @Test
    public void testSerializeVec4() {
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
        // Fill with non-zero to verify it gets zeroed
        buf.putLong(0, 0xFFFFFFFFFFFFFFFFL);

        DrawDataSerializer.serialize(layout, buf, 0, null, (name) -> null);

        buf.position(0);
        assertEquals(0L, buf.getLong()); // null texture → handle 0
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :jme3-core:test --tests "com.jme3.renderer.indirect.DrawDataSerializerTest" --info`
Expected: Compilation failure — `DrawDataSerializer` doesn't exist.

- [ ] **Step 3: Implement DrawDataSerializer**

```java
package com.jme3.renderer.indirect;

import com.jme3.math.ColorRGBA;
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
     *                     or null (no texture → handle 0).
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
                buf.putInt(value != null && (Boolean) value ? 1 : 0);
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
            case Matrix4:
                writeMat4(buf, value != null ? (Matrix4f) value : Matrix4f.IDENTITY);
                break;
            case Texture2D:
            case Texture3D:
            case TextureArray:
            case TextureCubeMap:
                // Bindless handle as long (uvec2). Null → 0 (no texture).
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
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jme3-core:test --tests "com.jme3.renderer.indirect.DrawDataSerializerTest" --info`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add jme3-core/src/main/java/com/jme3/renderer/indirect/DrawDataSerializer.java \
       jme3-core/src/test/java/com/jme3/renderer/indirect/DrawDataSerializerTest.java
git commit -m "feat(indirect): add DrawDataSerializer for per-draw SSBO population"
```

---

## Task 4: MdiGeometry — rendering pipeline integration

A Geometry subclass that renders via `glMultiDrawElementsIndirect` instead of `glDrawElements`.

**Files:**
- Create: `jme3-core/src/main/java/com/jme3/scene/indirect/MdiGeometry.java`
- Modify: `jme3-core/src/main/java/com/jme3/material/logic/DefaultTechniqueDefLogic.java:63-75`

- [ ] **Step 1: Implement MdiGeometry**

```java
package com.jme3.scene.indirect;

import com.jme3.renderer.indirect.IndirectCommandBuffer;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;

/**
 * A Geometry that renders its mesh via multi-draw indirect.
 * <p>
 * Instead of a single {@code glDrawElements} call, this geometry issues
 * {@code glMultiDrawElementsIndirect} using the attached command buffer.
 * The rendering pipeline detects this type in
 * {@link com.jme3.material.logic.DefaultTechniqueDefLogic#renderMeshFromGeometry}
 * and dispatches the MDI call automatically.
 * <p>
 * The DrawData SSBO should be set on this geometry's material as a
 * {@code ShaderStorageBufferObject} parameter named {@code "DrawData"}
 * (or whatever the matdef's DrawData SSBO is called).
 */
public class MdiGeometry extends Geometry {

    private IndirectCommandBuffer commandBuffer;

    protected MdiGeometry() {
        super();
    }

    public MdiGeometry(String name, Mesh mesh, IndirectCommandBuffer commandBuffer) {
        super(name, mesh);
        this.commandBuffer = commandBuffer;
    }

    /** Returns the indirect command buffer used for MDI rendering. */
    public IndirectCommandBuffer getCommandBuffer() {
        return commandBuffer;
    }

    /** Sets the indirect command buffer. */
    public void setCommandBuffer(IndirectCommandBuffer commandBuffer) {
        this.commandBuffer = commandBuffer;
    }

    /** Returns the number of draw commands in the command buffer. */
    public int getDrawCount() {
        return commandBuffer != null ? commandBuffer.getCommandCount() : 0;
    }
}
```

- [ ] **Step 2: Add MdiGeometry branch to renderMeshFromGeometry**

In `jme3-core/src/main/java/com/jme3/material/logic/DefaultTechniqueDefLogic.java`, modify `renderMeshFromGeometry()`.

Add import at top:
```java
import com.jme3.scene.indirect.MdiGeometry;
```

Change the method body from:
```java
    public static void renderMeshFromGeometry(Renderer renderer, Geometry geom) {
        Mesh mesh = geom.getMesh();
        int lodLevel = geom.getLodLevel();
        if (geom instanceof InstancedGeometry) {
            InstancedGeometry instGeom = (InstancedGeometry) geom;
            int numVisibleInstances = instGeom.getNumVisibleInstances();
            if (numVisibleInstances > 0) {
                renderer.renderMesh(mesh, lodLevel, numVisibleInstances, instGeom.getAllInstanceData());
            }
        } else {
            renderer.renderMesh(mesh, lodLevel, 1, null);
        }
    }
```

To:
```java
    public static void renderMeshFromGeometry(Renderer renderer, Geometry geom) {
        Mesh mesh = geom.getMesh();
        int lodLevel = geom.getLodLevel();
        if (geom instanceof MdiGeometry) {
            MdiGeometry mdiGeom = (MdiGeometry) geom;
            int drawCount = mdiGeom.getDrawCount();
            if (drawCount > 0) {
                renderer.renderMeshMultiIndirect(mesh,
                        mdiGeom.getCommandBuffer().getBufferObject(),
                        drawCount, 0);
            }
        } else if (geom instanceof InstancedGeometry) {
            InstancedGeometry instGeom = (InstancedGeometry) geom;
            int numVisibleInstances = instGeom.getNumVisibleInstances();
            if (numVisibleInstances > 0) {
                renderer.renderMesh(mesh, lodLevel, numVisibleInstances, instGeom.getAllInstanceData());
            }
        } else {
            renderer.renderMesh(mesh, lodLevel, 1, null);
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add jme3-core/src/main/java/com/jme3/scene/indirect/MdiGeometry.java \
       jme3-core/src/main/java/com/jme3/material/logic/DefaultTechniqueDefLogic.java
git commit -m "feat(indirect): add MdiGeometry with MDI rendering pipeline integration"
```

---

## Task 5: IndirectBatch — mid-level manual API

Wraps MeshCombiner + command buffer + DrawData SSBO into a single object. Users add geometries, call `build()`, then update transforms per frame.

**Files:**
- Create: `jme3-core/src/main/java/com/jme3/renderer/indirect/IndirectBatch.java`
- Create: `jme3-core/src/test/java/com/jme3/renderer/indirect/IndirectBatchTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.jme3.renderer.indirect;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.scene.Mesh;
import com.jme3.scene.indirect.MdiGeometry;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class IndirectBatchTest {

    @Test
    public void testBuildCreatesCommandBuffer() {
        DrawDataLayout layout = new DrawDataLayout.Builder()
                .addWorldMatrix()
                .addField("BaseColor", VarType.Vector4)
                .build();

        IndirectBatch batch = new IndirectBatch(layout);
        batch.addDraw(new Box(1, 1, 1), (name) -> new ColorRGBA(1, 0, 0, 1));
        batch.addDraw(new Sphere(8, 8, 1f), (name) -> new ColorRGBA(0, 1, 0, 1));
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
        batch.addDraw(new Box(1, 1, 1), (name) -> null);
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
        batch.addDraw(new Box(1, 1, 1), (name) -> null);
        batch.build();

        MdiGeometry geom = batch.createGeometry("test");
        assertNotNull(geom);
        assertEquals("test", geom.getName());
        assertNotNull(geom.getMesh());
        assertNotNull(geom.getCommandBuffer());
        assertEquals(1, geom.getDrawCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jme3-core:test --tests "com.jme3.renderer.indirect.IndirectBatchTest" --info`
Expected: Compilation failure — `IndirectBatch` doesn't exist.

- [ ] **Step 3: Implement IndirectBatch**

```java
package com.jme3.renderer.indirect;

import com.jme3.math.Matrix4f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.indirect.MdiGeometry;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Mid-level API for multi-draw indirect rendering.
 * <p>
 * Combines multiple meshes into one, builds an indirect command buffer, and
 * manages a DrawData SSBO that is automatically populated from per-draw
 * parameter values. Provides per-frame transform updates.
 * <p>
 * Usage:
 * <pre>
 * DrawDataLayout layout = myMaterial.getMaterialDef().getDrawDataLayout();
 * IndirectBatch batch = new IndirectBatch(layout);
 * batch.addDraw(boxMesh, name -&gt; material1.getParam(name).getValue());
 * batch.addDraw(sphereMesh, name -&gt; material2.getParam(name).getValue());
 * batch.build();
 *
 * MdiGeometry geom = batch.createGeometry("myBatch");
 * geom.setMaterial(mdiMaterial);
 * rootNode.attachChild(geom);
 *
 * // Each frame:
 * batch.setWorldMatrix(0, geom1WorldMatrix);
 * batch.setWorldMatrix(1, geom2WorldMatrix);
 * batch.update();
 * </pre>
 */
public class IndirectBatch {

    private final DrawDataLayout layout;
    private final List<DrawEntry> entries = new ArrayList<>();

    // Built state
    private MeshCombiner.CombinedMesh combinedMesh;
    private IndirectCommandBuffer commandBuffer;
    private BufferObject drawDataBuffer;
    private ByteBuffer drawDataCpu;
    private boolean built = false;

    public IndirectBatch(DrawDataLayout layout) {
        this.layout = layout;
    }

    /**
     * Adds a draw entry with a mesh and parameter lookup function.
     * The lookup function resolves material parameter values by name.
     * For texture params, return {@code Long} (bindless handle) or null.
     *
     * @param mesh        the mesh for this draw
     * @param paramLookup resolves param name → value
     */
    public void addDraw(Mesh mesh, Function<String, Object> paramLookup) {
        entries.add(new DrawEntry(mesh, paramLookup));
    }

    /**
     * Adds a draw entry from an existing Geometry, reading param values
     * from its material.
     */
    public void addDraw(Geometry geometry) {
        entries.add(new DrawEntry(geometry.getMesh(), name -> {
            var param = geometry.getMaterial().getParam(name);
            return param != null ? param.getValue() : null;
        }));
    }

    /**
     * Combines meshes, builds the command buffer, and populates the DrawData SSBO.
     * Must be called after all draws are added and before rendering.
     */
    public void build() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("No draws added to batch");
        }

        // Combine meshes
        Mesh[] meshes = new Mesh[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            meshes[i] = entries.get(i).mesh;
        }
        combinedMesh = MeshCombiner.combine(meshes);

        // Build command buffer
        commandBuffer = combinedMesh.toCommandBuffer();
        commandBuffer.update();

        // Allocate DrawData SSBO
        int totalBytes = entries.size() * layout.getStride();
        drawDataCpu = BufferUtils.createByteBuffer(totalBytes);
        drawDataBuffer = new BufferObject();
        drawDataBuffer.setAccessHint(BufferObject.AccessHint.Dynamic);
        drawDataBuffer.setNatureHint(BufferObject.NatureHint.Draw);

        // Serialize initial param values
        for (int i = 0; i < entries.size(); i++) {
            DrawDataSerializer.serialize(layout, drawDataCpu, i * layout.getStride(),
                    Matrix4f.IDENTITY, entries.get(i).paramLookup);
        }
        drawDataCpu.rewind();
        drawDataBuffer.setData(drawDataCpu);
        drawDataBuffer.setUpdateNeeded();

        built = true;
    }

    /**
     * Sets the world matrix for draw at the given index.
     * Call {@link #update()} after setting all transforms.
     */
    public void setWorldMatrix(int drawIndex, Matrix4f worldMatrix) {
        checkBuilt();
        DrawDataField wmField = layout.findField("WorldMatrix");
        if (wmField == null || !wmField.isWorldMatrix()) return;

        int pos = drawIndex * layout.getStride() + wmField.getOffset();
        drawDataCpu.position(pos);
        writeMat4(drawDataCpu, worldMatrix);
    }

    /**
     * Uploads the DrawData SSBO to the GPU. Call after modifying transforms.
     */
    public void update() {
        checkBuilt();
        drawDataCpu.rewind();
        drawDataBuffer.setData(drawDataCpu);
        drawDataBuffer.setUpdateNeeded();
    }

    /**
     * Creates an MdiGeometry using the combined mesh and command buffer.
     * Set a material on the returned geometry and attach it to the scene graph.
     * The DrawData SSBO must be set on the material:
     * {@code material.setShaderStorageBufferObject("DrawData", batch.getDrawDataBuffer())}
     */
    public MdiGeometry createGeometry(String name) {
        checkBuilt();
        return new MdiGeometry(name, combinedMesh.getMesh(), commandBuffer);
    }

    public int getDrawCount() { return entries.size(); }
    public DrawDataLayout getLayout() { return layout; }
    public Mesh getCombinedMesh() { checkBuilt(); return combinedMesh.getMesh(); }
    public IndirectCommandBuffer getCommandBuffer() { checkBuilt(); return commandBuffer; }
    public BufferObject getDrawDataBuffer() { checkBuilt(); return drawDataBuffer; }
    public MeshCombiner.CombinedMesh getCombinedMeshInfo() { checkBuilt(); return combinedMesh; }

    private void checkBuilt() {
        if (!built) throw new IllegalStateException("IndirectBatch not built yet. Call build() first.");
    }

    private static void writeMat4(ByteBuffer buf, Matrix4f m) {
        buf.putFloat(m.m00).putFloat(m.m10).putFloat(m.m20).putFloat(m.m30);
        buf.putFloat(m.m01).putFloat(m.m11).putFloat(m.m21).putFloat(m.m31);
        buf.putFloat(m.m02).putFloat(m.m12).putFloat(m.m22).putFloat(m.m32);
        buf.putFloat(m.m03).putFloat(m.m13).putFloat(m.m23).putFloat(m.m33);
    }

    private static class DrawEntry {
        final Mesh mesh;
        final Function<String, Object> paramLookup;

        DrawEntry(Mesh mesh, Function<String, Object> paramLookup) {
            this.mesh = mesh;
            this.paramLookup = paramLookup;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :jme3-core:test --tests "com.jme3.renderer.indirect.IndirectBatchTest" --info`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add jme3-core/src/main/java/com/jme3/renderer/indirect/IndirectBatch.java \
       jme3-core/src/test/java/com/jme3/renderer/indirect/IndirectBatchTest.java
git commit -m "feat(indirect): add IndirectBatch mid-level API for MDI rendering"
```

---

## Task 6: MdiNode — automatic scene graph batching

Extends `GeometryGroupNode` (like `InstancedNode`) to automatically batch child geometries into MDI draw calls.

**Files:**
- Create: `jme3-core/src/main/java/com/jme3/scene/indirect/MdiNode.java`
- Create: `jme3-core/src/main/java/com/jme3/scene/indirect/MdiNodeControl.java`

- [ ] **Step 1: Implement MdiNodeControl**

```java
package com.jme3.scene.indirect;

import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.Control;
import com.jme3.util.clone.Cloner;
import com.jme3.util.clone.JmeCloneable;

import java.io.IOException;

/**
 * Control that triggers per-frame updates on the owning MdiNode.
 * Attached automatically when an MdiNode is created.
 */
class MdiNodeControl implements Control, JmeCloneable {

    private MdiNode node;

    MdiNodeControl() {
    }

    MdiNodeControl(MdiNode node) {
        this.node = node;
    }

    @Override
    public void setSpatial(Spatial spatial) {
    }

    @Override
    public void update(float tpf) {
    }

    @Override
    public void render(RenderManager rm, ViewPort vp) {
        node.updateBatches();
    }

    @Deprecated
    @Override
    public Control cloneForSpatial(Spatial spatial) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object jmeClone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        this.node = cloner.clone(node);
    }

    @Override
    public void write(JmeExporter ex) throws IOException {
    }

    @Override
    public void read(JmeImporter im) throws IOException {
    }
}
```

- [ ] **Step 2: Implement MdiNode**

```java
package com.jme3.scene.indirect;

import com.jme3.material.MatParam;
import com.jme3.material.Material;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.indirect.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A scene graph node that automatically batches child geometries into
 * multi-draw indirect (MDI) draw calls.
 * <p>
 * Works like {@link com.jme3.scene.instancing.InstancedNode} but uses MDI
 * instead of hardware instancing. This allows batching geometries with
 * <b>different meshes</b> into a single draw call, as long as they share
 * the same material definition with a {@code DrawData} block.
 * <p>
 * Usage:
 * <pre>
 * MdiNode mdiNode = new MdiNode("terrain");
 * rootNode.attachChild(mdiNode);
 *
 * Geometry tree = new Geometry("tree", treeMesh);
 * tree.setMaterial(mdiMaterial);  // must have DrawData in its matdef
 * mdiNode.attachChild(tree);
 *
 * Geometry rock = new Geometry("rock", rockMesh);
 * rock.setMaterial(mdiMaterial);
 * mdiNode.attachChild(rock);
 *
 * mdiNode.batch();  // builds MDI batches
 * </pre>
 * <p>
 * Child geometry transforms are updated automatically each frame via
 * the internal {@link MdiNodeControl}.
 */
public class MdiNode extends GeometryGroupNode {

    private static final Logger logger = Logger.getLogger(MdiNode.class.getName());

    /** Name of the SSBO parameter on the material for per-draw data. */
    private static final String DRAW_DATA_PARAM = "DrawData";

    private MdiNodeControl control;

    /** Maps each child geometry to its batch. */
    private final Map<Geometry, MdiBatch> batchByGeom = new HashMap<>();

    /** Maps DrawDataLayout identity to batch. Geometries with same layout share a batch. */
    private final Map<DrawDataLayout, MdiBatch> batchByLayout = new HashMap<>();

    /** Set of batches needing transform re-upload. */
    private final Set<MdiBatch> dirtyBatches = new HashSet<>();

    /** Whether batch() has been called. */
    private boolean batched = false;

    protected MdiNode() {
        super();
    }

    public MdiNode(String name) {
        super(name);
        control = new MdiNodeControl(this);
        addControl(control);
    }

    /**
     * Groups all child geometries into MDI batches by material DrawData layout.
     * Must be called after attaching children and before rendering.
     */
    public void batch() {
        // Clear existing batches
        for (MdiBatch batch : batchByLayout.values()) {
            if (batch.mdiGeometry != null) {
                detachChild(batch.mdiGeometry);
            }
        }
        batchByGeom.clear();
        batchByLayout.clear();
        dirtyBatches.clear();

        // Collect geometries recursively
        collectGeometries(this);

        // Build each batch
        for (MdiBatch batch : batchByLayout.values()) {
            buildBatch(batch);
        }

        batched = true;
    }

    private void collectGeometries(Spatial spatial) {
        if (spatial instanceof Geometry) {
            Geometry geom = (Geometry) spatial;
            if (geom instanceof MdiGeometry) return; // skip our own batch geometries
            if (geom.isGrouped()) return;
            if (geom.getBatchHint() == Spatial.BatchHint.Never) return;

            addToBatch(geom);
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                if (child instanceof GeometryGroupNode) continue;
                collectGeometries(child);
            }
        }
    }

    private void addToBatch(Geometry geom) {
        Material material = geom.getMaterial();
        if (material == null) {
            logger.log(Level.WARNING, "Geometry {0} has no material, skipping MDI batching", geom.getName());
            return;
        }

        DrawDataLayout layout = material.getMaterialDef().getDrawDataLayout();
        if (layout == null) {
            logger.log(Level.WARNING, "Material on {0} has no DrawData block, skipping MDI batching", geom.getName());
            return;
        }

        MdiBatch batch = batchByLayout.get(layout);
        if (batch == null) {
            batch = new MdiBatch(layout, material);
            batchByLayout.put(layout, batch);
        }

        batch.geometries.add(geom);
        batchByGeom.put(geom, batch);
        geom.associateWithGroupNode(this, batch.geometries.size() - 1);
    }

    private void buildBatch(MdiBatch batch) {
        DrawDataLayout layout = batch.layout;
        List<Geometry> geoms = batch.geometries;

        // Combine meshes
        Mesh[] meshes = new Mesh[geoms.size()];
        for (int i = 0; i < geoms.size(); i++) {
            meshes[i] = geoms.get(i).getMesh();
        }
        batch.combinedMesh = MeshCombiner.combine(meshes);

        // Build command buffer
        batch.commandBuffer = batch.combinedMesh.toCommandBuffer();
        batch.commandBuffer.update();

        // Allocate DrawData SSBO
        int totalBytes = geoms.size() * layout.getStride();
        batch.drawDataCpu = BufferUtils.createByteBuffer(totalBytes);
        batch.drawDataSsbo = new BufferObject();
        batch.drawDataSsbo.setAccessHint(BufferObject.AccessHint.Dynamic);
        batch.drawDataSsbo.setNatureHint(BufferObject.NatureHint.Draw);

        // Serialize initial material params
        serializeBatch(batch);

        // Create MdiGeometry and attach to scene
        batch.mdiGeometry = new MdiGeometry(
                "MdiBatch-" + System.identityHashCode(batch),
                batch.combinedMesh.getMesh(),
                batch.commandBuffer);

        // Clone the material and set the DrawData SSBO on it
        Material batchMaterial = batch.templateMaterial.clone();
        batchMaterial.setShaderStorageBufferObject(DRAW_DATA_PARAM, batch.drawDataSsbo);
        batch.mdiGeometry.setMaterial(batchMaterial);
        batch.mdiGeometry.setCullHint(CullHint.Never);
        batch.mdiGeometry.setShadowMode(RenderQueue.ShadowMode.Inherit);

        attachChild(batch.mdiGeometry);
        dirtyBatches.add(batch);
    }

    private void serializeBatch(MdiBatch batch) {
        DrawDataLayout layout = batch.layout;
        ByteBuffer buf = batch.drawDataCpu;
        buf.rewind();

        for (int i = 0; i < batch.geometries.size(); i++) {
            Geometry geom = batch.geometries.get(i);
            Material mat = geom.getMaterial();

            Matrix4f worldMatrix = geom.getWorldMatrix();

            DrawDataSerializer.serialize(layout, buf, i * layout.getStride(),
                    worldMatrix, name -> resolveParam(mat, name));
        }

        buf.rewind();
        batch.drawDataSsbo.setData(buf);
        batch.drawDataSsbo.setUpdateNeeded();
    }

    private Object resolveParam(Material material, String paramName) {
        MatParam param = material.getParam(paramName);
        if (param == null) return null;

        Object value = param.getValue();
        // For texture types, return the bindless handle
        if (param.getVarType().isTextureType() && value instanceof Texture) {
            Texture tex = (Texture) value;
            long handle = tex.getImage().getBindlessHandle();
            return handle != 0 ? handle : null;
        }
        return value;
    }

    /**
     * Called by MdiNodeControl each render frame. Updates dirty batch SSBOs.
     */
    void updateBatches() {
        if (!batched) return;

        for (MdiBatch batch : dirtyBatches) {
            updateBatchTransforms(batch);
        }
        dirtyBatches.clear();
    }

    private void updateBatchTransforms(MdiBatch batch) {
        DrawDataLayout layout = batch.layout;
        DrawDataField wmField = layout.findField("WorldMatrix");
        if (wmField == null) return;

        ByteBuffer buf = batch.drawDataCpu;
        for (int i = 0; i < batch.geometries.size(); i++) {
            Geometry geom = batch.geometries.get(i);
            int pos = i * layout.getStride() + wmField.getOffset();
            buf.position(pos);
            Matrix4f wm = geom.getWorldMatrix();
            buf.putFloat(wm.m00).putFloat(wm.m10).putFloat(wm.m20).putFloat(wm.m30);
            buf.putFloat(wm.m01).putFloat(wm.m11).putFloat(wm.m21).putFloat(wm.m31);
            buf.putFloat(wm.m02).putFloat(wm.m12).putFloat(wm.m22).putFloat(wm.m32);
            buf.putFloat(wm.m03).putFloat(wm.m13).putFloat(wm.m23).putFloat(wm.m33);
        }

        buf.rewind();
        batch.drawDataSsbo.setData(buf);
        batch.drawDataSsbo.setUpdateNeeded();
    }

    // --- GeometryGroupNode callbacks ---

    @Override
    public void onTransformChange(Geometry geom) {
        MdiBatch batch = batchByGeom.get(geom);
        if (batch != null) {
            dirtyBatches.add(batch);
        }
    }

    @Override
    public void onMaterialChange(Geometry geom) {
        if (batched) {
            // Material change requires full rebuild of affected batch
            logger.log(Level.WARNING,
                    "Material changed on {0} after batch(). Call batch() again to rebuild.",
                    geom.getName());
        }
    }

    @Override
    public void onMeshChange(Geometry geom) {
        if (batched) {
            logger.log(Level.WARNING,
                    "Mesh changed on {0} after batch(). Call batch() again to rebuild.",
                    geom.getName());
        }
    }

    @Override
    public void onGeometryUnassociated(Geometry geom) {
        MdiBatch batch = batchByGeom.remove(geom);
        if (batch != null) {
            batch.geometries.remove(geom);
            // Batch needs rebuild
            if (batched) {
                logger.log(Level.INFO,
                        "Geometry {0} removed from MdiNode. Call batch() to rebuild.",
                        geom.getName());
            }
        }
    }

    @Override
    public Spatial detachChildAt(int index) {
        Spatial s = super.detachChildAt(index);
        if (s instanceof MdiGeometry) {
            // One of our batch geometries was detached — clean up
            batchByLayout.values().removeIf(b -> b.mdiGeometry == s);
        }
        return s;
    }

    /** Internal batch state for one group of geometries sharing a DrawData layout. */
    private static class MdiBatch {
        final DrawDataLayout layout;
        final Material templateMaterial;
        final List<Geometry> geometries = new ArrayList<>();

        MeshCombiner.CombinedMesh combinedMesh;
        IndirectCommandBuffer commandBuffer;
        BufferObject drawDataSsbo;
        ByteBuffer drawDataCpu;
        MdiGeometry mdiGeometry;

        MdiBatch(DrawDataLayout layout, Material templateMaterial) {
            this.layout = layout;
            this.templateMaterial = templateMaterial;
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add jme3-core/src/main/java/com/jme3/scene/indirect/MdiNode.java \
       jme3-core/src/main/java/com/jme3/scene/indirect/MdiNodeControl.java
git commit -m "feat(indirect): add MdiNode for automatic MDI scene graph batching"
```

---

## Task 7: Built-in UnshadedMdi matdef and shaders

A superset MDI-aware Unshaded material that reads all params from a DrawData SSBO.

**Files:**
- Create: `jme3-core/src/main/resources/Common/MatDefs/Misc/UnshadedMdi.j3md`
- Create: `jme3-core/src/main/resources/Common/MatDefs/Misc/UnshadedMdi.vert`
- Create: `jme3-core/src/main/resources/Common/MatDefs/Misc/UnshadedMdi.frag`

- [ ] **Step 1: Create UnshadedMdi.j3md**

```
MaterialDef UnshadedMdi {

    MaterialParameters {
        Color Color : 1.0 1.0 1.0 1.0
        Texture2D ColorMap
        ShaderStorageBufferObject DrawData
    }

    DrawData {
        WorldMatrix
        Color
        ColorMap
    }

    Technique {
        VertexShader GLSL460 : Common/MatDefs/Misc/UnshadedMdi.vert
        FragmentShader GLSL460 : Common/MatDefs/Misc/UnshadedMdi.frag

        WorldParameters {
            ViewProjectionMatrix
        }
    }
}
```

- [ ] **Step 2: Create UnshadedMdi.vert**

```glsl
uniform mat4 g_ViewProjectionMatrix;

struct DrawData {
    mat4  worldMatrix;
    vec4  color;
    uvec2 colorMapHandle;
    uvec2 _pad0; // align to 16 bytes
};

layout(std430) buffer m_DrawData {
    DrawData draws[];
};

in vec3 inPosition;
in vec2 inTexCoord;

out vec2 texCoord;
flat out int drawId;

void main() {
    drawId = gl_DrawID;
    texCoord = inTexCoord;

    mat4 worldMatrix = draws[gl_DrawID].worldMatrix;
    vec4 worldPos = worldMatrix * vec4(inPosition, 1.0);
    gl_Position = g_ViewProjectionMatrix * worldPos;
}
```

- [ ] **Step 3: Create UnshadedMdi.frag**

```glsl
#extension GL_ARB_bindless_texture : enable

struct DrawData {
    mat4  worldMatrix;
    vec4  color;
    uvec2 colorMapHandle;
    uvec2 _pad0;
};

layout(std430) buffer m_DrawData {
    DrawData draws[];
};

in vec2 texCoord;
flat in int drawId;

out vec4 outFragColor;

void main() {
    DrawData d = draws[drawId];
    vec4 color = d.color;

    if (d.colorMapHandle != uvec2(0)) {
        color *= texture(sampler2D(d.colorMapHandle), texCoord);
    }

    outFragColor = color;
}
```

- [ ] **Step 4: Commit**

```bash
git add jme3-core/src/main/resources/Common/MatDefs/Misc/UnshadedMdi.j3md \
       jme3-core/src/main/resources/Common/MatDefs/Misc/UnshadedMdi.vert \
       jme3-core/src/main/resources/Common/MatDefs/Misc/UnshadedMdi.frag
git commit -m "feat(matdef): add built-in UnshadedMdi material for MDI rendering"
```

---

## Task 8: Integration test — MdiNode with UnshadedMdi

End-to-end screenshot test proving MdiNode batches different meshes with UnshadedMdi.

**Files:**
- Create: `jme3-screenshot-tests/src/test/java/org/jmonkeyengine/screenshottests/renderer/indirect/TestMdiNode.java`

- [ ] **Step 1: Write the integration test**

```java
package org.jmonkeyengine.screenshottests.renderer.indirect;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.scene.Geometry;
import com.jme3.scene.indirect.MdiNode;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTestBase;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Tests MdiNode automatic batching: attaches geometries with different meshes
 * and colors under an MdiNode, calls batch(), and verifies they render correctly
 * in a single MDI call.
 */
public class TestMdiNode extends ScreenshotTestBase {

    @Test
    public void testMdiNodeBatching() {
        screenshotTest(new BaseAppState() {
            @Override
            protected void initialize(Application app) {
                SimpleApplication simpleApp = (SimpleApplication) app;
                EnumSet<Caps> caps = app.getRenderer().getCaps();

                if (!caps.contains(Caps.MultiDrawIndirect)
                        || !caps.contains(Caps.ShaderStorageBufferObject)) {
                    return;
                }

                app.getCamera().setLocation(new Vector3f(0, 0, 8));
                app.getCamera().lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

                MdiNode mdiNode = new MdiNode("testMdi");
                simpleApp.getRootNode().attachChild(mdiNode);

                // Red box on the left
                Material mat1 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/UnshadedMdi.j3md");
                mat1.setColor("Color", ColorRGBA.Red);

                Geometry box = new Geometry("box", new Box(0.8f, 0.8f, 0.8f));
                box.setMaterial(mat1);
                box.setLocalTranslation(-2, 0, 0);
                mdiNode.attachChild(box);

                // Green sphere on the right
                Material mat2 = new Material(app.getAssetManager(),
                        "Common/MatDefs/Misc/UnshadedMdi.j3md");
                mat2.setColor("Color", ColorRGBA.Green);

                Geometry sphere = new Geometry("sphere", new Sphere(16, 16, 1f));
                sphere.setMaterial(mat2);
                sphere.setLocalTranslation(2, 0, 0);
                mdiNode.attachChild(sphere);

                // Batch — combines both into one MDI call
                mdiNode.batch();
            }

            @Override protected void cleanup(Application app) {}
            @Override protected void onEnable() {}
            @Override protected void onDisable() {}
        })
        .setFramesToTakeScreenshotsOn(3)
        .run();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :jme3-screenshot-tests:test --tests "org.jmonkeyengine.screenshottests.renderer.indirect.TestMdiNode" --info`

This needs a GPU. If running headless, verify compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add jme3-screenshot-tests/src/test/java/org/jmonkeyengine/screenshottests/renderer/indirect/TestMdiNode.java
git commit -m "test(indirect): add MdiNode integration test with UnshadedMdi"
```

---

## Summary of what each layer provides

| Layer | Class | User controls | Auto-managed |
|-------|-------|--------------|--------------|
| Low-level | `MeshCombiner`, `IndirectCommandBuffer`, `BufferObject` | Everything | Nothing |
| Mid-level | `IndirectBatch` | Which meshes, when to build/update | Mesh combining, command buffer, SSBO layout+serialization |
| High-level | `MdiNode` | Attach/detach children, call `batch()` | Grouping, combining, command buffer, SSBO, per-frame transform updates |

Custom materials work at all levels by declaring a `DrawData` block in their `.j3md` and writing a matching GLSL struct.
