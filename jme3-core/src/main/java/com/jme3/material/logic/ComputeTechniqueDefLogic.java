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
package com.jme3.material.logic;

import com.jme3.asset.AssetManager;
import com.jme3.light.LightList;
import com.jme3.material.Material.BindUnits;
import com.jme3.material.TechniqueDef;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Geometry;
import com.jme3.shader.DefineList;
import com.jme3.shader.Shader;

import java.util.EnumSet;

/**
 * {@link TechniqueDefLogic} for compute-only techniques.
 * <p>
 * Handles shader compilation via the standard {@link TechniqueDef} path
 * and dispatches work groups instead of rendering geometry.
 */
public class ComputeTechniqueDefLogic implements TechniqueDefLogic {

    private final TechniqueDef techniqueDef;

    public ComputeTechniqueDefLogic(TechniqueDef techniqueDef) {
        this.techniqueDef = techniqueDef;
    }

    @Override
    public Shader makeCurrent(AssetManager assetManager, RenderManager renderManager,
                              EnumSet<Caps> rendererCaps, LightList lights,
                              DefineList defines) {
        return techniqueDef.getShader(assetManager, rendererCaps, defines);
    }

    @Override
    public void render(RenderManager renderManager, Shader shader,
                       Geometry geometry, LightList lights, BindUnits lastBindUnits) {
        throw new UnsupportedOperationException(
            "Compute technique cannot render geometry. Use Material.dispatch() instead.");
    }

    @Override
    public void dispatch(RenderManager renderManager, Shader shader,
                         int numGroupsX, int numGroupsY, int numGroupsZ) {
        Renderer renderer = renderManager.getRenderer();
        renderer.setShader(shader);
        renderer.dispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
    }
}
