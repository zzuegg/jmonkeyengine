# Bindless Textures Support Evaluation for jMonkeyEngine

## Executive Summary

Adding bindless texture support (ARB_bindless_texture) to jMonkeyEngine is **feasible** and the engine's architecture is reasonably well-structured to accommodate it, but it would require changes across multiple layers of the rendering stack. The effort is moderate-to-high, with the primary challenges being backward compatibility, the material/shader system integration, and the fact that bindless textures are an **optional extension** not available on all hardware.

---

## 1. What Are Bindless Textures?

In traditional OpenGL rendering, textures are bound to numbered **texture units** (e.g., `GL_TEXTURE0` through `GL_TEXTURE15`). The shader references them via `uniform sampler2D` at an integer binding point. This creates a hard limit on the number of simultaneously bound textures (typically 16-32 per stage).

**Bindless textures** (`GL_ARB_bindless_texture`, core in no GL version, available as extension since GL 4.0) eliminate this bottleneck:

1. You obtain a 64-bit **texture handle** via `glGetTextureHandleARB(texId)`
2. You make the handle **resident** via `glMakeTextureHandleResidentARB(handle)`
3. You pass the handle to shaders as a `uint64` uniform (or via SSBO/UBO)
4. In GLSL, you cast the `uint64` to a sampler type using `sampler2D(handle)`

**Benefits:**
- No texture unit limit — thousands of textures accessible simultaneously
- Reduced CPU overhead from bind/unbind state changes
- Enables data-driven rendering (texture arrays of handles in SSBOs)
- Critical enabler for GPU-driven rendering pipelines

**Drawbacks:**
- Not universally supported (requires ARB_bindless_texture extension)
- Resident textures consume GPU memory that cannot be paged
- Requires careful lifecycle management (handles must be non-resident before deletion)
- No OpenGL ES support

---

## 2. Current jMonkeyEngine Texture Architecture

### 2.1 Texture Binding Flow

The current rendering pipeline follows the traditional bind-per-unit model:

```
Material.render()
  → for each MatParamOverride/MatParam with texture type:
      → Renderer.setTexture(unit, texture)
        → GLRenderer.setTexture(unit, tex)
          → updateTexImageData() if needed (glGenTextures, upload)
          → setupTextureParams(unit, tex)
            → bindTextureAndUnit(target, img, unit)
              → gl.glActiveTexture(GL_TEXTURE0 + unit)
              → gl.glBindTexture(target, img.getId())
```

Key file: `GLRenderer.java:2724-2758`

### 2.2 Texture Unit Management

- Texture units are assigned by the `Uniform` system — each sampler uniform gets a `textureUnit` index
- Maximum units tracked in `RenderContext.maxTextureUnits` (typically 16-32)
- The `context.boundTextures[]` array tracks what's bound to each unit via `WeakReference<Image>`
- State caching avoids redundant binds: `GLRenderer.java:2550-2561`

### 2.3 Shader Uniform System

Textures are declared as `VarType.Texture2D`, `VarType.TextureCubeMap`, etc. in `VarType.java`. These map to GLSL sampler types (`sampler2D`, `samplerCube`, etc.).

The `Uniform` class stores per-uniform data and is set via `glUniform1i(location, textureUnit)` — passing the texture unit index, not a handle.

### 2.4 GL Abstraction Layer

```
GL.java       → OpenGL 2.0 baseline (glBindTexture, glActiveTexture, glGenTextures)
GL2.java      → Desktop GL2 specifics
GL3.java      → GL 3.0+ (UBO support)
GL4.java      → GL 4.0+ (SSBO, compute, tessellation)
GLExt.java    → Extensions
LwjglGL.java  → LWJGL3 implementation (delegates to org.lwjgl.opengl.*)
```

### 2.5 Capabilities System

`Caps.java` enumerates supported features. `GLRenderer.loadCapabilitiesCommon()` checks extensions and GL version to populate the caps set. No bindless-related capabilities exist today.

---

## 3. Required Changes

### 3.1 Layer 1: GL Abstraction (Low Risk)

**Files:** `GLExt.java`, `LwjglGLExt.java`

Add bindless texture GL functions to the extension interface:

```java
// In GLExt.java
long glGetTextureHandleARB(int texture);
long glGetTextureSamplerHandleARB(int texture, int sampler);
void glMakeTextureHandleResidentARB(long handle);
void glMakeTextureHandleNonResidentARB(long handle);
void glUniformHandleui64ARB(int location, long value);
boolean glIsTextureHandleResidentARB(long handle);
```

