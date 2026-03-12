uniform mat4 g_WorldViewProjectionMatrix;

in vec3 inPosition;
in vec3 inNormal;
in vec2 inTexCoord;

out vec2 texCoord;
out vec3 normal;
flat out int drawId;

void main() {
    texCoord = inTexCoord;
    normal = inNormal;
    drawId = gl_DrawID;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
