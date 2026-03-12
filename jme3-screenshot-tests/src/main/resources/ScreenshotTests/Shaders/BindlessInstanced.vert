uniform mat4 g_ViewProjectionMatrix;

// Instancing data: world matrix (rows 0-2 xyz) + normal quaternion (row 0-3 w)
#ifdef INSTANCING
in mat4 inInstanceData;

vec4 TransformWorldViewProjection(vec4 position) {
    mat4 worldMatrix = mat4(
        vec4(inInstanceData[0].xyz, 0.0),
        vec4(inInstanceData[1].xyz, 0.0),
        vec4(inInstanceData[2].xyz, 0.0),
        vec4(inInstanceData[3].xyz, 1.0));
    return g_ViewProjectionMatrix * (worldMatrix * position);
}
#else
uniform mat4 g_WorldViewProjectionMatrix;

vec4 TransformWorldViewProjection(vec4 position) {
    return g_WorldViewProjectionMatrix * position;
}
#endif

in vec3 inPosition;
in vec2 inTexCoord;

out vec2 texCoord;
flat out int instanceId;

void main() {
    texCoord = inTexCoord;
    instanceId = gl_InstanceID;
    gl_Position = TransformWorldViewProjection(vec4(inPosition, 1.0));
}
