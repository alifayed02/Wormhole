#version 330

uniform sampler2D Face0; // crossing mouth's PARTNER cube
uniform sampler2D Face1;
uniform sampler2D Face2;
uniform sampler2D Face3;
uniform sampler2D Face4;
uniform sampler2D Face5;
uniform sampler2D DeflectionLut; // through-branch azimuth phi(alpha, d)

const float TWO_PI = 6.28318530718;
const float D_MAX = 24.0;          // must match DeflectionLut.D_MAX
const float FRESNEL_POW = 4.0;
const float GLOW_STRENGTH = 0.4;
const vec3  GLOW_COLOR = vec3(0.35, 0.6, 1.0);

layout(std140) uniform CubeBasis { vec4 Fwd[6]; vec4 Right[6]; vec4 Up[6]; };
layout(std140) uniform LensParams { vec4 Center; };       // xyz = camera-relative throat, w = rho
layout(std140) uniform CrossingParams { vec4 Warp; };     // x = intensity, yzw unused

in vec3 vPos;
out vec4 fragColor;

vec3 sampleCube(vec3 d) {
    int best = 0;
    float bestDot = -1e9;
    for (int i = 0; i < 6; i++) {
        float dd = dot(d, Fwd[i].xyz);
        if (dd > bestDot) { bestDot = dd; best = i; }
    }
    float u = dot(d, Right[best].xyz) / bestDot;
    float v = dot(d, Up[best].xyz) / bestDot;
    vec2 uv = vec2(u * 0.5 + 0.5, v * 0.5 + 0.5);
    if (best == 0) return texture(Face0, uv).rgb;
    else if (best == 1) return texture(Face1, uv).rgb;
    else if (best == 2) return texture(Face2, uv).rgb;
    else if (best == 3) return texture(Face3, uv).rgb;
    else if (best == 4) return texture(Face4, uv).rgb;
    return texture(Face5, uv).rgb;
}

void main() {
    vec3 viewRay = normalize(vPos);
    float dist = length(Center.xyz);
    float rho = Center.w;
    vec3 toCenter = normalize(Center.xyz);
    float cosA = clamp(dot(viewRay, toCenter), -1.0, 1.0);
    float alpha = acos(cosA);
    float alphaCrit = asin(clamp(rho / dist, 0.0, 1.0));
    float alphaRatio = alpha / max(alphaCrit, 1e-5);

    if (alphaRatio >= 1.0) {
        discard; // around-rays handled in Task 4; for now show the real frame outside the through-cone
    }

    float alphaN = clamp(alphaRatio, 0.0, 0.999);
    float dCoord = clamp((dist / rho - 1.0) / (D_MAX - 1.0), 0.0, 1.0);
    float phi = texture(DeflectionLut, vec2(alphaN, dCoord)).r * TWO_PI;
    vec3 tang = alpha > 1e-4 ? normalize(viewRay - cosA * toCenter) : vec3(0.0);
    vec3 d = normalize(cos(phi) * toCenter + sin(phi) * tang);

    vec3 col = sampleCube(d);
    float fres = pow(clamp(alphaRatio, 0.0, 1.0), FRESNEL_POW);
    col += GLOW_COLOR * fres * GLOW_STRENGTH;

    fragColor = vec4(col, Warp.x); // alpha = intensity -> crossfade over the real frame
}
