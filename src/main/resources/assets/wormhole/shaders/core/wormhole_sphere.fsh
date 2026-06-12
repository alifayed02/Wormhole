#version 330

uniform sampler2D Face0;          // the PARTNER's full live cubemap (the other side)
uniform sampler2D Face1;
uniform sampler2D Face2;
uniform sampler2D Face3;
uniform sampler2D Face4;
uniform sampler2D Face5;
uniform sampler2D DeflectionLut;  // baked DnegLensModel.trace: emergent azimuth phi(alpha, d)

const float TWO_PI = 6.28318530718;
const float D_MAX = 24.0;          // must match DeflectionLut.D_MAX
const float FRESNEL_POW = 4.0;
const float GLOW_STRENGTH = 0.4;
const vec3  GLOW_COLOR = vec3(0.35, 0.6, 1.0);

layout(std140) uniform CubeBasis {
    vec4 Fwd[6];    // per-face camera forward (world)
    vec4 Right[6];
    vec4 Up[6];
};

layout(std140) uniform LensParams {
    vec4 Center;    // xyz = camera-relative mouth centre, w = radius
};

in vec3 vDir;

out vec4 fragColor;

void main() {
    vec3 surf = normalize(vDir);
    vec3 fragPos = Center.xyz + Center.w * surf;
    vec3 viewRay = normalize(fragPos);

    // 1:1 inputs to the geodesic: camera distance d (rho units) and incidence angle alpha.
    float dist = length(Center.xyz);
    float rho = Center.w;
    vec3 toCenter = normalize(Center.xyz);
    float cosA = clamp(dot(viewRay, toCenter), -1.0, 1.0);
    float alpha = acos(cosA);
    float alphaCrit = asin(clamp(rho / dist, 0.0, 1.0));
    float alphaRatio = alpha / max(alphaCrit, 1e-5);
    float alphaN = clamp(alphaRatio, 0.0, 0.999);
    float dCoord = clamp((dist / rho - 1.0) / (D_MAX - 1.0), 0.0, 1.0);

    // Real DNeg geodesic: the emergent azimuth phi for this incidence + distance (verified model).
    float phi = texture(DeflectionLut, vec2(alphaN, dCoord)).r * TWO_PI;
    vec3 tang = alpha > 1e-4 ? normalize(viewRay - cosA * toCenter) : vec3(0.0);
    vec3 d = normalize(cos(phi) * toCenter + sin(phi) * tang);   // emergent direction on the other side

    // Sample the partner's full cubemap in that direction (any direction is valid — no FOV limit).
    int best = 0;
    float bestDot = -1e9;
    for (int i = 0; i < 6; i++) {
        float dd = dot(d, Fwd[i].xyz);
        if (dd > bestDot) { bestDot = dd; best = i; }
    }
    float u = dot(d, Right[best].xyz) / bestDot;
    float v = dot(d, Up[best].xyz) / bestDot;
    vec2 uv = vec2(u * 0.5 + 0.5, v * 0.5 + 0.5);
    vec3 col;
    if (best == 0) col = texture(Face0, uv).rgb;
    else if (best == 1) col = texture(Face1, uv).rgb;
    else if (best == 2) col = texture(Face2, uv).rgb;
    else if (best == 3) col = texture(Face3, uv).rgb;
    else if (best == 4) col = texture(Face4, uv).rgb;
    else col = texture(Face5, uv).rgb;

    // Einstein-ring glow at the silhouette (where phi diverges and images pile up).
    float fres = pow(clamp(alphaRatio, 0.0, 1.0), FRESNEL_POW);
    col += GLOW_COLOR * fres * GLOW_STRENGTH;

    fragColor = vec4(col, 1.0);
}