LWJGL 3.4.1 (currently used by jME) exposes `org.lwjgl.opengl.ARBBindlessTexture` — these bindings are already available, so the LWJGL implementation is straightforward delegation.

**Effort:** Small — ~50 lines of interface + implementation.

### 3.2 Layer 2: Capability Detection (Low Risk)

**Files:** `Caps.java`, `GLRenderer.java`

```java
// In Caps.java
BindlessTexture,  // GL_ARB_bindless_texture

// In GLRenderer.loadCapabilitiesCommon()
if (hasExtension("GL_ARB_bindless_texture")) {
    caps.add(Caps.BindlessTexture);
}
```

**Effort:** Trivial — ~5 lines.

### 3.3 Layer 3: Texture Handle Management (Medium Risk)

**Files:** New `TextureHandleManager.java`, modifications to `Image.java` or `Texture.java`

Need to:
- Store 64-bit handles per texture (add `long handle` field to `Image` or `Texture`)
- Track residency state (a texture must be resident before shader access, non-resident before deletion)
- Manage handle lifecycle (get handle after texture upload, make resident, make non-resident before cleanup)
- Integrate with `NativeObjectManager` cleanup to ensure non-residency before deletion

```java
// Conceptual API
public class TextureHandleManager {
    long getHandle(Texture tex);        // glGetTextureHandleARB
    void makeResident(long handle);     // glMakeTextureHandleResidentARB
    void makeNonResident(long handle);  // glMakeTextureHandleNonResidentARB
    boolean isResident(long handle);    // glIsTextureHandleResidentARB
}
```

**Key concern:** Resident textures pin GPU memory. The engine needs a strategy for when to make textures resident/non-resident. Options:
- Make resident on first use, non-resident on cleanup (simple, high memory)
- LRU eviction with a resident budget (complex, better memory behavior)
- User-controlled residency via API (flexible, shifts burden to user)

**Effort:** Medium — ~200-300 lines, plus lifecycle integration.

### 3.4 Layer 4: Shader/Uniform System (High Risk)

**Files:** `VarType.java`, `Uniform.java`, `GLRenderer.java`, shader source files

This is the most impactful change. Two approaches:

#### Approach A: Transparent Replacement (sampler uniforms → uint64 handles)

- When bindless is available, sampler uniforms are uploaded as `uint64` handles via `glUniformHandleui64ARB()` instead of `glUniform1i(unit)`
- Shaders written with `uniform sampler2D` work without modification (the driver interprets the handle)
- Requires the shader compiler to understand that the uniform is being set as a handle

**Pros:** Minimal shader changes, backward compatible
**Cons:** Limited — still one uniform per texture, doesn't enable the "thousands of textures" use case

#### Approach B: SSBO-Based Texture Handle Arrays

- Store texture handles in an SSBO (array of `uint64`)
- Shaders index into the array to get the sampler handle
- Requires GLSL extension: `#extension GL_ARB_bindless_texture : require`

```glsl
#extension GL_ARB_bindless_texture : require
layout(std430, binding = 0) buffer TextureHandles {
    sampler2D textures[];
};
// Access: texture(textures[materialIndex], uv)
```

**Pros:** Enables GPU-driven rendering, material batching, massive draw call reduction
**Cons:** Requires new shader infrastructure, not backward compatible, needs SSBO support (GL 4.3+)

#### Recommended: Start with Approach A, evolve to B

Approach A can be implemented as an optimization in `GLRenderer.setTexture()` that's transparent to the material system. Approach B is a larger architectural change best pursued alongside a GPU-driven rendering pipeline.

**Effort:** Approach A: ~100-150 lines. Approach B: ~500+ lines plus shader infrastructure.

### 3.5 Layer 5: Material System Integration (Medium Risk)

**Files:** `Material.java`, `MatParam.java`, `TechniqueDef.java`

For Approach A, minimal changes — the material system continues assigning texture units, but `GLRenderer` uses handles instead of unit binds when bindless is available.

For Approach B, larger changes:
- New `VarType` entries for bindless handle references
- Material definitions need a way to declare bindless texture arrays
- The technique system needs to manage SSBO bindings for handle arrays

**Effort:** Approach A: ~20 lines. Approach B: ~300+ lines.

---

## 4. Hardware & Platform Support

### 4.1 Extension Availability

