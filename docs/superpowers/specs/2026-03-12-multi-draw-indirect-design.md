# Multi-Draw Indirect (MDI) Support for jMonkeyEngine

**Date:** 2026-03-12
**Status:** Approved
**Minimum GL Version:** OpenGL 4.6

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
// Single indirect draws
void glDrawElementsIndirect(int mode, int type, long indirect);
void glDrawArraysIndirect(int mode, long indirect);

// Multi-draw indirect
void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawCount, int stride);
void glMultiDrawArraysIndirect(int mode, long indirect, int drawCount, int stride);

// Count variants (GPU decides draw count)
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

`LwjglGL4.java` implements the six methods by delegating to LWJGL3's `GL46` class.

### 3. Caps

Single new entry in `Caps.java`: `MultiDrawIndirect`, gated on OpenGL 4.6 detection.

### 4. RenderContext

New field: `int boundDrawIndirectBuffer` — tracks the currently bound `GL_DRAW_INDIRECT_BUFFER` to avoid redundant binds.

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

**Dual-binding for GPU writes:** The underlying GL buffer ID is the same regardless of bind target. The renderer:
1. Uploads/updates the buffer using its primary `bufferType` target
2. Supports `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, id)` for compute shader access regardless of `bufferType`
3. Binds to `GL_DRAW_INDIRECT_BUFFER` at draw time when used for indirect rendering

### 6. Renderer Interface

Three new default methods on `Renderer.java`:

```java
/**
 * Render using a single indirect draw command.
 */
default void renderMeshIndirect(Mesh mesh, BufferObject commandBuffer, long offset) {}

/**
 * Render using multiple indirect draw commands.
 */
default void renderMeshMultiIndirect(Mesh mesh, BufferObject commandBuffer,
    int drawCount, long offset) {}

/**
 * Render using multiple indirect draw commands with GPU-determined count.
 */
default void renderMeshMultiIndirectCount(Mesh mesh, BufferObject commandBuffer,
    BufferObject countBuffer, int maxDrawCount, long offset) {}
```

Indexed vs array mode is determined from the mesh (has index buffer -> Elements variant, otherwise Arrays variant).

### 7. GLRenderer Implementation

The three `renderMesh*` methods in `GLRenderer`:

1. Check `Caps.MultiDrawIndirect` — throw `RendererException` if not supported
2. Ensure `BufferObject` is uploaded/up-to-date via existing buffer update pipeline
3. Bind the indirect buffer to `GL_DRAW_INDIRECT_BUFFER` (with `RenderContext` caching)
4. Set up vertex attributes from the mesh (reuse existing `setVertexAttrib` logic)
5. If the buffer was used as an SSBO (GPU-written commands), issue `glMemoryBarrier(GL_COMMAND_BARRIER_BIT)`
6. Call the appropriate `gl4.glMultiDraw*` / `gl4.glDraw*Indirect` method
7. For count variants, also bind the count buffer to `GL_PARAMETER_BUFFER`

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

    // Builder-style convenience
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

    // Debug-mode assertion validation
    public void validateAgainst(Mesh mesh) { ... }

    public int getCommandCount() { ... }
    public int getStride() { ... }  // 20 for Elements, 16 for Arrays
}
```

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
| `jme3-core/.../renderer/Caps.java` | Add `MultiDrawIndirect` |
| `jme3-core/.../renderer/opengl/GL4.java` | Add 6 indirect draw methods + 3 constants |
| `jme3-lwjgl3/.../renderer/lwjgl/LwjglGL4.java` | LWJGL3 implementation of the 6 methods |
| `jme3-core/.../shader/bufferobject/BufferObject.java` | Add `BufferType` enum with 3 variants |
| `jme3-core/.../renderer/Renderer.java` | Add 3 renderMesh*Indirect methods |
| `jme3-core/.../renderer/opengl/GLRenderer.java` | Implement the 3 render methods, indirect buffer binding, memory barriers |
| `jme3-core/.../renderer/RenderContext.java` | Add `boundDrawIndirectBuffer` field |

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
