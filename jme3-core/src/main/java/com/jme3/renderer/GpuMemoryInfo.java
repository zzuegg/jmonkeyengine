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
package com.jme3.renderer;

/**
 * GPU memory information queried at runtime.
 * <p>
 * All values are in kilobytes (KB). A value of {@code -1} indicates the
 * information is not available (unsupported by the driver/hardware).
 * <p>
 * Supported via:
 * <ul>
 *   <li><b>NVIDIA:</b> {@code GL_NVX_gpu_memory_info} — provides dedicated
 *       VRAM, total available (dedicated + shared), and current available.</li>
 *   <li><b>AMD:</b> {@code GL_ATI_meminfo} — provides current free memory
 *       for textures, VBOs, and renderbuffers.</li>
 * </ul>
 * Use {@link Renderer#getGpuMemoryInfo()} to obtain an instance. The returned
 * object is a snapshot; call the method again for updated values.
 */
public class GpuMemoryInfo {

    private final int dedicatedMemoryKB;
    private final int totalAvailableMemoryKB;
    private final int currentAvailableMemoryKB;

    /**
     * Creates a new GpuMemoryInfo with the given values.
     * Use {@code -1} for unsupported fields.
     *
     * @param dedicatedMemoryKB dedicated GPU memory in KB, or -1
     * @param totalAvailableMemoryKB total available memory (dedicated + shared) in KB, or -1
     * @param currentAvailableMemoryKB currently available memory in KB, or -1
     */
    public GpuMemoryInfo(int dedicatedMemoryKB, int totalAvailableMemoryKB, int currentAvailableMemoryKB) {
        this.dedicatedMemoryKB = dedicatedMemoryKB;
        this.totalAvailableMemoryKB = totalAvailableMemoryKB;
        this.currentAvailableMemoryKB = currentAvailableMemoryKB;
    }

    /**
     * Returns the dedicated GPU video memory in KB.
     * On NVIDIA, this is the physical VRAM on the GPU.
     * Returns {@code -1} if not available (AMD or unsupported).
     */
    public int getDedicatedMemoryKB() {
        return dedicatedMemoryKB;
    }

    /**
     * Returns the total available GPU memory in KB.
     * On NVIDIA, this includes dedicated VRAM plus any shared system memory
     * the GPU can access. On AMD, this is not available.
     * Returns {@code -1} if not available.
     */
    public int getTotalAvailableMemoryKB() {
        return totalAvailableMemoryKB;
    }

    /**
     * Returns the currently available (free) GPU memory in KB.
     * This value changes at runtime as resources are allocated/freed.
     * On NVIDIA, this is from {@code GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX}.
     * On AMD, this is from {@code GL_TEXTURE_FREE_MEMORY_ATI} (first element).
     * <p>
     * Note: on some drivers (e.g. Mesa), this value may be slightly stale
     * and not reflect very recent allocations/deallocations.
     * This method is intended for diagnostics, not per-frame budgeting.
     * Returns {@code -1} if not supported.
     */
    public int getCurrentAvailableMemoryKB() {
        return currentAvailableMemoryKB;
    }

    /**
     * Returns whether any GPU memory information is available.
     */
    public boolean isSupported() {
        return dedicatedMemoryKB != -1 || totalAvailableMemoryKB != -1 || currentAvailableMemoryKB != -1;
    }

    @Override
    public String toString() {
        return "GpuMemoryInfo["
                + "dedicated=" + (dedicatedMemoryKB == -1 ? "N/A" : dedicatedMemoryKB + " KB")
                + ", totalAvailable=" + (totalAvailableMemoryKB == -1 ? "N/A" : totalAvailableMemoryKB + " KB")
                + ", currentAvailable=" + (currentAvailableMemoryKB == -1 ? "N/A" : currentAvailableMemoryKB + " KB")
                + "]";
    }
}
