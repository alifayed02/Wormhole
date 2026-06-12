#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

uniform sampler2D SceneColor;     // the live on-screen scene (the surrounding pixels we warp)
uniform sampler2D DeflectionLut;  // DNeg around-ray bending angle delta(b), normalized by DELTA_MAX

layout(std140) uniform LensParams {
    vec4 Center;   // xyz = camera-relative mouth centre, w = radius (rho)
};

const float B_MAX = 2.6;          // must match AroundDeflectionLut.B_MAX (influence radius, rho units)
const float DELTA_MAX = 2.5;      // must match AroundDeflectionLut.DELTA_MAX
const float BEND_TO_UV = 0.28;    // radial pull strength (deflection -> screen-UV displacement)
// -1 = inward (physical: surroundings compress toward the rim into the Einstein ring); +1 flips it.
const float BEND_SIGN = -1.0;
const float V_FLIP = 0.0;

in vec3 vPos;

out vec4 fragColor;

void main() {
    vec3 viewRay = normalize(vPos);
    float dist = length(Center.xyz);
    float rho = Center.w;
    vec3 toCenter = normalize(Center.xyz);
    float cosA = clamp(dot(viewRay, toCenter), -1.0, 1.0);
    float alpha = acos(cosA);

    // Angular radius from the mouth: 1 at the silhouette, growing outward. Unlike the impact parameter
    // (dist·sin(alpha)/rho), this never saturates when the camera is close, so the boundary stays a
    // clean circle at every distance instead of revealing the square billboard.
    float alphaCrit = asin(clamp(rho / dist, 0.0, 1.0));
    float q = alpha / max(alphaCrit, 1e-5);

    // Inside the silhouette is the window (sphere pass); beyond the influence cone, no lensing.
    if (q < 1.0 || q > B_MAX) {
        discard;
    }

    // Paper's geodesic deflection: diverges at the rim (Einstein ring / wrap-around), fades to ~0 at the
    // edge of influence. A smooth falloff (1 at the silhouette -> 0 at the edge) gates the whole warp so
    // the displacement dissolves to identity at the edge — no cutoff, just pixels pulled less and less.
    float u = (q - 1.0) / (B_MAX - 1.0);
    float falloff = smoothstep(1.0, 0.0, u);
    float delta = min(texture(DeflectionLut, vec2(u, 0.5)).r * DELTA_MAX, DELTA_MAX);

    // Warp the on-screen pixels around the mouth: a radial pull toward/through the silhouette plus a
    // tangential swirl, both scaled by the deflection — strongest at the rim, gone by the edge.
    vec2 baseUv = gl_FragCoord.xy / vec2(textureSize(SceneColor, 0));
    baseUv.y = mix(baseUv.y, 1.0 - baseUv.y, V_FLIP);

    vec4 cClip = ProjMat * ModelViewMat * vec4(Center.xyz, 1.0);
    vec2 centerUv = cClip.xy / cClip.w * 0.5 + 0.5;
    centerUv.y = mix(centerUv.y, 1.0 - centerUv.y, V_FLIP);

    vec2 size = vec2(textureSize(SceneColor, 0));
    float aspect = size.x / size.y;
    vec2 toEdge = (baseUv - centerUv) * vec2(aspect, 1.0);   // aspect-corrected so radial is circular
    float dlen = length(toEdge);
    vec2 radial = dlen > 1e-5 ? toEdge / dlen : vec2(0.0);

    // Light bends RADIALLY toward the throat (no tangential swirl — a static wormhole doesn't rotate
    // light). The deflection diverges at the rim and eases to zero at the outer edge. Sampling the scene
    // displaced inward by it makes the surroundings compress into the ring at the silhouette and stretch
    // into tangential arcs there — the "wrap around the mouth" falls out of the radial compression.
    float pull = delta * BEND_TO_UV * BEND_SIGN * falloff;
    vec2 uv = clamp(baseUv + radial * pull * vec2(1.0 / aspect, 1.0), 0.0, 1.0);

    fragColor = vec4(texture(SceneColor, uv).rgb, 1.0);
}
