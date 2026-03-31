#extension GL_ARB_bindless_texture : enable

struct DrawData {
    mat4  worldMatrix;
    vec4  color;
    uvec2 colorMapHandle;
};

layout(std430) buffer m_DrawData {
    DrawData draws[];
};

in vec2 texCoord;
flat in int drawId;

out vec4 outFragColor;

void main() {
    DrawData d = draws[drawId];
    vec4 color = d.color;

    if (d.colorMapHandle != uvec2(0)) {
        color *= texture(sampler2D(d.colorMapHandle), texCoord);
    }

    outFragColor = color;
}
