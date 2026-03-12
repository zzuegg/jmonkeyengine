in vec3 inPosition;

// Multi-slot attribute: mat4 occupies 4 consecutive attribute slots (4 x vec4).
// JME binds this via VertexBuffer.Type.TexCoord3 with 16 float components.
in mat4 inTexCoord3;

uniform mat4 g_WorldViewProjectionMatrix;

out vec4 vertColor;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);

    // Read all 4 rows of the matrix to verify every attribute slot carries data.
    // Row 0: R contribution, Row 1: G contribution, Row 2: B contribution, Row 3: A contribution.
    // Each row's .x component holds the value for that channel.
    vertColor = vec4(
        inTexCoord3[0].x,
        inTexCoord3[1].x,
        inTexCoord3[2].x,
        inTexCoord3[3].x
    );
}
