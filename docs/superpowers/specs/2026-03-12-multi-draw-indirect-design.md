# Multi-Draw Indirect (MDI) Support for jMonkeyEngine

**Date:** 2026-03-12
**Status:** Approved
**Minimum GL Version:** OpenGL 4.3 (base MDI), OpenGL 4.6 (count variants)

## Overview

Add multi-draw indirect support to jMonkeyEngine, enabling batching of multiple draw calls into a single GPU command. This includes core GL plumbing (all indirect draw variants) and a high-level `IndirectCommandBuffer` API that wraps the existing `BufferObject` infrastructure.

MDI allows the GPU to execute many draw calls from a single command buffer, dramatically reducing CPU overhead for scenes with many objects sharing the same vertex format and material. Combined with compute shaders, the GPU can also determine *which* and *how many* draws to execute (GPU-driven rendering).

## Architecture

### Approach

Extend the existing `BufferObject` with a new buffer target concept (`GL_DRAW_INDIRECT_BUFFER`). The convenience API (`IndirectCommandBuffer`) wraps a `BufferObject` internally and provides typed command builders. For GPU-driven rendering, the same `BufferObject` gets dual-bound — SSBO for compute shader writes, indirect buffer for the draw call.

### Why BufferObject

`BufferObject` already provides dirty region tracking, access/nature hints (`Dynamic`/`Static`/`Stream`), lifecycle management via `NativeObject`, and integration with the `Renderer` pipeline. An indirect command buffer is fundamentally a `BufferObject` bound to `GL_DRAW_INDIRECT_BUFFER` instead of `GL_SHADER_STORAGE_BUFFER`. Reusing it avoids duplicating buffer lifecycle management.

## Design

### 1. GL4 Interface Additions

Six new methods on `GL4.java`:

```java
// Single indirect draws (core in GL 4.3)
void glDrawElementsIndirect(int mode, int type, long indirect);
void glDrawArraysIndirect(int mode, long indirect);

// Multi-draw indirect (core in GL 4.3)
void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawCount, int stride);
void glMultiDrawArraysIndirect(int mode, long indirect, int drawCount, int stride);

// Count variants — GPU decides draw count (core in GL 4.6)
void glMultiDrawElementsIndirectCount(int mode, int type, long indirect, long drawCount, int maxDrawCount, int stride);
void glMultiDrawArraysIndirectCount(int mode, long indirect, long drawCount, int maxDrawCount, int stride);
```

New constants on `GL4.java`:

```java
int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
int GL_PARAMETER_BUFFER = 0x80EE;
int GL_COMMAND_BARRIER_BIT = 0x00000040;
```

### 2. LWJGL3 Backend

The LWJGL3 backend implements GL4 inside `LwjglGL.java` (`com.jme3.renderer.lwjgl.LwjglGL` which implements `GL, GL2, GL3, GL4`). The six new methods are added there, delegating to LWJGL3's `GL43` (for base MDI) and `GL46` (for count variants).

### 3. Caps

Two new entries in `Caps.java`:

- `MultiDrawIndirect` — gated on OpenGL 4.3 detection. Enables `renderMeshIndirect` and `renderMeshMultiIndirect`.
- `MultiDrawIndirectCount` — gated on OpenGL 4.6 detection. Enables `renderMeshMultiIndirectCount`. Requires adding `OpenGL46` and `GLSL460` caps and the corresponding version detection logic in `GLRenderer`.

New caps to add:
- `OpenGL46`
- `GLSL460`
- `MultiDrawIndirect`
- `MultiDrawIndirectCount`

### 4. RenderContext

Two new fields:

- `int boundDrawIndirectBuffer` — tracks the currently bound `GL_DRAW_INDIRECT_BUFFER` to avoid redundant binds.
- `int boundParameterBuffer` — tracks the currently bound `GL_PARAMETER_BUFFER` for count variants.

Both fields must be reset to `0` in `RenderContext.reset()`.

### 5. BufferObject Target Extension

New `BufferType` enum added to `BufferObject`:

```java
public enum BufferType {
    ShaderStorageBuffer,    // GL_SHADER_STORAGE_BUFFER (existing default)
    DrawIndirectBuffer,     // GL_DRAW_INDIRECT_BUFFER
    ParameterBuffer         // GL_PARAMETER_BUFFER (for indirect count)
}
```

`BufferObject` gains a `bufferType` field (default: `ShaderStorageBuffer` for backwards compatibility). The renderer uses this to determine which GL target to bind to.

**Serialization:** The `bufferType` field must be included in `BufferObject.write()` and `BufferObject.read()` for `JmeExporter`/`JmeImporter`, so that saved `BufferObject`s with non-default types are correctly restored.

