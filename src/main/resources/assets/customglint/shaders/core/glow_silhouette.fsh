#version 330

#moj_import <minecraft:projection.glsl>

// Custom Glints glow-silhouette fragment shader — the SINGLE combined mask (replaces the old separate
// FULL-shape + VISIBLE passes, which cost a second model render + a second emit per mob). One render of
// the model now produces both pieces of information the composite needs, encoded in alpha:
//
//   alpha == 0            : empty (not part of any entity) — discarded shape texels.
//   1..127  (v/255)       : IN SHAPE but OCCLUDED (behind world geometry), object id = v. Marks the shape
//                           (so the composite doesn't ring its interior) but is NOT a ring source.
//   128..255 (v/255)      : VISIBLE (unoccluded), object id = v - 128. The composite rings outward from
//                           these, keeping each object's ring separate by id.
//
// The per-object id rides the vertex-colour ALPHA (forced 1..127 by FullColorOverrideConsumer), so every
// glowing entity / armor piece / item gets its own outline instead of fusing into one union ring.
//
// rgb is always the glow colour. Occlusion is decided here per-fragment by sampling the full-res scene
// depth (DepthSampler = the main target's depth texture, read as a normal sampler2D → depth in .r) and
// comparing it to this fragment's own window depth. This removes the separate depth-downsample pass and
// the second silhouette pass entirely. Sampler0 alpha drives the silhouette shape (transparent texels
// discard) exactly as before.

uniform sampler2D Sampler0;
uniform sampler2D DepthSampler;

in vec4 vertexColor;
in vec2 texCoord0;
in float viewDist;
in vec4 screenPos;

out vec4 fragColor;

// Distance thickness. Full width only within NEAR_FULL blocks, then thins with distance, FLOORED so it
// never drops below a ~1px hairline. Must match the composite's MAX_THICKNESS budget (see glow_outline.fsh).
// FLOOR * MAX_THICKNESS must stay >= ~1 texel: full-res MAX_THICKNESS=3.5, FLOOR=0.30 → ~1.05px.
const float NEAR_FULL = 4.0;
const float FLOOR = 0.05;

// Occlusion bias, in BLOCKS of linear view-space distance. The test compares the fragment's eye distance
// (viewDist) against the scene's eye distance reconstructed from the sampled depth — NOT raw window depth.
// Window depth is nonlinear (1/z), so a fixed window-depth epsilon corresponds to a world gap that grows
// as distance² and lets the ring leak through solid cutout leaf texels once the entity is far enough away
// (the leaf surface and the entity collapse into the same window-depth bucket). A bias measured in blocks
// is distance-independent: it occludes a leaf 0.1+ blocks in front identically near and far, while the
// entity's own surface (≈0 blocks away from itself) always reads visible. Raise if the entity's own
// silhouette flickers occluded at grazing angles; lower if glow still leaks through thin foreground cutouts.
const float OCCLUSION_BIAS = 0.10;

void main() {
    if (texture(Sampler0, texCoord0).a == 0.0) {
        discard;
    }

    // Reconstruct screen UV from the interpolated clip position (resolution-independent — no viewport
    // uniform needed) and sample the full-res scene depth at this fragment.
    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(DepthSampler, uv).r;

    // Window depth → linear eye distance via the same projection the body was drawn with. ndc in [-1,1];
    // ProjMat[3][2] / (ndc + ProjMat[2][2]) yields the positive eye-space distance (near→near, far→far).
    float ndc = sceneDepth * 2.0 - 1.0;
    float sceneDist = ProjMat[3][2] / (ndc + ProjMat[2][2]);

    bool visible = viewDist <= sceneDist + OCCLUSION_BIAS;
    // Pack (visibility, object id) into alpha: occluded = id (1..127), visible = 128 + id. The id rides the
    // forced vertex-colour alpha; the composite (post/glow_outline_id) decodes it to keep rings separate.
    int id = clamp(int(vertexColor.a * 255.0 + 0.5), 1, 127);
    int v = visible ? (128 + id) : id;
    fragColor = vec4(vertexColor.rgb, float(v) / 255.0);
}
