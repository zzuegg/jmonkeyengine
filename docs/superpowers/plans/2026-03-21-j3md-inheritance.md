# J3MD Inheritance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow `.j3md` files to extend other `.j3md` files, inheriting parameters and techniques with support for overrides (new params, default changes, individual shader swaps, additive defines/world params).

**Architecture:** Flatten-at-load-time approach. `J3MLoader.loadFromRoot()` detects `MaterialDef Name : parent.j3md` syntax, loads the parent `MaterialDef`, deep-clones its params and techniques into the child, then applies child overrides on top. No runtime parent chain. No changes to `Material.java`, `Technique.java`, or any rendering code.

**Tech Stack:** Java, jMonkeyEngine material system (`com.jme3.material`), `J3MLoader` parser, Gradle + JUnit 4 + Mockito for tests.

**Known limitation:** Circular inheritance (A extends B extends A) is not detected and will cause infinite recursion via the asset manager. This is acceptable for v1 — the asset manager's own cycle detection (if any) or a stack overflow will surface the issue.

---

### Task 1: Add parent-loading support to J3MLoader.loadFromRoot()

**Files:**
- Modify: `jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java:716-800`

Currently `loadFromRoot()` (line 716) rejects the colon syntax for `MaterialDef`:

```java
// Line 750-753
if (split.length == 2){
    if (!extending){
        throw new MatParseException("Must use 'Material' when extending.", materialStat);
    }
```

We need to allow `MaterialDef Name : parent.j3md` and load+flatten the parent.

- [ ] **Step 1: Write the failing test — MaterialDef extending another MaterialDef**

Create test resource file `jme3-core/src/test/resources/parent-matdef.j3md`:

```
MaterialDef Parent {
    MaterialParameters {
        Float Roughness : 0.5
        Texture2D DiffuseMap
    }
    Technique {
        VertexShader GLSL150 GLSL100 : parent.vert
        FragmentShader GLSL150 GLSL100 : parent.frag
        WorldParameters {
            WorldViewProjectionMatrix
        }
        Defines {
            HAS_DIFFUSEMAP : DiffuseMap
        }
    }
    Technique PreShadow {
        VertexShader GLSL150 GLSL100 : shadow.vert
        FragmentShader GLSL150 GLSL100 : shadow.frag
    }
}
```

Create test resource file `jme3-core/src/test/resources/child-matdef.j3md`:

```
MaterialDef Child : parent-matdef.j3md {
    MaterialParameters {
        Float Wetness : 0.0
        Float Roughness : 0.8
    }
    Technique {
        FragmentShader GLSL150 GLSL100 : child.frag
        Defines {
            WETNESS : Wetness
        }
    }
}
```

Add test in `jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java`:

```java
@Test
public void materialDefInheritance_shouldInheritParentParams() throws IOException {
    // Setup: make assetManager return the parent MaterialDef when asked
    MaterialDef parentDef = loadParentDef();
    when(assetManager.loadAsset(any(AssetKey.class))).thenAnswer(invocation -> {
        AssetKey<?> k = invocation.getArgument(0);
        if (k.getName().equals("parent-matdef.j3md")) {
            return parentDef;
        }
        return null;
    });

    when(assetKey.getExtension()).thenReturn("j3md");
    when(assetInfo.openStream()).thenReturn(
        J3MLoader.class.getResourceAsStream("/child-matdef.j3md"));

    MaterialDef childDef = (MaterialDef) j3MLoader.load(assetInfo);

    // Should have parent params + child params
    assertNotNull(childDef.getMaterialParam("Roughness"));
    assertNotNull(childDef.getMaterialParam("DiffuseMap"));
    assertNotNull(childDef.getMaterialParam("Wetness"));

    // Roughness default should be overridden to 0.8
    assertEquals(0.8f, (float) childDef.getMaterialParam("Roughness").getValue(), 0.001f);
}
```

Note: `loadParentDef()` is a helper that creates a fresh `J3MLoader`, sets up the parent's `AssetInfo`/`AssetKey` pointing at `/parent-matdef.j3md`, and loads it. This avoids mocking `MaterialDef` and tests real parsing.

