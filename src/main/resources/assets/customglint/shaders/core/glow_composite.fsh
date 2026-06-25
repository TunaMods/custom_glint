#version 150

// Custom Glints glow-outline composite (1.21.1) — PER-OBJECT id-aware, PER-CATEGORY thickness, single
// pass, constant thickness (no depth). The mask (glow_silhouette) packs a per-object KEY + visibility
// into alpha:
//   0        : empty (no silhouette here).
//   1..127   : in-shape but occluded (not used yet — the silhouette currently marks everything visible).
//   128..255 : VISIBLE, key = v - 128.
// The key is (category << 5) | id: top 2 bits = category, low 5 = id. rgb is that object's glow colour.
//
// A pixel rings iff a VISIBLE silhouette of a DIFFERENT id is within that source's CATEGORY thickness —
// the outer edge of every object plus the boundary wherever two objects overlap, each category its own
// width. Sampler0 (mask) MUST be NEAREST: alpha is a packed classifier, not a colour.

uniform sampler2D Sampler0;

in vec2 texCoord;

out vec4 fragColor;

// Per-category outline thickness, in texels (~screen px). index = category:
//   [0] entity   [1] armor   [2] item (3rd-person held / dropped)   [3] held (first-person)
const float THICKNESS[4] = float[](4.0, 4.0, 3.0, 7.0);
// SEARCH = kernel radius = the max thickness of the categories ACTUALLY rendered. Only world ITEMS
// (category 2, thickness 3) are wired right now, so this is 3, NOT 7: a source farther than a pixel's
// own ring width can never ring it, so searching past the widest active ring is pure wasted work
// (radius 7 ≈ 149 taps vs radius 3 ≈ 29 — a ~5x lossless cut; the ring is pixel-identical). RAISE this
// to match when entity/armor/held-FP (thickness 4/4/7) get wired, or their rings will clip.
const int   SEARCH       = 3;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    int vP = int(texture(Sampler0, texCoord).a * 255.0 + 0.5);
    int keyP = vP >= 128 ? vP - 128 : vP;

    float maxR2 = float(SEARCH * SEARCH);
    for (int dx = -SEARCH; dx <= SEARCH; dx++) {
        for (int dy = -SEARCH; dy <= SEARCH; dy++) {
            float d2 = float(dx * dx + dy * dy);
            if (d2 > maxR2) continue;
            vec2 srcUV = texCoord + vec2(float(dx), float(dy)) * texel;
            vec4 n = texture(Sampler0, srcUV);
            int vN = int(n.a * 255.0 + 0.5);
            if (vN < 128) continue;                   // visible silhouettes are the only ring sources
            int keyN = vN - 128;
            if ((keyN & 31) == (keyP & 31)) continue; // same identity -> not a boundary
            float r = THICKNESS[keyN >> 5];
            if (d2 <= r * r) {
                // Reject isolated specks (morphological opening). A lone stray "visible" texel — e.g. a
                // sub-pixel coverage flicker at a convex corner under Sodium's reduced immediate-mode
                // vertex precision — would otherwise be ballooned by the dilation into a round bead just
                // outside the real outline (flickering, Sodium-only). A genuine silhouette source sits in
                // a solid shape, so require >= 2 of its 8 neighbours to also be silhouette. The 8-cell
                // (not 4-cell) test is what keeps thin DIAGONAL features — a sword blade's edge runs on
                // the diagonal, so its along-edge neighbours are the corner cells; a 4-connected test
                // would erode the whole blade outline. Isolated/2-texel specks still have < 2 and drop.
                int support = 0;
                if (texture(Sampler0, srcUV + vec2( texel.x, 0.0)).a > 0.002) support++;
                if (texture(Sampler0, srcUV + vec2(-texel.x, 0.0)).a > 0.002) support++;
                if (texture(Sampler0, srcUV + vec2(0.0,  texel.y)).a > 0.002) support++;
                if (texture(Sampler0, srcUV + vec2(0.0, -texel.y)).a > 0.002) support++;
                if (texture(Sampler0, srcUV + vec2( texel.x,  texel.y)).a > 0.002) support++;
                if (texture(Sampler0, srcUV + vec2( texel.x, -texel.y)).a > 0.002) support++;
                if (texture(Sampler0, srcUV + vec2(-texel.x,  texel.y)).a > 0.002) support++;
                if (texture(Sampler0, srcUV + vec2(-texel.x, -texel.y)).a > 0.002) support++;
                if (support < 2) continue;
                fragColor = vec4(n.rgb, 1.0);
                return;
            }
        }
    }
    discard;
}
