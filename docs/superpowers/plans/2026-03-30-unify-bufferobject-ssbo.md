# Unify BufferObject and ShaderStorageBufferObject Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Delete ShaderStorageBufferObject, add read-back to BufferObject, migrate all consumers.

**Architecture:** BufferObject becomes the single GPU buffer abstraction. Read-back goes through the Renderer interface. All GL calls stay in GLRenderer.

**Tech Stack:** Java, OpenGL (GL4), jMonkeyEngine renderer

---

### Task 1: Add read-back to BufferObject (fix/ssbo-ubo-binding branch)

**Files:**
- Modify: `jme3-core/src/main/java/com/jme3/shader/bufferobject/BufferObject.java`

- [ ] **Step 1: Add BufferType enum to BufferObject**

BufferType does not exist on the fix branch. Add it for target selection:

```java
public static enum BufferType {
    ShaderStorageBuffer
}
```

Add field `private BufferType bufferType = BufferType.ShaderStorageBuffer;` with getter/setter.

- [ ] **Step 2: Commit**

```bash
git commit -m "feat(bufferobject): add BufferType enum and read-back support"
```

### Task 2: Add readBufferObjectData to Renderer interfaces (fix/ssbo-ubo-binding)

**Files:**
- Modify: `jme3-core/src/main/java/com/jme3/renderer/Renderer.java`
- Modify: `jme3-core/src/main/java/com/jme3/renderer/opengl/GLRenderer.java`
- Modify: `jme3-core/src/main/java/com/jme3/system/NullRenderer.java`

- [ ] **Step 1: Add method to Renderer interface**

```java
public void readBufferObjectData(BufferObject bo);
```

- [ ] **Step 2: Implement in GLRenderer**

```java
@Override
public void readBufferObjectData(BufferObject bo) {
    int target = GL4.GL_SHADER_STORAGE_BUFFER; // extend for other types as needed
    gl.glBindBuffer(target, bo.getId());
    ByteBuffer buf = bo.getData();
    gl.glGetBufferSubData(target, 0, buf);
    gl.glBindBuffer(target, 0);
}
```

- [ ] **Step 3: Add no-op to NullRenderer**

- [ ] **Step 4: Commit**

### Task 3: Migrate SdsmFitter (fix/ssbo-ubo-binding)

**Files:**
- Modify: `jme3-core/src/main/java/com/jme3/shadow/SdsmFitter.java`

- [ ] **Step 1: Replace ShaderStorageBufferObject with BufferObject**

Change field types, constructor, and read calls. Remove GL4 field if no longer needed.

- [ ] **Step 2: Commit**

### Task 4: Migrate ComputeShader (fix/ssbo-ubo-binding)

**Files:**
- Modify: `jme3-core/src/main/java/com/jme3/renderer/opengl/ComputeShader.java`

- [ ] **Step 1: Change bindShaderStorageBuffer to accept BufferObject**

- [ ] **Step 2: Commit**

### Task 5: Delete ShaderStorageBufferObject (fix/ssbo-ubo-binding)

**Files:**
- Delete: `jme3-core/src/main/java/com/jme3/renderer/opengl/ShaderStorageBufferObject.java`

- [ ] **Step 1: Delete the file and remove any remaining imports**

- [ ] **Step 2: Commit**

### Task 6: Apply same changes to main branch

- [ ] **Step 1: Cherry-pick or reimplement Tasks 1-5 on main**
- [ ] **Step 2: Additionally migrate main-only consumers (TestBindlessTextureWithSsbo, TestMdiBindlessTextures)**
- [ ] **Step 3: Commit**
