/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.renderer.indirect;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * High-level wrapper around a {@link BufferObject} for building indirect draw command buffers.
 * <p>
 * Supports both CPU-side command building via typed command structs and GPU-side writes
 * by pre-allocating space and exposing the underlying {@link BufferObject} for SSBO binding.
 * <p>
 * Usage (CPU-side):
 * <pre>
 * IndirectCommandBuffer buf = new IndirectCommandBuffer(DrawType.Elements);
 * buf.addCommand(new DrawElementsIndirectCommand(36, 10, 0, 0, 0));
 * buf.addCommand(new DrawElementsIndirectCommand(24, 5, 36, 100, 10));
 * buf.update();
 * renderer.renderMeshMultiIndirect(mesh, buf.getBufferObject(), buf.getCommandCount(), 0);
 * </pre>
 * <p>
 * Usage (GPU-driven):
 * <pre>
 * IndirectCommandBuffer buf = new IndirectCommandBuffer(DrawType.Elements);
 * buf.allocate(1024);
 * material.setBufferObject("Commands", buf.getBufferObject());
 * // compute shader writes commands...
 * renderer.renderMeshMultiIndirectCount(mesh, buf.getBufferObject(), countBuf, 1024, 0);
 * </pre>
 */
public class IndirectCommandBuffer {

    /**
     * Whether commands are indexed (DrawElements) or non-indexed (DrawArrays).
     */
    public enum DrawType {
        /** Indexed drawing — uses DrawElementsIndirectCommand (20 bytes per command). */
        Elements,
        /** Non-indexed drawing — uses DrawArraysIndirectCommand (16 bytes per command). */
        Arrays
    }

    private final DrawType drawType;
    private final BufferObject bufferObject;
    private int commandCount;
    private ByteBuffer cpuBuffer;

    /**
     * Creates a new IndirectCommandBuffer with the given draw type.
     *
     * @param drawType whether commands use indexed or non-indexed drawing
     */
    public IndirectCommandBuffer(DrawType drawType) {
        this.drawType = drawType;
        this.bufferObject = new BufferObject();
        this.bufferObject.setBufferType(BufferObject.BufferType.DrawIndirectBuffer);
        this.bufferObject.setAccessHint(BufferObject.AccessHint.Dynamic);
        this.bufferObject.setNatureHint(BufferObject.NatureHint.Draw);
    }

    /**
     * Adds an indexed draw command. The buffer must have been created with {@link DrawType#Elements}.
     *
     * @param cmd the draw elements command
     * @return this buffer for chaining
     * @throws IllegalArgumentException if drawType is not Elements
     */
    public IndirectCommandBuffer addCommand(DrawElementsIndirectCommand cmd) {
        if (drawType != DrawType.Elements) {
            throw new IllegalArgumentException("Cannot add DrawElementsIndirectCommand to a DrawType.Arrays buffer");
        }
        ensureCpuCapacity(DrawElementsIndirectCommand.STRIDE);
        cpuBuffer.putInt(cmd.count);
        cpuBuffer.putInt(cmd.instanceCount);
        cpuBuffer.putInt(cmd.firstIndex);
        cpuBuffer.putInt(cmd.baseVertex);
        cpuBuffer.putInt(cmd.baseInstance);
        commandCount++;
        return this;
    }

    /**
     * Adds a non-indexed draw command. The buffer must have been created with {@link DrawType#Arrays}.
     *
     * @param cmd the draw arrays command
     * @return this buffer for chaining
     * @throws IllegalArgumentException if drawType is not Arrays
     */
    public IndirectCommandBuffer addCommand(DrawArraysIndirectCommand cmd) {
        if (drawType != DrawType.Arrays) {
            throw new IllegalArgumentException("Cannot add DrawArraysIndirectCommand to a DrawType.Elements buffer");
        }
        ensureCpuCapacity(DrawArraysIndirectCommand.STRIDE);
        cpuBuffer.putInt(cmd.count);
        cpuBuffer.putInt(cmd.instanceCount);
        cpuBuffer.putInt(cmd.first);
        cpuBuffer.putInt(cmd.baseInstance);
        commandCount++;
        return this;
    }

    /**
     * Replaces all commands with the given list of elements draw commands.
     *
     * @param cmds the commands to set
     * @return this buffer for chaining
     * @throws IllegalArgumentException if drawType is not Elements
     */
    public IndirectCommandBuffer setElementsCommands(List<DrawElementsIndirectCommand> cmds) {
        if (drawType != DrawType.Elements) {
            throw new IllegalArgumentException("Cannot set DrawElementsIndirectCommand list on a DrawType.Arrays buffer");
        }
        commandCount = 0;
        int totalBytes = cmds.size() * DrawElementsIndirectCommand.STRIDE;
        if (cpuBuffer != null) {
            BufferUtils.destroyDirectBuffer(cpuBuffer);
        }
        cpuBuffer = BufferUtils.createByteBuffer(totalBytes);
        for (DrawElementsIndirectCommand cmd : cmds) {
            cpuBuffer.putInt(cmd.count);
            cpuBuffer.putInt(cmd.instanceCount);
            cpuBuffer.putInt(cmd.firstIndex);
            cpuBuffer.putInt(cmd.baseVertex);
            cpuBuffer.putInt(cmd.baseInstance);
            commandCount++;
        }
        return this;
    }

