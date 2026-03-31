uniform mat4 g_ViewProjectionMatrix;

struct DrawData {
    mat4  worldMatrix;   // offset 0
    vec4  color;         // offset 64
    uvec2 colorMapHandle;// offset 80
};

layout(std430) buffer m_DrawData {
    DrawData draws[];
};

in vec3 inPosition;
in vec2 inTexCoord;

out vec2 texCoord;
flat out int drawId;

void main() {
    drawId = gl_DrawID;
    texCoord = inTexCoord;

    mat4 worldMatrix = draws[gl_DrawID].worldMatrix;
    vec4 worldPos = worldMatrix * vec4(inPosition, 1.0);
    gl_Position = g_ViewProjectionMatrix * worldPos;
}