```java
private MaterialDef loadParentDef() throws IOException {
    J3MLoader parentLoader = new J3MLoader();
    AssetKey<?> parentKey = Mockito.mock(AssetKey.class);
    when(parentKey.getExtension()).thenReturn("j3md");
    when(parentKey.getName()).thenReturn("parent-matdef.j3md");
    AssetInfo parentInfo = Mockito.mock(AssetInfo.class);
    when(parentInfo.getManager()).thenReturn(assetManager);
    when(parentInfo.getKey()).thenReturn(parentKey);
    when(parentInfo.openStream()).thenReturn(
        J3MLoader.class.getResourceAsStream("/parent-matdef.j3md"));
    return (MaterialDef) parentLoader.load(parentInfo);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest.materialDefInheritance_shouldInheritParentParams" --info`
Expected: FAIL — `MatParseException: Must use 'Material' when extending`

- [ ] **Step 3: Implement MaterialDef inheritance in loadFromRoot()**

In `J3MLoader.java`, modify `loadFromRoot()` (starting at line 729):

```java
private void loadFromRoot(List<Statement> roots) throws IOException{
    if (roots.size() == 2){
        Statement exception = roots.get(0);
        String line = exception.getLine();
        if (line.startsWith("Exception")){
            throw new AssetLoadException(line.substring("Exception ".length()));
        }else{
            throw new IOException("In multiroot material, expected first statement to be 'Exception'");
        }
    }else if (roots.size() != 1){
        throw new IOException("Too many roots in J3M/J3MD file");
    }

    boolean extending = false;
    boolean extendingDef = false;
    Statement materialStat = roots.get(0);
    String materialName = materialStat.getLine();

    if (materialName.startsWith("MaterialDef")){
        materialName = materialName.substring("MaterialDef ".length()).trim();
        extending = false;

    }else if (materialName.startsWith("Material")){
        materialName = materialName.substring("Material ".length()).trim();
        extending = true;
    }else{
        throw new IOException("Specified file is not a Material file");
    }

    String[] split = materialName.split(":", 2);

    if (materialName.equals("")){
        throw new MatParseException("Material name cannot be empty", materialStat);
    }

    if (split.length == 2){
        if (extending) {
            // Existing .j3m extending a .j3md — unchanged behavior
            String extendedMat = split[1].trim();

            MaterialDef def = assetManager.loadAsset(new AssetKey<MaterialDef>(extendedMat));
            if (def == null) {
                throw new MatParseException("Extended material " + extendedMat + " cannot be found.", materialStat);
            }

            material = new Material(def);
            material.setKey(key);
            material.setName(split[0].trim());
        } else {
            // NEW: MaterialDef extending another MaterialDef
            extendingDef = true;
            String parentPath = split[1].trim();

            MaterialDef parentDef = assetManager.loadAsset(new AssetKey<MaterialDef>(parentPath));
            if (parentDef == null) {
                throw new MatParseException("Parent material definition " + parentPath + " cannot be found.", materialStat);
            }

            String childName = split[0].trim();
            materialDef = new MaterialDef(assetManager, childName);
            materialDef.setAssetName(key.getName());

            // Flatten: copy all parent params into child
            for (MatParam param : parentDef.getMaterialParams()) {
                if (param instanceof MatParamTexture) {
                    MatParamTexture texParam = (MatParamTexture) param;
                    materialDef.addMaterialParamTexture(
                        texParam.getVarType(), texParam.getName(),
                        texParam.getColorSpace(), (Texture) texParam.getValue());
                } else {
                    materialDef.addMaterialParam(
                        param.getVarType(), param.getName(), param.getValue());
                }
            }

            // Flatten: copy all parent techniques into child (cloned)
            for (String techName : parentDef.getTechniqueDefsNames()) {
                for (TechniqueDef techDef : parentDef.getTechniqueDefs(techName)) {
                    try {
                        materialDef.addTechniqueDef(techDef.clone());
                    } catch (CloneNotSupportedException e) {
                        throw new AssetLoadException("Failed to clone technique: " + techName, e);
                    }
                }
            }
        }

    }else if (split.length == 1){
        if (extending){
            throw new MatParseException("Expected ':', got '{'", materialStat);
        }
        materialDef = new MaterialDef(assetManager, materialName);
        materialDef.setAssetName(key.getName());
    }else{
        throw new MatParseException("Cannot use colon in material name/path", materialStat);
    }

    for (Statement statement : materialStat.getContents()) {
        split = statement.getLine().split("[ \\{]");
        String statType = split[0];
        if (extending) {
            if (statType.equals("MaterialParameters")) {
                readExtendingMaterialParams(statement.getContents());
            } else if (statType.equals("AdditionalRenderState")) {
                readAdditionalRenderState(statement.getContents());
            } else if (statType.equals("Transparent")) {
                readTransparentStatement(statement.getLine());
            } else if (statType.equals("ReceivesShadows")) {
                readReceivesShadowsStatement(statement.getLine());
            }
        } else if (extendingDef) {
            if (statType.equals("MaterialParameters")) {
                readExtendingDefMaterialParams(statement.getContents());
            } else if (statType.equals("Technique")) {
                readExtendingDefTechnique(statement);
            } else {
                throw new MatParseException(
                    "Expected material def statement, got '" + statType + "'", statement);
            }
        } else {
            if (statType.equals("Technique")) {
                readTechnique(statement);
            } else if (statType.equals("MaterialParameters")) {
                readMaterialParams(statement.getContents());
            } else {
                throw new MatParseException(
                    "Expected material statement, got '" + statType + "'", statement);
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest.materialDefInheritance_shouldInheritParentParams" --info`
Expected: PASS

