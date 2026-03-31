uniform mat4 g_ViewProjectionMatrix;
uniform mat4 g_ViewMatrix;

struct DrawData {
    mat4  worldMatrix;            // offset 0,   size 64
    vec4  baseColor;              // offset 64,  size 16
    float metallic;               // offset 80,  size 4
    float roughness;              // offset 84,  size 4
    // 8 bytes padding (vec4 requires 16-byte alignment)
    vec4  emissive;               // offset 96,  size 16
    float emissiveIntensity;      // offset 112, size 4
    // 4 bytes padding (uvec2 requires 8-byte alignment)
    uvec2 baseColorMapHandle;     // offset 120, size 8
    uvec2 normalMapHandle;        // offset 128, size 8
    uvec2 metallicRoughnessMapHandle; // offset 136, size 8
    uvec2 emissiveMapHandle;      // offset 144, size 8
    // stride = 160 (152 padded to 16)
};

layout(std430) buffer m_DrawData {
    DrawData draws[];
};

in vec3 inPosition;
in vec3 inNormal;
in vec2 inTexCoord;
in vec4 inTangent;

out vec2 texCoord;
out vec3 wPosition;
out vec3 wNormal;
out vec4 wTangent;
flat out int drawId;

void main() {
    drawId = gl_DrawID;
    texCoord = inTexCoord;

    mat4 worldMatrix = draws[gl_DrawID].worldMatrix;
    vec4 worldPos = worldMatrix * vec4(inPosition, 1.0);
    wPosition = worldPos.xyz;

    mat3 normalMatrix = mat3(worldMatrix);
    wNormal = normalize(normalMatrix * inNormal);

    if (inTangent.xyz != vec3(0.0)) {
        wTangent = vec4(normalize(normalMatrix * inTangent.xyz), inTangent.w);
    } else {
        wTangent = vec4(0.0);
    }

    gl_Position = g_ViewProjectionMatrix * worldPos;
}