| GPU Vendor | Extension | Min Hardware | Notes |
|------------|-----------|-------------|-------|
| NVIDIA | GL_ARB_bindless_texture | Kepler (GTX 600+) | Excellent support since 2012 |
| NVIDIA | GL_NV_bindless_texture | Fermi (GTX 400+) | Proprietary precursor |
| AMD | GL_ARB_bindless_texture | GCN 1.0 (HD 7000+) | Supported since ~2014 |
| Intel | GL_ARB_bindless_texture | **Not supported** | Major gap — no Intel iGPU support |
| Apple | N/A | **Not supported** | macOS stuck at GL 4.1, no ARB_bindless |
| Mobile | N/A | **Not supported** | OpenGL ES has no bindless extension |

**Key takeaway:** Intel integrated GPUs (a large share of desktop/laptop market) do not support bindless textures. This makes it unsuitable as a required feature — it must be an optional optimization path.

### 4.2 LWJGL3 Support

LWJGL 3.4.1 includes `org.lwjgl.opengl.ARBBindlessTexture` with all necessary bindings. No LWJGL upgrade needed.

### 4.3 jME Platform Considerations

| Platform | Bindless Support |
|----------|-----------------|
| jme3-lwjgl3 (Desktop) | Yes, via ARBBindlessTexture |
| jme3-lwjgl (Legacy Desktop) | Yes, via ARBBindlessTexture |
| jme3-android | No (OpenGL ES) |
| jme3-ios | No (OpenGL ES / Metal) |

---

## 5. Impact Assessment

### 5.1 What Bindless Textures Enable

1. **Reduced state changes:** Eliminate `glBindTexture`/`glActiveTexture` calls — significant CPU savings in scenes with many materials
2. **Material batching:** Multiple objects with different textures can be drawn in a single draw call if using SSBO approach
3. **Texture unit limit removal:** No longer limited to 16-32 textures per draw call
4. **GPU-driven rendering foundation:** Essential for indirect drawing with `glMultiDrawElementsIndirect` where the GPU selects materials

### 5.2 What It Doesn't Solve Alone

- Still need indirect draw infrastructure for full GPU-driven rendering
- Doesn't help with CPU-side scene graph traversal overhead
- Material sorting/batching logic in `RenderManager` would need rework to take advantage

### 5.3 Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Breaking existing materials | Medium | High | Feature-flag behind `Caps.BindlessTexture`, fallback to unit-based |
| GPU memory pressure from resident textures | Medium | Medium | Residency budget, lazy residency |
| Complexity in cleanup/lifecycle | Medium | Medium | Integrate with `NativeObjectManager` |
| Intel/mobile users can't use it | Certain | Low | Optional optimization, not required |
| Shader compatibility issues | Low | Medium | Approach A is transparent to shaders |

---

## 6. Implementation Roadmap

### Phase 1: Foundation (Estimated scope: ~400 lines changed)
1. Add `GL_ARB_bindless_texture` function bindings to `GLExt.java` + LWJGL implementation
2. Add `Caps.BindlessTexture` capability detection
3. Add handle storage to `Image` class (long field)
4. Implement `TextureHandleManager` for handle lifecycle
5. Integrate with `NativeObjectManager` for cleanup

### Phase 2: Transparent Optimization (Estimated scope: ~200 lines changed)
1. Modify `GLRenderer.setTexture()` to use handles when bindless is available
2. Use `glUniformHandleui64ARB()` instead of `glUniform1i()` for sampler uniforms
3. Skip `glActiveTexture`/`glBindTexture` calls when using handles
4. Add residency management (make resident on use, track state)

### Phase 3: Advanced — SSBO-Based Handles (Estimated scope: ~800+ lines)
1. New material system support for bindless texture arrays
2. SSBO-based handle storage for GPU-driven rendering
3. Shader library support (`#extension GL_ARB_bindless_texture`)
4. Integration with instanced/indirect rendering

---

## 7. Recommendation

**Start with Phase 1 + Phase 2** (Approach A — transparent optimization). This provides:
- Measurable CPU-side performance improvement for texture-heavy scenes
- Zero breaking changes to existing materials and shaders
- Clean fallback path for unsupported hardware
- Foundation for future GPU-driven rendering work

Phase 3 should be pursued as part of a broader GPU-driven rendering initiative, as bindless texture arrays via SSBO are most valuable when combined with indirect draw calls and compute-based culling.

The engine's layered GL abstraction (`GL`/`GLExt` interfaces → LWJGL implementation) and capability system (`Caps` enum) are well-designed for adding optional extensions like this. The main architectural challenge is ensuring texture handle lifecycle (residency management) integrates cleanly with the existing `NativeObjectManager` garbage-collection-based cleanup system.