- [ ] **Step 5: Run all existing J3MLoader tests to check for regressions**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest" --info`
Expected: All existing tests PASS

- [ ] **Step 6: Commit**

```bash
git add jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java \
       jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java \
       jme3-core/src/test/resources/parent-matdef.j3md \
       jme3-core/src/test/resources/child-matdef.j3md
git commit -m "feat(material): add MaterialDef inheritance support in J3MLoader

Allow MaterialDef to extend another MaterialDef using colon syntax.
Parent params and techniques are flattened into child at load time."
```

---

### Task 2: Implement readExtendingDefMaterialParams — param override logic

**Files:**
- Modify: `jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java`

This method handles the `MaterialParameters` block inside a child `MaterialDef`. It must:
- Add new params that don't exist in parent
- Override default values of existing params (must match type)
- Reject type mismatches

- [ ] **Step 1: Write the failing test — param type mismatch should throw**

Add test in `J3MLoaderTest.java`:

```java
@Test(expected = IOException.class)
public void materialDefInheritance_paramTypeMismatch_shouldThrow() throws IOException {
    MaterialDef parentDef = loadParentDef();
    when(assetManager.loadAsset(any(AssetKey.class))).thenAnswer(invocation -> {
        AssetKey<?> k = invocation.getArgument(0);
        if (k.getName().equals("parent-matdef.j3md")) {
            return parentDef;
        }
        return null;
    });

    when(assetKey.getExtension()).thenReturn("j3md");
    when(assetInfo.openStream()).thenReturn(
        J3MLoader.class.getResourceAsStream("/child-matdef-type-mismatch.j3md"));

    j3MLoader.load(assetInfo);
}
```

Create test resource `jme3-core/src/test/resources/child-matdef-type-mismatch.j3md`:

```
MaterialDef BadChild : parent-matdef.j3md {
    MaterialParameters {
        Vector4 Roughness : 1.0 1.0 1.0 1.0
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest.materialDefInheritance_paramTypeMismatch_shouldThrow" --info`
Expected: FAIL — method `readExtendingDefMaterialParams` doesn't exist yet

- [ ] **Step 3: Implement readExtendingDefMaterialParams**

Add to `J3MLoader.java`:

