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
package com.jme3.scene.indirect;

import com.jme3.material.MaterialDef;
import com.jme3.material.TechniqueDef;
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
 * Technique mapping: when the render pipeline requests a technique (e.g.
 * "Default", "PreShadow"), this geometry maps it to its MDI variant
 * ("Mdi", "MdiPreShadow") if available in the material definition.
 * This allows standard matdefs to include MDI technique variants that
 * are automatically selected for MDI rendering.
 */
public class MdiGeometry extends Geometry {

    /** Prefix added to technique names to find MDI variants. */
    private static final String MDI_PREFIX = "Mdi";

    private IndirectCommandBuffer commandBuffer;
    private int drawCount = -1;

    protected MdiGeometry() {
        super();
    }

    public MdiGeometry(String name, Mesh mesh, IndirectCommandBuffer commandBuffer) {
        super(name, mesh);
        this.commandBuffer = commandBuffer;
    }

    public IndirectCommandBuffer getCommandBuffer() {
        return commandBuffer;
    }

    public void setCommandBuffer(IndirectCommandBuffer commandBuffer) {
        this.commandBuffer = commandBuffer;
    }

    public int getDrawCount() {
        if (drawCount >= 0) return drawCount;
        return commandBuffer != null ? commandBuffer.getCommandCount() : 0;
    }

    public void setDrawCount(int count) {
        this.drawCount = count;
    }

    /**
     * Maps technique names to their MDI variants.
     * <p>
     * "Default" maps to "Mdi", other techniques map to "Mdi" + name
     * (e.g. "PreShadow" becomes "MdiPreShadow"). If the MDI variant
     * does not exist in the material definition, falls back to the
     * original technique name.
     */
    @Override
    public String mapTechnique(String requestedTechnique) {
        if (material == null) return requestedTechnique;

        String mdiName;
        if (requestedTechnique.equals(TechniqueDef.DEFAULT_TECHNIQUE_NAME)) {
            mdiName = MDI_PREFIX;
        } else {
            mdiName = MDI_PREFIX + requestedTechnique;
        }

        MaterialDef matDef = material.getMaterialDef();
        if (matDef.getTechniqueDefs(mdiName) != null) {
            return mdiName;
        }

        // No MDI variant — fall back to original
        return requestedTechnique;
    }
}
