#version 150

#moj_import <fog.glsl>

// Custom Glints PROCEDURAL CHROMATIC fragment shader (1.21.1 port of the 26.1 core/chromatic.fsh).
// Generates a flowing oil-slick from value-noise fractal Brownian motion instead of sampling a design
// texture. The colour comes from a palette strip (Sampler1; the colour count rides vCount). With NO
// colours the shader falls back to a full-spectrum hue swept from the noise. The per-trim seed (vSeed)
// offsets every field so two chromatic trims never produce the same pattern. Blended through the same
// GLINT transparency as the texture glints (the RenderType sets it), so this reads as an additive foil.

uniform sampler2D Sampler1; // palette strip: 1px tall, one texel per colour

uniform vec4 ColorModulator;
uniform float GlintAlpha;
uniform float GameTime;
uniform float FogStart;
uniform float FogEnd;

in float vertexDistance;
in vec2 noiseCoord;
in float vSeed;
in float vMorph;
in float vCount;

out vec4 fragColor;

const float DENSITY = 7.0; // noise cells across one UV unit: tunable look/scale knob

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

// h in 0..1 -> rainbow rgb (no white desaturation)
vec3 hue(float h) {
    vec3 p = abs(fract(vec3(h) + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}

// Maps the noise fields to a colour for the continuous (full-coverage) slick: a full-spectrum rainbow when
// no palette is set (n<=0), else a continuous sweep through the palette that wraps last->first so adjacent
// colours blend. No threshold / no gaps.
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
    float t = GameTime * 5000.0 * max(0.05, vMorph); // GameTime wraps 0..1 over a MC day; scale to a flow rate (× speed)
    vec2 uv = noiseCoord * DENSITY;
    vec2 so = vec2(vSeed * 3.1, vSeed * 6.7);         // seed decorrelates the whole pattern per trim

    int n = int(vCount + 0.5);

    // Continuous oil-slick: FULL coverage, the colour comes from a smooth noise field that flows over time
    // (no thresholded blobs / no gaps). Two fields decorrelate the colour position from the brightness so it
    // reads as a slick with depth rather than a flat wash.
    float n1 = fbm(uv + so + vec2(t * 0.10, -t * 0.07));
    float n2 = fbm(uv * 1.7 + so.yx - vec2(t * 0.06, t * 0.04));

    vec3 col = cgChroma(n, n1, n2, t);
    float bright = 0.7 + 0.3 * n2;

    // Master fade rides ColorModulator.a (the renderer sets ColorModulator white), times the distance fog
    // fade and the engine glint-alpha: same fade chain vanilla rendertype_glint uses.
    float fade = linear_fog_fade(vertexDistance, FogStart, FogEnd) * GlintAlpha * ColorModulator.a;
    fragColor = vec4(col * bright * fade, 1.0);
}
