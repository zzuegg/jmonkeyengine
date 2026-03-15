#extension GL_ARB_bindless_texture : require

layout(std430) buffer m_TextureHandles {
    sampler2D textures[];
};

in vec2 texCoord;
in vec3 normal;
flat in int drawId;

out vec4 outFragColor;

void main() {
    vec4 texColor = texture(textures[drawId], texCoord);

    // Simple directional lighting for depth perception
    vec3 lightDir = normalize(vec3(1.0, 2.0, 3.0));
    float ndotl = max(dot(normalize(normal), lightDir), 0.0);
    float lighting = 0.3 + 0.7 * ndotl;

    outFragColor = vec4(texColor.rgb * lighting, texColor.a);
}
