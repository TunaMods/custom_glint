#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC overlay fragment shader — the post-Iris counterpart of
// core/chromatic.fsh. Identical oil-slick synthesis, but the model is re-rendered AFTER Iris finishes
// the frame (the chromatic procedural program can't run in-phase under a pack — Iris swaps it for a
// pack program and the glint goes flat white), so occlusion can't ride the GPU depth test against
// Iris's gbuffer. Instead we sample the committed scene depth (DepthSampler = the main target's depth)
// and keep ONLY the fragment that is the visible front surface — exactly an EQUAL depth test done in
// the shader, robust to any depth-precision mismatch between our re-render and Iris's gbuffer pass.
// (Borrowed from core/glow_silhouette.fsh's per-fragment occlusion.)

uniform sampler2D Sampler0;     // the model texture (elytra/armor/entity) — drives the cutout alpha-test
uniform sampler2D Sampler1;     // palette strip: 1px tall, one texel per colour
uniform sampler2D DepthSampler; // full-res scene depth (main target)

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 noiseCoord;
in vec2 texCoord0;
in vec4 vertexColor;
in float vSeed;
in float vMorph;
in float vCount;
in float viewDist;
in vec4 screenPos;

out vec4 fragColor;

const float DENSITY = 7.0; // noise cells across one UV unit — tunable look/scale knob

// Occlusion tolerance, in BLOCKS of linear view distance (same rationale as glow_silhouette: a blocks
// epsilon is distance-independent, unlike a window-depth epsilon). A fragment farther than the scene
// surface by more than this is occluded (or a back face, since cull is off) and is dropped, so only the
// model's own visible front faces paint.
const float OCCLUSION_BIAS = 0.10;

float hash(vec2 p) {
    p = fract(p * vec2(127.31, 311.7));
    p += dot(p, p + 41.27);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int o = 0; o < 4; o++) {
        v += amp * vnoise(p);
        p *= 2.03;
        amp *= 0.5;
    }
    return v;
}

vec3 hue(float h) {
    vec3 p = abs(fract(vec3(h) + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}

vec3 cgChroma(int n, float n1, float n2, float t) {
    if (n <= 0) {
        return hue(fract(n1 * 1.6 + n2 * 0.4 + t * 0.02));
    }
    float fpos = fract(n1 * 1.3 + n2 * 0.3 + t * 0.02) * float(n);
    int i0 = int(floor(fpos)) % n;
    int i1 = (i0 + 1) % n;
    vec3 c0 = texelFetch(Sampler1, ivec2(i0, 0), 0).rgb;
    vec3 c1 = texelFetch(Sampler1, ivec2(i1, 0), 0).rgb;
    return mix(c0, c1, fract(fpos));
}

void main() {
    // Cutout: the model mesh (elytra wing, armor) is rectangular — its real silhouette is the texture's
    // alpha. The in-phase glint gets this for free (the cutout draw only wrote depth on opaque texels);
    // the post-Iris re-render must alpha-test the model texture itself, exactly like core/glow_silhouette.
    if (texture(Sampler0, texCoord0).a < 0.1) {
        discard;
    }

    // Per-fragment occlusion: reconstruct the scene's eye distance at this pixel and discard anything that
    // isn't the visible front surface (occluded geometry, or our own back faces — cull is off).
    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(DepthSampler, uv).r;
    float ndc = sceneDepth * 2.0 - 1.0;
    float sceneDist = ProjMat[3][2] / (ndc + ProjMat[2][2]);
    if (viewDist > sceneDist + OCCLUSION_BIAS) {
        discard;
    }

    float t = GameTime * 5000.0 * max(0.05, vMorph);
    vec2 uvn = noiseCoord * DENSITY;
    vec2 so = vec2(vSeed * 3.1, vSeed * 6.7);

    int n = int(vCount + 0.5);

    float n1 = fbm(uvn + so + vec2(t * 0.10, -t * 0.07));
    float n2 = fbm(uvn * 1.7 + so.yx - vec2(t * 0.06, t * 0.04));

    vec3 col = cgChroma(n, n1, n2, t);
    float bright = 0.7 + 0.3 * n2;

    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    fragColor = vec4(col * bright * fade * vertexColor.a, vertexColor.a);
}