    /**
     * Replaces all commands with the given list of array draw commands.
     *
     * @param cmds the commands to set
     * @return this buffer for chaining
     * @throws IllegalArgumentException if drawType is not Arrays
     */
    public IndirectCommandBuffer setArraysCommands(List<DrawArraysIndirectCommand> cmds) {
        if (drawType != DrawType.Arrays) {
            throw new IllegalArgumentException("Cannot set DrawArraysIndirectCommand list on a DrawType.Elements buffer");
        }
        commandCount = 0;
        int totalBytes = cmds.size() * DrawArraysIndirectCommand.STRIDE;
        if (cpuBuffer != null) {
            BufferUtils.destroyDirectBuffer(cpuBuffer);
        }
        cpuBuffer = BufferUtils.createByteBuffer(totalBytes);
        for (DrawArraysIndirectCommand cmd : cmds) {
            cpuBuffer.putInt(cmd.count);
            cpuBuffer.putInt(cmd.instanceCount);
            cpuBuffer.putInt(cmd.first);
            cpuBuffer.putInt(cmd.baseInstance);
            commandCount++;
        }
        return this;
    }

    /**
     * Pre-allocate space for a given number of commands.
     * Used for GPU-driven rendering where a compute shader writes the commands.
     *
     * @param maxCommands maximum number of commands
     * @return this buffer for chaining
     */
    public IndirectCommandBuffer allocate(int maxCommands) {
        if (cpuBuffer != null) {
            BufferUtils.destroyDirectBuffer(cpuBuffer);
            cpuBuffer = null;
        }
        int stride = (drawType == DrawType.Elements)
                ? DrawElementsIndirectCommand.STRIDE
                : DrawArraysIndirectCommand.STRIDE;
        bufferObject.initializeEmpty(maxCommands * stride);
        bufferObject.setUpdateNeeded();
        commandCount = maxCommands;
        return this;
    }

    /**
     * Transfers the CPU-side command data to the underlying BufferObject and
     * marks it for upload to the GPU. Call this after adding commands via
     * {@link #addCommand} or {@link #setElementsCommands}/{@link #setArraysCommands}.
     */
    public void update() {
        if (cpuBuffer != null) {
            cpuBuffer.flip();
            bufferObject.setData(cpuBuffer);
            bufferObject.setUpdateNeeded();
        }
    }

    /**
     * Returns the underlying BufferObject. Use this to pass to
     * {@code Renderer.renderMesh*Indirect} methods or to bind as an SSBO
     * for compute shader access.
     *
     * @return the backing BufferObject
     */
    public BufferObject getBufferObject() {
        return bufferObject;
    }

    /**
     * Returns the raw ByteBuffer for direct manipulation.
     * Advanced users can write commands directly. Call {@link #update()} when done.
     *
     * @return the CPU-side ByteBuffer, or null if only {@link #allocate} was used
     */
    public ByteBuffer getRawBuffer() {
        return cpuBuffer;
    }

    /**
     * Returns the number of commands in this buffer.
     */
    public int getCommandCount() {
        return commandCount;
    }

    /**
     * Returns the draw type (Elements or Arrays).
     */
    public DrawType getDrawType() {
        return drawType;
    }

    /**
     * Returns 0, indicating tightly-packed commands. This is the idiomatic
     * OpenGL usage — stride 0 tells the driver to use the natural struct size
     * (20 bytes for Elements, 16 bytes for Arrays).
     */
    public int getStride() {
        return 0;
    }

    /**
     * Debug-mode validation. Checks that the command buffer is compatible
     * with the given mesh. This is intended to be called from assertions:
     * <pre>
     * assert cmdBuf.validateAgainst(mesh);
     * </pre>
     *
     * @param mesh the mesh to validate against
     * @return true if validation passes
     * @throws IllegalStateException if validation fails
     */
    public boolean validateAgainst(Mesh mesh) {
        boolean meshHasIndices = mesh.getBuffer(VertexBuffer.Type.Index) != null;
        if (drawType == DrawType.Elements && !meshHasIndices) {
            throw new IllegalStateException(
                    "IndirectCommandBuffer uses DrawType.Elements but mesh has no index buffer");
        }
        if (drawType == DrawType.Arrays && meshHasIndices) {
            throw new IllegalStateException(
                    "IndirectCommandBuffer uses DrawType.Arrays but mesh has an index buffer");
        }
        return true;
    }

    private void ensureCpuCapacity(int additionalBytes) {
        if (cpuBuffer == null) {
            cpuBuffer = BufferUtils.createByteBuffer(additionalBytes * 16); // room for 16 commands initially
        }
        if (cpuBuffer.remaining() < additionalBytes) {
            int newCapacity = (cpuBuffer.capacity() + additionalBytes) * 2;
            ByteBuffer newBuffer = BufferUtils.createByteBuffer(newCapacity);
            cpuBuffer.flip();
            newBuffer.put(cpuBuffer);
            BufferUtils.destroyDirectBuffer(cpuBuffer);
            cpuBuffer = newBuffer;
        }
    }
}
