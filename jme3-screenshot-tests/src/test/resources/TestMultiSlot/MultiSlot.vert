in vec3 inPosition;

// Multi-slot attribute: mat4 occupies 4 consecutive attribute slots (4 x vec4).
// JME binds this via VertexBuffer.Type.TexCoord3 with 16 float components.
in mat4 inTexCoord3;

uniform mat4 g_WorldViewProjectionMatrix;

out vec4 vertColor;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);

    // Use the multi-slot attribute data to drive vertex color.
    // Each row of the matrix is a vec4; use row 0 as RGBA color.
    vertColor = inTexCoord3[0];
}
