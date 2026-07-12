#version 150

// Custom Glints PROCEDURAL CHROMATIC overlay fragment shader (1.21.1). Same oil-slick synthesis as
// core/chromatic.fsh, but drawn AFTER the shader pack finishes the frame (see the vsh header), so occlusion
// can't ride the GPU depth test against the pack's gbuffer. Instead it samples the committed scene depth
// (Sampler2 = main target depth) and keeps ONLY the visible front surface — an EQUAL depth test done in the
// shader, robust to any precision mismatch between our re-render and the pack's pass (mirrors
// core/glow_silhouette.fsh). The model mesh is rectangular, so its real shape is the texture alpha
// (Sampler0) — alpha-discard carves the cutout the in-phase depth test would otherwise give for free.

uniform sampler2D Sampler0; // model texture (armor/entity) — drives the cutout alpha-test
uniform sampler2D Sampler1; // palette strip: 1px tall, one texel per colour
uniform sampler2D Sampler2; // full-res scene depth (main target), bound by the drain

uniform mat4 ProjMat;
uniform vec4 ColorModulator;
uniform float GameTime;

in vec2 noiseCoord;
in vec2 texCoord0;
in float vSeed;
in float vMorph;
in float vCount;
in float viewDist;
in vec4 screenPos;

out vec4 fragColor;

const float DENSITY = 7.0;          // noise cells across one UV unit (matches core/chromatic.fsh)
// Occlusion tolerance in BLOCKS of linear view distance. This must be TIGHTER than glow_silhouette's ring
// bias: the chromatic overlay is a FILL over the whole armor surface, so it needs to self-occlude within the
// player model (an arm ~0.1 blocks in front of the chestplate must hide the chestplate slick behind it). A
// loose bias let the slick draw over the arm. Kept just large enough to swallow the armor's own
// VIEW_OFFSET depth epsilon + reconstruction imprecision so the chestplate's own surface isn't discarded.
const float OCCLUSION_BIAS = 0.02;

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
    // Cutout: the mesh is rectangular; its real shape is the model-texture alpha (the in-phase draw got this
    // free from the cutout depth write). Discard transparent texels so the slick follows the real armor shape.
    if (textureLod(Sampler0, texCoord0, 0.0).a < 0.1) {
        discard;
    }

    // Per-fragment occlusion: reconstruct the scene's eye distance at this pixel and drop anything not on the
    // visible front surface (occluded geometry, or our own back faces — cull is off). sceneDepth<=0 => unbound
    // / near plane => treat as visible so occlusion can never erase the whole slick.
    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(Sampler2, uv).r;
    float ndc = sceneDepth * 2.0 - 1.0;
    float sceneDist = ProjMat[3][2] / (ndc + ProjMat[2][2]);
    // Small distance-scaled term for reconstruction imprecision that grows with distance, but far tighter
    // than the ring's 0.03 so close self-occlusion (arm over chestplate) still works.
    float bias = OCCLUSION_BIAS + viewDist * 0.006;
    if (sceneDepth > 0.0 && viewDist > sceneDist + bias) {
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
    // NOTE: no GlintAlpha here — the engine only sets it during the in-phase foil pass; in this post-Iris
    // re-render it is stale/0, which multiplied the slick to black (additive black = invisible). ColorModulator
    // is white (the RT sets it), so the slick keeps full brightness and the composite blends it as a foil.
    float fade = ColorModulator.a;

    // rgb = the slick, alpha = coverage (1 where the model drew). The composite blits this additively over
    // the pack's final image.
    fragColor = vec4(col * bright * fade, 1.0);
}
