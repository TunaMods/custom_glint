#version 150

// Custom Glints CHROMATIC BAKE fragment shader. Synthesises the flowing oil-slick from value-noise fractal
// Brownian motion across one 0..1 quad; ChromaticTextureBaker draws it into an offscreen texture once per frame,
// and that texture is then fed to the ordinary glint RenderTypes. The colour comes from a palette strip
// (Sampler1; the colour count rides vCount). With NO colours it falls back to a full-spectrum hue swept from the
// noise. The per-trim seed offsets every field so two chromatic trims never produce the same pattern.
//
// This writes the slick; it does not blend it. chromatic.json declares a ONE/ZERO "replace" blend for that (see
// the note there). The additive GLINT blend, the fog fade and GlintAlpha are all applied later by the glint
// program that samples this texture - exactly as they are for the PNG designs.

uniform sampler2D Sampler1; // palette strip: 1px tall, one texel per colour

in vec2 bakeUV;
in vec2 vSeed;
in vec2 vFlow1;
in vec2 vFlow2;
in float vCount;
in float vHue;

out vec4 fragColor;

// Noise cells across the baked texture. LOAD-BEARING: must equal ChromaticTextureBaker.PERIOD - it is both the
// cell count spread across 0..1 AND the lattice period wrapped below, and the bake tiles under GL_REPEAT only
// while those are the same number.
const float DENSITY = 7.0;

float hash(vec2 p) {
    p = fract(p * vec2(127.31, 311.7));
    p += dot(p, p + 41.27);
    return fract(p.x * p.y);
}

// Value noise whose lattice wraps at `per`, so the field repeats exactly every `per` cells and the bake is
// seamless. mod() handles the negative cells a seed/flow offset produces.
float vnoise(vec2 p, float per) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(mod(i, per));
    float b = hash(mod(i + vec2(1.0, 0.0), per));
    float c = hash(mod(i + vec2(0.0, 1.0), per));
    float d = hash(mod(i + vec2(1.0, 1.0), per));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Octave decorrelation. The pre-bake fbm stepped frequency by 2.03 to keep octaves off each other's lattice; a
// non-integer step cannot tile (the octave's period stops dividing the field's), so the step is 2.0 and the
// octaves are pulled apart by these offsets instead. Translating a periodic field keeps it periodic, so this is
// free.
const vec2 OCT[4] = vec2[4](vec2(0.0, 0.0), vec2(5.2, 1.3), vec2(1.7, 9.2), vec2(8.3, 2.8));

// Every octave's lattice period is `per * freq`, so each octave repeats over exactly `per` cells of p and the sum
// does too.
float fbm(vec2 p, float per) {
    float v = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int o = 0; o < 4; o++) {
        v += amp * vnoise(p * freq + OCT[o], per * freq);
        freq *= 2.0;
        amp *= 0.5;
    }
    return v;
}

// h in 0..1 -> rainbow rgb (no white desaturation)
vec3 hue(float h) {
    vec3 p = abs(fract(vec3(h) + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}

// Maps the noise fields to a colour for the continuous (full-coverage) slick: a full-spectrum rainbow when no
// palette is set (n<=0), else a continuous sweep through the palette that wraps last->first so adjacent colours
// blend. No threshold / no gaps.
vec3 cgChroma(int n, float n1, float n2) {
    if (n <= 0) {
        return hue(fract(n1 * 1.6 + n2 * 0.4 + vHue));
    }
    float fpos = fract(n1 * 1.3 + n2 * 0.3 + vHue) * float(n);
    int i0 = int(floor(fpos)) % n;
    int i1 = (i0 + 1) % n;
    vec3 c0 = texelFetch(Sampler1, ivec2(i0, 0), 0).rgb;
    vec3 c1 = texelFetch(Sampler1, ivec2(i1, 0), 0).rgb;
    return mix(c0, c1, fract(fpos));
}

void main() {
    vec2 uv = bakeUV * DENSITY;
    int n = int(vCount + 0.5);

    // Continuous oil-slick: FULL coverage, the colour comes from a smooth noise field that flows over time (no
    // thresholded blobs / no gaps). Two fields decorrelate the colour position from the brightness so it reads as
    // a slick with depth rather than a flat wash. Field 2 samples at 2x frequency, hence the 2x lattice period.
    float n1 = fbm(uv + vSeed + vFlow1, DENSITY);
    float n2 = fbm(uv * 2.0 + vSeed.yx + vFlow2, DENSITY * 2.0);

    vec3 col = cgChroma(n, n1, n2);
    float bright = 0.7 + 0.3 * n2;

    // Alpha 1: the glint program that samples this discards below 0.1, and the slick is full-coverage. Brightness
    // rides the RGB, which is also what the additive GLINT blend keys off.
    fragColor = vec4(col * bright, 1.0);
}
