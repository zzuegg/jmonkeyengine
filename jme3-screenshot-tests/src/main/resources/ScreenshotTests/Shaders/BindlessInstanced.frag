#extension GL_ARB_bindless_texture : require

layout(std430) buffer m_TextureHandles {
    sampler2D textures[];
};

in vec2 texCoord;
flat in int instanceId;

out vec4 outFragColor;

void main() {
    outFragColor = texture(textures[instanceId], texCoord);
}
