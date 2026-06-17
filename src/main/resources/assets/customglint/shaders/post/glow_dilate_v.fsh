#version 330

// Custom Glints glow-outline — SEPARABLE dilation, VERTICAL pass + ring extraction. The second half of the
// decomposition started in post/glow_dilate_h (read its header for the full derivation). Consumes the
// horizontal budget field (BudgetSampler: rgb = nearest reaching source colour, alpha = encoded
// gH = max_dx(R²−dx²), or 0 = empty) and the original mask (MaskSampler) for the outside-shape test.
//
// Output is pixel-identical to the old post/glow_outline: a pixel is a ring pixel iff it is OUTSIDE the
// entity's full shape (mask.a == 0) AND some visible source covers it within its Euclidean reach
// (max_dy(gH − dy²) >= 0). Writes the ring (rgb = source colour, a = 1) blended over the scene
// (TRANSLUCENT, same as the old composite); discards everything else. Both samplers MUST be NEAREST — the
// encoded budget must not be filtered, and the mask alpha is a classifier, not a continuous value.

uniform sampler2D BudgetSampler;
uniform sampler2D MaskSampler;

in vec2 texCoord;

out vec4 fragColor;

// Must match post/glow_dilate_h exactly.
const float MAX_THICKNESS = 7.0;
const int   SEARCH        = 7;
const float MAXR2         = MAX_THICKNESS * MAX_THICKNESS; // 49.0
const float EPS           = 1.0 / 255.0;

void main() {
    if (texture(MaskSampler, texCoord).a > 0.0) {
        discard; // inside the entity's full shape: never a ring pixel (matches old post/glow_outline:27)
    }
    vec2 texel = 1.0 / vec2(textureSize(BudgetSampler, 0));
    float bestRem = -1.0;        // best (gH − dy²); >= 0 → covered
    vec3  bestC   = vec3(0.0);
    for (int dy = -SEARCH; dy <= SEARCH; dy++) {
        vec4 col = texture(BudgetSampler, texCoord + vec2(0.0, float(dy)) * texel);
        if (col.a > 0.0) {                                    // a reaching source exists in that column
            float gH  = (col.a - EPS) / (1.0 - EPS) * MAXR2;  // decode the horizontal budget
            float rem = gH - float(dy * dy);
            if (rem > bestRem) { bestRem = rem; bestC = col.rgb; }
        }
    }
    if (bestRem >= 0.0) {
        fragColor = vec4(bestC, 1.0);
    } else {
        discard;
    }
}
