#version 330

#moj_import <minecraft:projection.glsl>

// Custom Glints glow-outline composite — PER-OBJECT id-aware, PER-CATEGORY thickness, single pass. The
// mask (core/glow_silhouette) packs a per-object KEY + visibility into alpha:
//   0        : empty (no silhouette here).
//   1..127   : IN-SHAPE but OCCLUDED, key = v.
//   128..255 : VISIBLE, key = v - 128.
// The key is (category << 5) | id: the top 2 bits are the object's category, the low 5 are its id. rgb is
// that object's glow colour.
//
// A pixel rings iff a VISIBLE silhouette of a DIFFERENT object (different id) is within that source's
// CATEGORY thickness — so the outer edge of every object PLUS the boundary wherever two objects overlap,
// and each category can have its own ring width. MaskSampler MUST be NEAREST (alpha is a packed classifier).
//
// Cost note: an in-shape interior pixel with no other object within reach scans the whole kernel before
// discarding (no cheap "deep interior" early-out). Fine for normal scenes; heavier under extreme entity
// crams — mitigate with outlineRenderScale (downscale) or smaller THICKNESS values.

uniform sampler2D MaskSampler;
uniform sampler2D DepthSampler;   // full-res scene depth (bound by CustomGlintRenderer.compositeGlowOutline)

in vec2 texCoord;

out vec4 fragColor;

// Per-category outline thickness, in texels (≈ screen px at DOWNSCALE=1). EDIT to taste — index = category:
//   [0] entity   [1] armor   [2] item (3rd-person held / dropped)   [3] held (first-person)
// SEARCH MUST be >= ceil(max of these). If you set any above 7, raise SEARCH to match AND raise
// EntityGlintRender.SCISSOR_PAD to >= that + ~4 so the thicker ring isn't clipped at the group rect edge.
// These are the FULL widths, applied at or nearer than REF_DIST; beyond it the ring narrows with distance.
const float THICKNESS[4] = float[](4.0, 4.0, 3.0, 7.0);
const int   SEARCH       = 7;             // ceil(max(THICKNESS))

// Distance-proportional thinning. A constant pixel-width ring looks FATTER the farther (smaller on screen)
// an object gets. To stop that, each source's reach scales by REF_DIST / itsDistance, clamped to [MIN_SCALE,1]:
// closer than REF_DIST keeps the full THICKNESS, farther narrows ∝ 1/distance so the ring tracks the object's
// apparent size. The source distance is the scene depth at the source texel, reconstructed to linear eye
// distance via ProjMat (the same math core/glow_silhouette uses). MIN_SCALE floors it so far rings don't vanish.
const float REF_DIST  = 4.0;     // blocks; at/under this the ring stays full THICKNESS
const float MIN_SCALE = 0.40;    // never thinner than this fraction of THICKNESS (keeps a hairline at range)

// Ring occlusion bias, in BLOCKS. A source's ring skips any pixel whose scene geometry is at least this
// much nearer than the source edge the ring comes from. Applied to ALL categories: it keeps a farther
// object's ring from painting over a nearer object standing in front of it (two players side by side no
// longer bleed their outlines onto each other), and keeps a held/dropped item's halo off the hand gripping
// it. The ring expands outward from a visible edge regardless of what is in front of those outer pixels, so
// without this test the halo draws over whatever is nearer. Measured in blocks so it is distance-independent
// (same basis as core/glow_silhouette's OCCLUSION_BIAS).
const float RING_OCCLUSION_BIAS = 0.10;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(MaskSampler, 0));
    int vP = int(texture(MaskSampler, texCoord).a * 255.0 + 0.5);
    int keyP = vP >= 128 ? vP - 128 : vP;   // this pixel's object key (category<<5 | id); 0 = empty

    float maxR2 = float(SEARCH * SEARCH);
    for (int dx = -SEARCH; dx <= SEARCH; dx++) {
        for (int dy = -SEARCH; dy <= SEARCH; dy++) {
            float d2 = float(dx * dx + dy * dy);
            if (d2 > maxR2) continue;                           // outside the largest possible reach
            vec4 n = texture(MaskSampler, texCoord + vec2(float(dx), float(dy)) * texel);
            int vN = int(n.a * 255.0 + 0.5);
            if (vN < 128) continue;                             // visible silhouettes are the only sources
            int keyN = vN - 128;
            // Compare the ID (low 5 bits) only, NOT the category: an entity's body (CAT_ENTITY) and its
            // armor (CAT_ARMOR) share one id, so this skips the seam between them — they ring as ONE shape
            // instead of doubling where two pieces overlap. Distinct objects keep distinct ids and still ring.
            if ((keyN & 31) == (keyP & 31)) continue;           // same identity → not a boundary
            // Reconstruct this source's linear eye distance from the scene depth at its texel, then scale the
            // per-category reach so a far (small-on-screen) object gets a proportionally thinner ring.
            vec2 srcUV = texCoord + vec2(float(dx), float(dy)) * texel;
            float srcDepthRaw = texture(DepthSampler, srcUV).r;
            float ndc = srcDepthRaw * 2.0 - 1.0;
            float srcDist = ProjMat[3][2] / (ndc + ProjMat[2][2]);
            // A VISIBLE silhouette source can only sit on the cleared far plane (depth ~1.0) if its geometry
            // never wrote to the main depth buffer — i.e. the first-person held item, which under an Iris pack
            // is drawn into Iris's gbuffer, not the main target, so the composite samples the cleared far value
            // here. Without this guard srcDist → far → scale → MIN_SCALE and EVERY hand item rings as a thin
            // hairline (TRIED: routing held items through the hand-projection drain fixed the float but left
            // them thin — this is why). Held items are always close; keep them at full THICKNESS.
            float scale = (srcDepthRaw >= 0.999999)
                    ? 1.0
                    : clamp(REF_DIST / max(srcDist, 0.001), MIN_SCALE, 1.0);
            float r = THICKNESS[keyN >> 5] * scale;             // this source's distance-scaled reach
            if (d2 <= r * r) {                                  // within THAT category's thickness → ring it
                // Occlude the ring where this RING pixel has nearer scene geometry than the source edge the
                // ring comes from, so a farther object's halo never paints over a nearer object (adjacent
                // players, or a held item over the gripping hand). See RING_OCCLUSION_BIAS.
                float rNdc = texture(DepthSampler, texCoord).r * 2.0 - 1.0;
                float ringDist = ProjMat[3][2] / (rNdc + ProjMat[2][2]);
                if (ringDist < srcDist - RING_OCCLUSION_BIAS) continue;  // nearer geometry here → no ring
                fragColor = vec4(n.rgb, 1.0);
                return;
            }
        }
    }
    discard;
}
