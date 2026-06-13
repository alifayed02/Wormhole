#version 330

// One cube + one LUT per draw (≤7 samplers — the GL backend tracks only 12 units, so the through
// and around branches are rendered as two separate draws rather than 14 samplers in one).
uniform sampler2D Cube0;
uniform sampler2D Cube1;
uniform sampler2D Cube2;
uniform sampler2D Cube3;
uniform sampler2D Cube4;
uniform sampler2D Cube5;
uniform sampler2D Lut;

const float TWO_PI = 6.28318530718;
const float D_MAX = 24.0;          // must match DeflectionLut.D_MAX
const float FRESNEL_POW = 4.0;
const float GLOW_STRENGTH = 0.4;
const vec3  GLOW_COLOR = vec3(0.35, 0.6, 1.0);
const float DELTA_MAX = 2.5;       // must match AroundDeflectionLut.DELTA_MAX
const float B_MAX = 2.6;           // must match AroundDeflectionLut.B_MAX

layout(std140) uniform CubeBasis { vec4 Fwd[6]; vec4 Right[6]; vec4 Up[6]; };
layout(std140) uniform LensParams { vec4 Center; };       // xyz = camera-relative throat, w = rho
layout(std140) uniform CrossingParams { vec4 Warp; };     // x = intensity, y = mode (0 through, 1 around)

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
    if (best == 0) return texture(Cube0, uv).rgb;
    else if (best == 1) return texture(Cube1, uv).rgb;
    else if (best == 2) return texture(Cube2, uv).rgb;
    else if (best == 3) return texture(Cube3, uv).rgb;
    else if (best == 4) return texture(Cube4, uv).rgb;
    return texture(Cube5, uv).rgb;
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
    vec3 tang = alpha > 1e-4 ? normalize(viewRay - cosA * toCenter) : vec3(0.0);
    bool around = Warp.y > 0.5;

    vec3 col;
    if (!around) {
        // THROUGH branch: Cube = partner cube, Lut = DeflectionLut (azimuth phi). Skip around-rays.
        if (alphaRatio >= 1.0) {
            discard;
        }
        float alphaN = clamp(alphaRatio, 0.0, 0.999);
        float dCoord = clamp((dist / rho - 1.0) / (D_MAX - 1.0), 0.0, 1.0);
        float phi = texture(Lut, vec2(alphaN, dCoord)).r * TWO_PI;
        vec3 d = normalize(cos(phi) * toCenter + sin(phi) * tang);
        col = sampleCube(d);
        float fres = pow(clamp(alphaRatio, 0.0, 1.0), FRESNEL_POW);
        col += GLOW_COLOR * fres * GLOW_STRENGTH;
    } else {
        // AROUND branch: Cube = source cube, Lut = AroundLut (bend delta). Skip through-rays.
        if (alphaRatio < 1.0) {
            discard;
        }
        float q = alphaRatio;
        float bend = 0.0;
        if (q <= B_MAX) {
            float bu = (q - 1.0) / (B_MAX - 1.0);
            bend = min(texture(Lut, vec2(bu, 0.5)).r * DELTA_MAX, DELTA_MAX);
        }
        float a2 = alpha - bend; // pulled toward the axis (radial inward bend)
        vec3 d = normalize(cos(a2) * toCenter + sin(a2) * tang);
        col = sampleCube(d);
    }

    fragColor = vec4(col, Warp.x); // alpha = intensity -> crossfade over the real frame
}