```java
private void readExtendingDefMaterialParams(List<Statement> paramsList) throws IOException {
    for (Statement statement : paramsList) {
        readExtendingDefParam(statement.getLine());
    }
}

// Handles a single param line in a child MaterialDef's MaterialParameters block.
// If the param already exists (inherited from parent), update its default value.
// If it's new, add it as a new param.
private void readExtendingDefParam(String statement) throws IOException {
    String name;
    String defaultVal = null;
    ColorSpace colorSpace = null;

    String[] split = statement.split(":");

    if (split.length == 2) {
        statement = split[0].trim();
        defaultVal = split[1].trim();
    } else if (split.length != 1) {
        throw new IOException("Parameter statement syntax incorrect");
    }

    if (statement.endsWith("-LINEAR")) {
        colorSpace = ColorSpace.Linear;
        statement = statement.substring(0, statement.length() - "-LINEAR".length());
    }

    // Parse ffbinding (ignore it, same as readParam)
    int startParen = statement.indexOf("(");
    if (startParen != -1) {
        int endParen = statement.indexOf(")", startParen);
        statement = statement.substring(0, startParen);
    }

    // Parse type + name
    split = statement.split(whitespacePattern);
    if (split.length != 2) {
        throw new IOException("Parameter statement syntax incorrect");
    }

    VarType type;
    if (split[0].equals("Color")) {
        type = VarType.Vector4;
    } else {
        type = VarType.valueOf(split[0]);
    }
    name = split[1];

    // Check if param exists in parent (already copied to materialDef)
    MatParam existingParam = materialDef.getMaterialParam(name);

    if (existingParam != null) {
        // Override: validate type match
        if (existingParam.getVarType() != type) {
            throw new IOException(
                "Cannot override parameter '" + name + "': type mismatch. "
                + "Parent declares " + existingParam.getVarType()
                + " but child declares " + type);
        }

        // Update default value
        if (defaultVal != null) {
            Object defaultValObj = readValue(type, defaultVal);
            existingParam.setValue(defaultValObj);
        }
    } else {
        // New param: add it
        Object defaultValObj = null;
        if (defaultVal != null) {
            defaultValObj = readValue(type, defaultVal);
        }
        if (type.isTextureType()) {
            materialDef.addMaterialParamTexture(type, name, colorSpace, (Texture) defaultValObj);
        } else {
            materialDef.addMaterialParam(type, name, defaultValObj);
        }
    }
}
```

- [ ] **Step 4: Verify MatParam.setValue exists, add if needed**

Check if `MatParam` has a `setValue` method. If not, add one to `jme3-core/src/main/java/com/jme3/material/MatParam.java`:

```java
public void setValue(Object value) {
    this.value = value;
}
```

(It likely already has one via the `Savable` read path or Material usage — verify first.)

- [ ] **Step 5: Run tests**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest" --info`
Expected: All tests PASS, including the type-mismatch test

- [ ] **Step 6: Commit**

```bash
git add jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java \
       jme3-core/src/main/java/com/jme3/material/MatParam.java \
       jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java \
       jme3-core/src/test/resources/child-matdef-type-mismatch.j3md
git commit -m "feat(material): implement param override logic for MaterialDef inheritance

Child MaterialDef can add new params or override defaults of inherited params.
Type mismatches between parent and child params are rejected at load time."
```

---

### Task 3: Implement readExtendingDefTechnique — technique merge with shader swap

**Files:**
- Modify: `jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java`

This is the core technique merge logic. When a child declares a `Technique` block:
- Match by name against inherited (cloned) techniques
- If matched: override only the shaders the child explicitly declares, merge defines (additive), merge world params (additive), override render state if declared
- If no match: treat as a new technique (full declaration required)

**Important design notes (from review):**
1. **Shader language variant matching:** Parent techniques are stored as N variants (one per language set, e.g., GLSL150 variant and GLSL100 variant). Each variant is a separate `TechniqueDef` with a single language per shader type. The child's shader declarations list multiple languages (e.g., `FragmentShader GLSL150 GLSL100 : child.frag`), producing N language sets. We must match child language set `i` to parent variant `i` by index — the parent's `readTechnique` creates variants in the same order.
2. **Preset defines:** The `readDefine` method writes unmapped defines to `this.presetDefines` (instance field). We must save/restore this field, then apply the accumulated preset defines to each variant's shader prologue after processing.
3. **LightMode override requires logic update:** If child overrides `LightMode`, must also set the corresponding `TechniqueDefLogic` (same switch as in `readTechnique`).

