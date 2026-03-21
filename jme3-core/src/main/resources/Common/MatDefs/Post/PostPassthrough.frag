#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_Texture;

#ifdef RESOLVE_MS
uniform int m_NumSamples;
#endif

in vec2 texCoord;
out vec4 outFragColor;

void main() {
    #ifdef RESOLVE_MS
        ivec2 iTexC = ivec2(texCoord * textureSize(m_Texture, 0));
        outFragColor = texelFetch(m_Texture, iTexC, 0);
    #else
        outFragColor = texture2D(m_Texture, texCoord);
    #endif
}