**Dual-binding for GPU writes:** The underlying GL buffer ID is the same regardless of bind target. The renderer:
1. Uploads/updates the buffer using its primary `bufferType` target
2. Supports `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, id)` for compute shader access regardless of `bufferType`
3. Binds to `GL_DRAW_INDIRECT_BUFFER` at draw time when used for indirect rendering

**Buffer upload:** GLRenderer resolves the GL target from `BufferType` internally. A private helper method maps `BufferType` to the GL constant and calls the existing `updateBufferData(int target, BufferObject bo)`. No new public `Renderer` methods needed for buffer upload — the `renderMesh*Indirect` methods handle upload internally if the buffer is dirty.

### 6. Renderer Interface

Three new methods on `Renderer.java` that throw `UnsupportedOperationException` by default (consistent with the engine's pattern of failing loudly for unsupported features):

```java
/**
 * Render using a single indirect draw command.
 * @param mesh the mesh defining vertex format and buffers
 * @param commandBuffer the indirect command buffer
 * @param byteOffset byte offset into the command buffer (not a command index)
 */
default void renderMeshIndirect(Mesh mesh, BufferObject commandBuffer, long byteOffset) {
    throw new UnsupportedOperationException("Indirect rendering not supported by this renderer");
}

/**
 * Render using multiple indirect draw commands.
 * @param mesh the mesh defining vertex format and buffers
 * @param commandBuffer the indirect command buffer
 * @param drawCount number of draw commands
 * @param byteOffset byte offset into the command buffer (not a command index)
 */
default void renderMeshMultiIndirect(Mesh mesh, BufferObject commandBuffer,
    int drawCount, long byteOffset) {
    throw new UnsupportedOperationException("Indirect rendering not supported by this renderer");
}

/**
 * Render using multiple indirect draw commands with GPU-determined count.
 * @param mesh the mesh defining vertex format and buffers
 * @param commandBuffer the indirect command buffer
 * @param countBuffer buffer containing the draw count (at GL_PARAMETER_BUFFER target)
 * @param maxDrawCount upper bound on draw count
 * @param byteOffset byte offset into the command buffer (not a command index)
 */
default void renderMeshMultiIndirectCount(Mesh mesh, BufferObject commandBuffer,
    BufferObject countBuffer, int maxDrawCount, long byteOffset) {
    throw new UnsupportedOperationException("Indirect rendering with count not supported by this renderer");
}
```

Indexed vs array mode is determined from the mesh (has index buffer -> Elements variant, otherwise Arrays variant).

### 7. GLRenderer Implementation

The three `renderMesh*` methods in `GLRenderer`:

1. Check capabilities — `Caps.MultiDrawIndirect` for the first two methods, `Caps.MultiDrawIndirectCount` for the count variant. Throw `RendererException` if not supported.
2. Ensure `BufferObject` is uploaded/up-to-date. Use a private helper that resolves `BufferType` to the GL target constant and delegates to `updateBufferData(int target, BufferObject bo)`.
3. Bind the indirect buffer to `GL_DRAW_INDIRECT_BUFFER` (with `RenderContext.boundDrawIndirectBuffer` caching).
4. Set up vertex attributes from the mesh (reuse existing `setVertexAttrib` logic).
5. Issue `glMemoryBarrier(GL_COMMAND_BARRIER_BIT)` unconditionally before every indirect draw call. The cost is negligible compared to the draw call savings, and it guarantees correctness for GPU-written buffers without requiring a tracking flag.
6. Call the appropriate `gl4.glMultiDraw*` / `gl4.glDraw*Indirect` method. For tightly-packed commands (the common case), pass `stride = 0` which OpenGL interprets as the natural struct size.
7. For count variants, also bind the count buffer to `GL_PARAMETER_BUFFER` (with `RenderContext.boundParameterBuffer` caching).

### 8. Convenience API

New package: `com.jme3.renderer.indirect`

#### DrawElementsIndirectCommand

```java
public class DrawElementsIndirectCommand {
    public int count;         // index count per instance
    public int instanceCount; // number of instances
    public int firstIndex;    // offset into index buffer
    public int baseVertex;    // added to each index value
    public int baseInstance;  // base instance for instanced attributes
    // 20 bytes total (5 x 4)
}
```

#### DrawArraysIndirectCommand

```java
public class DrawArraysIndirectCommand {
    public int count;         // vertex count per instance
    public int instanceCount; // number of instances
    public int first;         // first vertex
    public int baseInstance;  // base instance for instanced attributes
    // 16 bytes total (4 x 4)
}
```

#### IndirectCommandBuffer

```java
public class IndirectCommandBuffer {

    public enum DrawType { Elements, Arrays }

    private final DrawType drawType;
    private final BufferObject bufferObject;
    private int commandCount;

    public IndirectCommandBuffer(DrawType drawType) { ... }

    // Builder-style convenience — validates DrawType matches command type,
    // throws IllegalArgumentException if mismatched
    public IndirectCommandBuffer addCommand(DrawElementsIndirectCommand cmd) { ... }
    public IndirectCommandBuffer addCommand(DrawArraysIndirectCommand cmd) { ... }

    // Batch add
    public IndirectCommandBuffer setCommands(List<DrawElementsIndirectCommand> cmds) { ... }

    // Direct access for advanced users / GPU-driven
    public BufferObject getBufferObject() { ... }
    public ByteBuffer getRawBuffer() { ... }

    // Pre-allocate space for N commands (GPU-driven scenario)
    public IndirectCommandBuffer allocate(int maxCommands) { ... }

    // Mark dirty for upload
    public void update() { ... }

    // Debug-mode assertion validation against a mesh
    public void validateAgainst(Mesh mesh) { ... }

    public int getCommandCount() { ... }

    // Returns 0 for tightly-packed (OpenGL interprets as natural struct size).
    // This is the idiomatic usage and is always correct for the convenience API.
    public int getStride() { return 0; }
}
```

**DrawType validation:** `addCommand(DrawElementsIndirectCommand)` throws `IllegalArgumentException` if `drawType` is `Arrays`, and vice versa. This catches mismatches immediately rather than producing corrupt buffer data.

### 9. Count Buffer

For GPU-driven rendering with `glMultiDrawElementsIndirectCount` / `glMultiDrawArraysIndirectCount`, a separate `BufferObject` with `BufferType.ParameterBuffer` holds the actual draw count. A compute shader writes both the command buffer (as SSBO) and the count buffer (as SSBO), then the renderer binds them to their respective targets.

## Usage Examples

### CPU-side MDI

```java
IndirectCommandBuffer buf = new IndirectCommandBuffer(DrawType.Elements);
buf.addCommand(new DrawElementsIndirectCommand(indexCount1, 10, 0, 0, 0));
buf.addCommand(new DrawElementsIndirectCommand(indexCount2, 5, firstIdx, baseVtx, 10));
buf.update();

renderer.renderMeshMultiIndirect(sharedMesh, buf.getBufferObject(),
    buf.getCommandCount(), 0);
```

### GPU-driven rendering

```java
// Pre-allocate command buffer
IndirectCommandBuffer cmdBuf = new IndirectCommandBuffer(DrawType.Elements);
cmdBuf.allocate(1024);

// Count buffer
BufferObject countBuffer = new BufferObject();
countBuffer.setBufferType(BufferType.ParameterBuffer);
countBuffer.setAccessHint(AccessHint.Dynamic);
countBuffer.setNatureHint(NatureHint.Copy);

// Bind as SSBOs for compute shader
cullMaterial.setBufferObject("IndirectCommands", cmdBuf.getBufferObject());
cullMaterial.setBufferObject("DrawCount", countBuffer);

// Compute shader fills both buffers...
// Then draw with GPU-determined count
renderer.renderMeshMultiIndirectCount(sharedMesh, cmdBuf.getBufferObject(),
    countBuffer, 1024, 0);
```

## Files Changed

### Modified

| File | Changes |
|------|---------|
| `jme3-core/.../renderer/Caps.java` | Add `OpenGL46`, `GLSL460`, `MultiDrawIndirect`, `MultiDrawIndirectCount` |
| `jme3-core/.../renderer/opengl/GL4.java` | Add 6 indirect draw methods + 3 constants |
| `jme3-lwjgl3/.../renderer/lwjgl/LwjglGL.java` | LWJGL3 implementation of the 6 methods (this class implements `GL, GL2, GL3, GL4`) |
| `jme3-core/.../shader/bufferobject/BufferObject.java` | Add `BufferType` enum with 3 variants, serialize in `write()`/`read()` |
| `jme3-core/.../renderer/Renderer.java` | Add 3 `renderMesh*Indirect` methods (default throws `UnsupportedOperationException`) |
| `jme3-core/.../renderer/opengl/GLRenderer.java` | Implement the 3 render methods, indirect buffer binding, memory barriers, GL 4.6 version detection |
| `jme3-core/.../renderer/RenderContext.java` | Add `boundDrawIndirectBuffer` and `boundParameterBuffer` fields, update `reset()` |

### New Files

| File | Purpose |
|------|---------|
| `jme3-core/.../renderer/indirect/DrawElementsIndirectCommand.java` | Indexed command struct (20 bytes) |
| `jme3-core/.../renderer/indirect/DrawArraysIndirectCommand.java` | Array command struct (16 bytes) |
| `jme3-core/.../renderer/indirect/IndirectCommandBuffer.java` | Convenience wrapper with builder API |

### Not Touched

- `ShaderStorageBufferObject.java` — separate concern
- `Mesh.java` — no changes needed
- `VertexBuffer.java` — no changes needed
