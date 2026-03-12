#extension GL_ARB_bindless_texture : require

layout(bindless_sampler) uniform sampler2D m_ColorMap;

varying vec2 texCoord1;

void main(){
    gl_FragColor = texture2D(m_ColorMap, texCoord1);
}