- [ ] **Step 1: Write the failing test — child overrides only fragment shader**

Add test in `J3MLoaderTest.java`:

```java
@Test
public void materialDefInheritance_shouldOverrideFragShaderOnly() throws IOException {
    MaterialDef parentDef = loadParentDef();
    when(assetManager.loadAsset(any(AssetKey.class))).thenAnswer(invocation -> {
        AssetKey<?> k = invocation.getArgument(0);
        if (k.getName().equals("parent-matdef.j3md")) {
            return parentDef;
        }
        return null;
    });

    when(assetKey.getExtension()).thenReturn("j3md");
    when(assetInfo.openStream()).thenReturn(
        J3MLoader.class.getResourceAsStream("/child-matdef.j3md"));

    MaterialDef childDef = (MaterialDef) j3MLoader.load(assetInfo);

    // Default technique should exist with overridden frag shader
    List<TechniqueDef> defaultTechs = childDef.getTechniqueDefs("Default");
    assertNotNull(defaultTechs);
    // Should have 2 variants (GLSL150 and GLSL100) like parent
    assertEquals(2, defaultTechs.size());

    // Fragment shader should be child's for both variants
    assertEquals("child.frag", defaultTechs.get(0).getFragmentShaderName());
    assertEquals("child.frag", defaultTechs.get(1).getFragmentShaderName());
    // Vertex shader should still be parent's for both variants
    assertEquals("parent.vert", defaultTechs.get(0).getVertexShaderName());
    assertEquals("parent.vert", defaultTechs.get(1).getVertexShaderName());

    // Verify language variants are correct
    assertEquals("GLSL150", defaultTechs.get(0).getFragmentShaderLanguage());
    assertEquals("GLSL100", defaultTechs.get(1).getFragmentShaderLanguage());

    // PreShadow technique should be fully inherited unchanged
    List<TechniqueDef> shadowTechs = childDef.getTechniqueDefs("PreShadow");
    assertNotNull(shadowTechs);
    assertEquals("shadow.frag", shadowTechs.get(0).getFragmentShaderName());
    assertEquals("shadow.vert", shadowTechs.get(0).getVertexShaderName());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest.materialDefInheritance_shouldOverrideFragShaderOnly" --info`
Expected: FAIL — `readExtendingDefTechnique` doesn't exist yet

- [ ] **Step 3: Implement readExtendingDefTechnique**

Add to `J3MLoader.java`:

