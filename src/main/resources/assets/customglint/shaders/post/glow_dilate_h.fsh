#version 330

// Custom Glints glow-outline — SEPARABLE dilation, HORIZONTAL pass. Together with post/glow_dilate_v this
// replaces the old single-pass 9x9 (81-tap) neighbour search in post/glow_outline with two 1-D passes
// (~9 taps each). It is an EXACT decomposition of the per-source-radius Euclidean dilation, via the
// "subtract squared distance" max-plus identity:
//
//     covered(P) ⟺ max over visible sources S of ( R_S² − dx² − dy² ) >= 0
//               =  max_dy ( [ max_dx ( R_S² − dx² ) ] − dy² ) >= 0
//
// where (dx,dy) = S − P in texels and R_S = MAX_THICKNESS * thickness_S is the source's reach (the same
// `allowed = MAX_THICKNESS * thickness` the old composite tested with `length((dx,dy)) <= allowed`).
//
// This pass computes the inner bracket per pixel: gH = max_dx( R_S² − dx² ) over the VISIBLE mask texels
// on the row, and carries that winning source's colour. post/glow_dilate_v does the outer max_dy, the
// `>= 0` test, and the outside-shape discard. Reads the mask core/glow_silhouette produces (alpha encodes
// shape + visibility + distance thickness). MaskSampler MUST be sampled NEAREST.
//
// Output (RGBA8, no blend): rgb = winning source colour; alpha = gH encoded into (EPS, 1] over [0, MAXR2],
// with alpha == 0 reserved as the "no reaching source on this row" sentinel (kept distinct from gH == 0 by
// the EPS floor). gH < 0 stores the sentinel — such a column can never satisfy gH − dy² >= 0 for any dy.

uniform sampler2D MaskSampler;

in vec2 texCoord;

out vec4 fragColor;

// Must match post/glow_dilate_v: MAX_THICKNESS = 7.0, SEARCH = ceil(MAX_THICKNESS). MAX_THICKNESS is the
// ring half-width in texels (≈ screen px at DOWNSCALE=1). thickness = (a − 0.5) * 2 from the mask (now a
// constant 1.0 — see core/glow_silhouette); R = MAX_THICKNESS * thickness; R² max = MAX_THICKNESS² = 49.0
// (the alpha encode range). Raise MAX_THICKNESS for a thicker outline — but SEARCH MUST rise with it, or
// the neighbour loop clamps the ring (raising MAX_THICKNESS alone does nothing; that was the old gotcha).
const float MAX_THICKNESS = 7.0;
const int   SEARCH        = 7;            // ceil(MAX_THICKNESS)
const float MAXR2         = MAX_THICKNESS * MAX_THICKNESS; // 49.0
const float EPS           = 1.0 / 255.0;  // keeps an exact gH == 0 distinct from the empty sentinel (0)

void main() {
    vec2 texel = 1.0 / vec2(textureSize(MaskSampler, 0));
    float bestG = -1.0;          // best (R² − dx²); < 0 → no reaching source (sentinel)
    vec3  bestC = vec3(0.0);
    for (int dx = -SEARCH; dx <= SEARCH; dx++) {
        vec4 n = texture(MaskSampler, texCoord + vec2(float(dx), 0.0) * texel);
        if (n.a >= 0.5) {                       // visible source only (occluded band 0.25 never dilates)
            float t  = (n.a - 0.5) * 2.0;       // recover distance thickness factor
            float r  = MAX_THICKNESS * t;       // reach in texels
            float g  = r * r - float(dx * dx);  // budget remaining at this column after the horizontal step
            if (g > bestG) { bestG = g; bestC = n.rgb; }
        }
    }
    if (bestG < 0.0) {
        fragColor = vec4(0.0);   // empty sentinel
        return;
    }
    float a = mix(EPS, 1.0, clamp(bestG / MAXR2, 0.0, 1.0));
    fragColor = vec4(bestC, a);
}
