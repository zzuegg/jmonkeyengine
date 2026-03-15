uniform mat4 g_ViewProjectionMatrix;

layout(std430) buffer m_DrawTransforms {
    mat4 worldMatrices[];
};

in vec3 inPosition;
in vec3 inNormal;
in vec2 inTexCoord;

out vec2 texCoord;
out vec3 normal;
flat out int drawId;

void main() {
    texCoord = inTexCoord;
    drawId = gl_DrawID;

    mat4 worldMatrix = worldMatrices[gl_DrawID];
    vec4 worldPos = worldMatrix * vec4(inPosition, 1.0);
    gl_Position = g_ViewProjectionMatrix * worldPos;

    // Transform normal by upper-left 3x3 of world matrix
    normal = mat3(worldMatrix) * inNormal;
}