```java
private void readExtendingDefTechnique(Statement techStat) throws IOException {
    isUseNodes = false;
    String[] split = techStat.getLine().split(whitespacePattern);

    String name;
    if (split.length == 1) {
        name = TechniqueDef.DEFAULT_TECHNIQUE_NAME;
    } else if (split.length == 2) {
        name = split[1];
    } else {
        throw new IOException("Technique statement syntax incorrect");
    }

    // Check if this technique name exists in the inherited techniques
    List<TechniqueDef> existingTechs = materialDef.getTechniqueDefs(name);

    if (existingTechs == null || existingTechs.isEmpty()) {
        // No parent technique with this name — treat as a brand new technique
        readTechnique(techStat);
        return;
    }

    // Parse the child's technique block into temporary state
    EnumMap<Shader.ShaderType, String> childShaderNames = new EnumMap<>(Shader.ShaderType.class);
    List<EnumMap<Shader.ShaderType, String>> childShaderLanguages = new ArrayList<>();
    List<Statement> childDefineStatements = new ArrayList<>();
    List<Statement> childWorldParamStatements = new ArrayList<>();
    RenderState childRenderState = null;
    RenderState childForcedRenderState = null;
    LightMode childLightMode = null;
    ShadowMode childShadowMode = null;
    TechniqueDef.LightSpace childLightSpace = null;

    int childLangSize = 0;

    for (Statement statement : techStat.getContents()) {
        String[] stSplit = statement.getLine().split("[ \\{]");
        String stType = stSplit[0];

        if (stType.equals("VertexShader") || stType.equals("FragmentShader")
                || stType.equals("GeometryShader")
                || stType.equals("TessellationControlShader")
                || stType.equals("TessellationEvaluationShader")) {
            // Parse shader statement into child maps
            String[] shSplit = statement.getLine().split(":");
            if (shSplit.length != 2) {
                throw new IOException("Shader statement syntax incorrect: " + statement.getLine());
            }
            String[] typeAndLang = shSplit[0].split(whitespacePattern);
            String shaderSource = shSplit[1].trim();

            for (Shader.ShaderType shaderType : Shader.ShaderType.values()) {
                if (typeAndLang[0].equals(shaderType.toString() + "Shader")) {
                    childShaderNames.put(shaderType, shaderSource);
                    String[] languages = Arrays.copyOfRange(typeAndLang, 1, typeAndLang.length);
                    if (childLangSize != 0 && childLangSize != languages.length) {
                        throw new AssetLoadException("Technique " + name
                            + " must have the same number of languages for each shader type.");
                    }
                    childLangSize = languages.length;
                    for (int i = 0; i < languages.length; i++) {
                        if (i >= childShaderLanguages.size()) {
                            childShaderLanguages.add(new EnumMap<>(Shader.ShaderType.class));
                        }
                        childShaderLanguages.get(i).put(shaderType, languages[i]);
                    }
                }
            }
        } else if (stType.equals("Defines")) {
            childDefineStatements.addAll(statement.getContents());
        } else if (stType.equals("WorldParameters")) {
            childWorldParamStatements.addAll(statement.getContents());
        } else if (stType.equals("RenderState")) {
            childRenderState = new RenderState();
            renderState = childRenderState;
            for (Statement rs : statement.getContents()) {
                readRenderStateStatement(rs);
            }
            renderState = null;
        } else if (stType.equals("ForcedRenderState")) {
            childForcedRenderState = new RenderState();
            renderState = childForcedRenderState;
            for (Statement rs : statement.getContents()) {
                readRenderStateStatement(rs);
            }
            renderState = null;
        } else if (stType.equals("LightMode")) {
            String[] lmSplit = statement.getLine().split(whitespacePattern);
            childLightMode = LightMode.valueOf(lmSplit[1]);
        } else if (stType.equals("ShadowMode")) {
            String[] smSplit = statement.getLine().split(whitespacePattern);
            childShadowMode = ShadowMode.valueOf(smSplit[1]);
        } else if (stType.equals("LightSpace")) {
            String[] lsSplit = statement.getLine().split(whitespacePattern);
            childLightSpace = TechniqueDef.LightSpace.valueOf(lsSplit[1]);
        } else if (stType.equals("NoRender")) {
            // ignore for extending — not meaningful to override
        } else {
            throw new MatParseException(null, stType, statement);
        }
    }

    // Validate child language set count matches parent variant count
    // (when child declares shader overrides)
    if (!childShaderNames.isEmpty() && !childShaderLanguages.isEmpty()
            && childShaderLanguages.size() != existingTechs.size()) {
        throw new IOException("Technique " + name + ": child declares "
            + childShaderLanguages.size() + " language variants but parent has "
            + existingTechs.size() + " variants. Must match.");
    }

    // Save and clear instance-level presetDefines (readDefine writes to it)
    ArrayList<String> savedPresetDefines = new ArrayList<>(presetDefines);
    presetDefines.clear();

    // Process defines ONCE on the first variant to collect mapped and unmapped defines.
    // Then replicate to other variants. This avoids duplicating preset defines.
    if (!childDefineStatements.isEmpty()) {
        technique = existingTechs.get(0);
        for (Statement defSt : childDefineStatements) {
            readDefine(defSt.getLine());
        }
        technique = null;

        // Now replicate the same mapped defines to remaining variants
        for (int i = 1; i < existingTechs.size(); i++) {
            TechniqueDef td = existingTechs.get(i);
            for (Statement defSt : childDefineStatements) {
                String[] defSplit = defSt.getLine().split(":");
                if (defSplit.length == 2) {
                    // Mapped define — add to this variant too
                    String defineName = defSplit[0].trim();
                    String paramName = defSplit[1].trim();
                    MatParam param = materialDef.getMaterialParam(paramName);
                    if (param != null) {
                        td.addShaderParamDefine(paramName, param.getVarType(), defineName);
                    }
                }
                // Unmapped defines go to presetDefines (already collected once)
            }
        }
    }

    // Apply child overrides to each cloned parent technique variant
    for (int variantIdx = 0; variantIdx < existingTechs.size(); variantIdx++) {
        TechniqueDef td = existingTechs.get(variantIdx);

        // Override shaders: only replace the shader types the child explicitly declares
        if (!childShaderNames.isEmpty()) {
            EnumMap<Shader.ShaderType, String> mergedNames = td.getShaderProgramNames().clone();
            EnumMap<Shader.ShaderType, String> mergedLangs = td.getShaderProgramLanguages().clone();

            // Apply child shader source names
            for (Map.Entry<Shader.ShaderType, String> entry : childShaderNames.entrySet()) {
                mergedNames.put(entry.getKey(), entry.getValue());
            }

            // Apply child shader languages for THIS variant (matched by index)
            if (!childShaderLanguages.isEmpty()) {
                EnumMap<Shader.ShaderType, String> childLangSet = childShaderLanguages.get(variantIdx);
                for (Map.Entry<Shader.ShaderType, String> entry : childLangSet.entrySet()) {
                    mergedLangs.put(entry.getKey(), entry.getValue());
                }
            }

            td.setShaderFile(mergedNames, mergedLangs);
        }

        // Additive world params
        if (!childWorldParamStatements.isEmpty()) {
            for (Statement wpSt : childWorldParamStatements) {
                td.addWorldParam(wpSt.getLine());
            }
        }

        // Override render state
        if (childRenderState != null) {
            td.setRenderState(childRenderState);
        }
        if (childForcedRenderState != null) {
            td.setForcedRenderState(childForcedRenderState);
        }

        // Override light/shadow mode (with logic update)
        if (childLightMode != null) {
            td.setLightMode(childLightMode);
            // Must update TechniqueDefLogic to match the new LightMode
            switch (childLightMode) {
                case Disable:
                    td.setLogic(new DefaultTechniqueDefLogic(td));
                    break;
                case MultiPass:
                    td.setLogic(new MultiPassLightingLogic(td));
                    break;
                case SinglePass:
                    td.setLogic(new SinglePassLightingLogic(td));
                    break;
                case StaticPass:
                    td.setLogic(new StaticPassLightingLogic(td));
                    break;
                case SinglePassAndImageBased:
                    td.setLogic(new SinglePassAndImageBasedLightingLogic(td));
                    break;
                default:
                    throw new IOException("Light mode not supported:" + childLightMode);
            }
        }
        if (childShadowMode != null) {
            td.setShadowMode(childShadowMode);
        }
        if (childLightSpace != null) {
            td.setLightSpace(childLightSpace);
        }
    }

    // Apply any preset (unmapped) defines that readDefine accumulated
    if (!presetDefines.isEmpty()) {
        for (TechniqueDef td : existingTechs) {
            // Append child preset defines to parent's existing prologue
            String childPrologue = createShaderPrologue(presetDefines);
            String existingPrologue = td.getShaderPrologue();
            if (existingPrologue != null && !existingPrologue.isEmpty()) {
                td.setShaderPrologue(existingPrologue + childPrologue);
            } else {
                td.setShaderPrologue(childPrologue);
            }
        }
    }

    // Restore instance-level presetDefines
    presetDefines.clear();
    presetDefines.addAll(savedPresetDefines);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest.materialDefInheritance_shouldOverrideFragShaderOnly" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add jme3-core/src/plugins/java/com/jme3/material/plugins/J3MLoader.java \
       jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java
git commit -m "feat(material): implement technique merge for MaterialDef inheritance

Child MaterialDef can override individual shaders within inherited techniques.
Shader language variants are matched by index to parent variants.
Defines and WorldParameters are merged additively.
RenderState, LightMode (with logic update), ShadowMode can be overridden."
```

