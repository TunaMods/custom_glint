#version 330

// Custom Glints glow-outline composite. Reads ONE combined mask of the glowing entities (MaskSampler,
// produced by core/glow_silhouette in a single model render), whose alpha encodes shape + visibility:
//   alpha == 0.0        — empty.
//   0.0 < alpha < 0.5   — in shape but occluded.
//   alpha >= 0.5        — visible; (alpha - 0.5) * 2 is the distance thickness factor; rgb = glow colour.
//
// A pixel becomes a ring pixel only when it is OUTSIDE the entity's full shape (alpha == 0 → never trace
// interior or cutout-gap edges) AND has a VISIBLE neighbour (alpha >= 0.5) within
// (MAX_THICKNESS * neighbour-thickness) texels (so occluded outer edges stay hidden and the ring thins
// with distance). It then takes that neighbour's colour. No dilated geometry → no depth band, no flicker.

uniform sampler2D MaskSampler;

in vec2 texCoord;

out vec4 fragColor;

// Full-res texels at DOWNSCALE=1 (1 texel ≈ 1 screen px). MAX_THICKNESS=3.5 → ~3.5px near-camera outline
// (same screen thickness as the old half-res 1.75); SEARCH bounds the neighbour loop. If you raise
// DOWNSCALE, halve these per step (e.g. DOWNSCALE=2 → MAX_THICKNESS 1.75, SEARCH 2).
const float MAX_THICKNESS = 3.5;
const int SEARCH = 4;            // ceil(MAX_THICKNESS)

void main() {
    if (texture(MaskSampler, texCoord).a > 0.0) {
        discard; // inside the entity's full shape: never a ring pixel (kills interior + gap-edge rings)
    }

    vec2 texel = 1.0 / vec2(textureSize(MaskSampler, 0));
    for (int dx = -SEARCH; dx <= SEARCH; dx++) {
        for (int dy = -SEARCH; dy <= SEARCH; dy++) {
            vec4 n = texture(MaskSampler, texCoord + vec2(float(dx), float(dy)) * texel);
            if (n.a >= 0.5) {                                     // visible neighbour only
                float thickness = (n.a - 0.5) * 2.0;             // recover distance thickness factor
                float allowed = MAX_THICKNESS * thickness;
                if (length(vec2(float(dx), float(dy))) <= allowed) {
                    fragColor = vec4(n.rgb, 1.0);
                    return;
                }
            }
        }
    }
    discard; // no visible neighbour within the scaled radius: leave the scene pixel alone
}
