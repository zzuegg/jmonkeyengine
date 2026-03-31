# Design: Unify BufferObject and ShaderStorageBufferObject

## Problem

`ShaderStorageBufferObject` and `BufferObject` both represent GPU buffer objects but are separate class hierarchies. `ShaderStorageBufferObject` extends `NativeObject` directly, does its own GL calls (bypassing the renderer), and duplicates buffer lifecycle management. This creates two paths for SSBO usage: one through the material system (`BufferObject` + `ShaderBufferBlock`) and one direct (`ShaderStorageBufferObject` in compute/SDSM).

In OpenGL, all buffer objects share the same lifecycle (create, upload, bind, read, delete) — the only difference is the binding target. The class hierarchy should reflect this.

## Decision

Delete `ShaderStorageBufferObject`. Add read-back capability to `BufferObject`. All GL calls go through the renderer.

## Changes

### BufferObject

Add read-back API. The renderer fills the data buffer; `BufferObject` exposes it:

```java
public ByteBuffer readBack() { return data; }
```

### Renderer interface

New method for GPU-to-CPU read-back:

```java
public void readBufferObjectData(BufferObject bo);
```

### GLRenderer

Implements read-back using `glGetBufferSubData`, selecting the GL target from `BufferObject.getBufferType()`:

```java
public void readBufferObjectData(BufferObject bo) {
    int target = getTargetForBufferType(bo.getBufferType());
    gl.glBindBuffer(target, bo.getId());
    ByteBuffer buf = bo.getData();
    gl.glGetBufferSubData(target, 0, buf);
    gl.glBindBuffer(target, 0);
}
```

### NullRenderer

No-op implementation of `readBufferObjectData`.

### ShaderStorageBufferObject

Deleted entirely. Replacement mapping:

| Old (ShaderStorageBufferObject) | New (BufferObject) |
|---|---|
| `new ShaderStorageBufferObject(gl4)` | `new BufferObject(); bo.setBufferType(BufferType.ShaderStorageBuffer)` |
| `ssbo.initialize(int[] data)` | `bo.setData(ByteBuffer)` + renderer upload |
| `ssbo.read(count)` | `renderer.readBufferObjectData(bo); bo.getData()` |

### SdsmFitter

Replace `ShaderStorageBufferObject` with `BufferObject`. Already has a `Renderer` reference, so no new dependencies needed. The `GL4` field can be removed if only used for SSBOs.

### ComputeShader (deprecated)

Update `bindShaderStorageBuffer()` to accept `BufferObject` instead of `ShaderStorageBufferObject`. Class remains deprecated.

## Scope per branch

**`fix/ssbo-ubo-binding` (PR branch targeting official jME):**
- BufferObject read-back
- Renderer/GLRenderer/NullRenderer read-back method
- Delete ShaderStorageBufferObject
- Migrate SdsmFitter and ComputeShader
- Existing SSBO binding fixes and tests preserved

**`main` (local branch):**
- Same changes as above
- Additional migrations for MDI, bindless, and compute dispatch consumers of ShaderStorageBufferObject