---

### Task 4: Test additive defines in inherited techniques

**Files:**
- Modify: `jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java`

- [ ] **Step 1: Write the test — child adds defines to inherited technique**

```java
@Test
public void materialDefInheritance_shouldMergeDefinesAdditively() throws IOException {
    MaterialDef parentDef = loadParentDef();
    when(assetManager.loadAsset(any(AssetKey.class))).thenAnswer(invocation -> {
        AssetKey<?> k = invocation.getArgument(0);
        if (k.getName().equals("parent-matdef.j3md")) {
            return parentDef;
        }
        return null;
    });

    when(assetKey.getExtension()).thenReturn("j3md");
    when(assetInfo.openStream()).thenReturn(
        J3MLoader.class.getResourceAsStream("/child-matdef.j3md"));

    MaterialDef childDef = (MaterialDef) j3MLoader.load(assetInfo);

    List<TechniqueDef> techs = childDef.getTechniqueDefs("Default");
    TechniqueDef td = techs.get(0);

    // Parent define should still be there
    assertNotNull("Parent define HAS_DIFFUSEMAP should exist",
        td.getShaderParamDefine("DiffuseMap"));
    assertEquals("HAS_DIFFUSEMAP", td.getShaderParamDefine("DiffuseMap"));

    // Child define should be added
    assertNotNull("Child define WETNESS should exist",
        td.getShaderParamDefine("Wetness"));
    assertEquals("WETNESS", td.getShaderParamDefine("Wetness"));
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest.materialDefInheritance_shouldMergeDefinesAdditively" --info`
Expected: PASS (should work with Task 3's implementation)

- [ ] **Step 3: Commit**

```bash
git add jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java
git commit -m "test(material): add test for additive define merging in MaterialDef inheritance"
```

---

### Task 5: Test that child can add entirely new techniques

**Files:**
- Modify: `jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java`
- Create: `jme3-core/src/test/resources/child-matdef-new-technique.j3md`

- [ ] **Step 1: Create test resource and write test**

Create `jme3-core/src/test/resources/child-matdef-new-technique.j3md`:

```
MaterialDef ChildWithNewTech : parent-matdef.j3md {
    Technique Glow {
        VertexShader GLSL150 GLSL100 : glow.vert
        FragmentShader GLSL150 GLSL100 : glow.frag
    }
}
```

Add test:

```java
@Test
public void materialDefInheritance_shouldSupportNewTechniques() throws IOException {
    MaterialDef parentDef = loadParentDef();
    when(assetManager.loadAsset(any(AssetKey.class))).thenAnswer(invocation -> {
        AssetKey<?> k = invocation.getArgument(0);
        if (k.getName().equals("parent-matdef.j3md")) {
            return parentDef;
        }
        return null;
    });

    when(assetKey.getExtension()).thenReturn("j3md");
    when(assetInfo.openStream()).thenReturn(
        J3MLoader.class.getResourceAsStream("/child-matdef-new-technique.j3md"));

    MaterialDef childDef = (MaterialDef) j3MLoader.load(assetInfo);

    // Parent techniques should be inherited
    assertNotNull(childDef.getTechniqueDefs("Default"));
    assertNotNull(childDef.getTechniqueDefs("PreShadow"));

    // New technique should be added
    List<TechniqueDef> glowTechs = childDef.getTechniqueDefs("Glow");
    assertNotNull("Glow technique should exist", glowTechs);
    assertEquals("glow.frag", glowTechs.get(0).getFragmentShaderName());
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest.materialDefInheritance_shouldSupportNewTechniques" --info`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add jme3-core/src/test/java/com/jme3/material/plugins/J3MLoaderTest.java \
       jme3-core/src/test/resources/child-matdef-new-technique.j3md
git commit -m "test(material): add test for new technique addition in MaterialDef inheritance"
```

---

### Task 6: Run full test suite and verify no regressions

**Files:** None (verification only)

- [ ] **Step 1: Run all jme3-core tests**

Run: `./gradlew :jme3-core:test --info`
Expected: All tests PASS

- [ ] **Step 2: Run jme3-plugins tests (TestMaterialDefWrite)**

Run: `./gradlew :jme3-plugins:test --info`
Expected: All tests PASS

- [ ] **Step 3: Verify existing .j3md files still load correctly**

Run: `./gradlew :jme3-core:test --tests "com.jme3.material.plugins.J3MLoaderTest" --info`
Expected: All tests PASS (old tests untouched, new tests pass)
