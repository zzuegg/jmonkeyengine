#extension GL_ARB_bindless_texture : enable

const float PI = 3.14159265359;

struct DrawData {
    mat4  worldMatrix;
    vec4  baseColor;
    float metallic;
    float roughness;
    vec4  emissive;
    float emissiveIntensity;
    uvec2 baseColorMapHandle;
    uvec2 normalMapHandle;
    uvec2 metallicRoughnessMapHandle;
    uvec2 emissiveMapHandle;
};

layout(std430) buffer m_DrawData {
    DrawData draws[];
};

uniform vec3 g_CameraPosition;
uniform vec4 g_AmbientLightColor;

#ifndef NB_LIGHTS
    #define NB_LIGHTS 12
#endif
uniform vec4 g_LightData[NB_LIGHTS];

in vec2 texCoord;
in vec3 wPosition;
in vec3 wNormal;
in vec4 wTangent;
flat in int drawId;

out vec4 outFragColor;

// --- PBR functions ---

float DistributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

float GeometrySchlickGGX(float NdotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return NdotV / (NdotV * (1.0 - k) + k);
}

float GeometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    return GeometrySchlickGGX(NdotV, roughness) * GeometrySchlickGGX(NdotL, roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// --- Light helpers (from jME Lighting.glsllib) ---

void lightComputeDir(in vec3 worldPos, in float lightType, in vec4 position,
                     out vec4 lightDir, out vec3 lightVec) {
    float posLight = step(0.5, lightType);
    vec3 tempVec = position.xyz * sign(posLight - 0.5) - (worldPos * posLight);
    lightVec = tempVec;
    float dist = length(tempVec);
    lightDir.w = clamp(1.0 - position.w * dist * posLight, 0.0, 1.0);
    lightDir.xyz = tempVec / vec3(dist);
}

float computeSpotFalloff(in vec4 lightDirection, in vec3 lightVector) {
    vec3 L = normalize(lightVector);
    vec3 spotdir = normalize(lightDirection.xyz);
    float curAngleCos = dot(-L, spotdir);
    float innerAngleCos = floor(lightDirection.w) * 0.001;
    float outerAngleCos = fract(lightDirection.w);
    float innerMinusOuter = innerAngleCos - outerAngleCos;
    return clamp((curAngleCos - outerAngleCos) / innerMinusOuter,
                 step(lightDirection.w, 0.001), 1.0);
}

// --- Normal mapping ---

vec3 calculateNormal(vec3 N, vec3 T, float tangentW, vec2 uv, uvec2 normalMapHandle) {
    vec3 tangentNormal = texture(sampler2D(normalMapHandle), uv).xyz * 2.0 - 1.0;
    vec3 B = cross(N, T) * tangentW;
    mat3 TBN = mat3(T, B, N);
    return normalize(TBN * tangentNormal);
}

void main() {
    DrawData d = draws[drawId];

    // Base color
    vec4 albedo = d.baseColor;
    if (d.baseColorMapHandle != uvec2(0)) {
        albedo *= texture(sampler2D(d.baseColorMapHandle), texCoord);
    }

    // Metallic and roughness
    float metallic = d.metallic;
    float roughness = d.roughness;
    if (d.metallicRoughnessMapHandle != uvec2(0)) {
        vec4 mr = texture(sampler2D(d.metallicRoughnessMapHandle), texCoord);
        roughness *= mr.g;
        metallic *= mr.b;
    }
    roughness = clamp(roughness, 0.04, 1.0);

    // Normal
    vec3 N = normalize(wNormal);
    if (d.normalMapHandle != uvec2(0) && wTangent.xyz != vec3(0.0)) {
        N = calculateNormal(N, normalize(wTangent.xyz), wTangent.w, texCoord, d.normalMapHandle);
    }

    vec3 V = normalize(g_CameraPosition - wPosition);

    // Dielectric F0
    vec3 F0 = mix(vec3(0.04), albedo.rgb, metallic);

    // Ambient
    vec3 ambient = vec3(0.03);
    if (g_AmbientLightColor.rgb != vec3(0.0)) {
        ambient = g_AmbientLightColor.rgb;
    }
    vec3 Lo = ambient * albedo.rgb * (1.0 - metallic);

    // Iterate lights (3 vec4 per light)
    for (int i = 0; i < NB_LIGHTS; i += 3) {
        vec4 lightColor = g_LightData[i];
        vec4 lightData1 = g_LightData[i + 1];

        if (lightColor.xyz == vec3(0.0)) break;

        vec4 lightDir;
        vec3 lightVec;
        lightComputeDir(wPosition, lightColor.w, lightData1, lightDir, lightVec);

        float attenuation = lightDir.w;

        // Spot light falloff
        if (lightColor.w > 1.0) {
            attenuation *= computeSpotFalloff(g_LightData[i + 2], lightVec);
        }

        vec3 L = lightDir.xyz;
        vec3 H = normalize(V + L);
        float NdotL = max(dot(N, L), 0.0);

        // Cook-Torrance BRDF
        float D = DistributionGGX(N, H, roughness);
        float G = GeometrySmith(N, V, L, roughness);
        vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

        vec3 numerator = D * G * F;
        float denominator = 4.0 * max(dot(N, V), 0.0) * NdotL + 0.0001;
        vec3 specular = numerator / denominator;

        vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);

        Lo += (kD * albedo.rgb / PI + specular) * lightColor.rgb * attenuation * NdotL;
    }

    // Emissive
    vec3 emissive = d.emissive.rgb * d.emissiveIntensity;
    if (d.emissiveMapHandle != uvec2(0)) {
        emissive *= texture(sampler2D(d.emissiveMapHandle), texCoord).rgb;
    }
    Lo += emissive;

    outFragColor = vec4(Lo, albedo.a);
}
