#version 150

// Custom Glints glow-silhouette fragment shader. Writes the item's shape into the offscreen mask:
//   rgb   = glow colour
//   alpha = (128 + key), where key = (category<<5 | id) in 1..127 — the +128 marks the texel VISIBLE
//           so the composite (glow_composite) treats it as a ring source.
// Sampler0 (the item atlas) drives the shape: transparent texels discard. Sampled at LOD 0 so the
// shape never depends on the atlas filter / mipmap state (Sodium replaces sprite mip generation).
//
// Scene-depth occlusion: Sampler1 is the MAIN target's depth texture, bound by the drain. This is safe
// here (unlike the old composite attempt) because the silhouette writes the offscreen MASK, not the main
// colour buffer — no read/write feedback loop. A fragment behind the scene depth is marked occluded (key
// 1..127, not a ring source) so the ring only forms around the VISIBLE part of the item. The mask's own
// LEQUAL depth buffer means only the front-most fragment per pixel writes, so back faces can't override.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform mat4 ProjMat;
// Per-block growth of the occlusion bias (0 = off, the off-pack default). Under a shader pack the
// reconstructed scene distance is imprecise and its error grows with distance, so a constant bias can't keep
// grazing edges stable far away. Scaling the bias with viewDist keeps far grazing edges visible (no flicker)
// while a real wall — a depth gap far larger than the bias — still occludes.
uniform float OcclusionBiasScale;

in vec4 vertexColor;
in vec2 texCoord0;
in float viewDist;   // this fragment's linear eye distance (blocks), from the vsh (-viewPos.z)
in vec4 screenPos;

out vec4 fragColor;

// Occlusion bias in BLOCKS of linear eye distance. Compared against linear distance, NOT raw window depth:
// window depth is nonlinear (1/z), so a fixed window-depth epsilon maps to a world gap that grows with
// distance² and lets the ring leak through solid blocks once the entity is far enough (the wall in front and
// the entity behind it fall into the same window-depth bucket). A block-based bias occludes a wall 0.1+
// blocks in front identically near and far, while the entity's own surface (≈0 blocks from itself) always
// reads visible. Raise if a silhouette flickers occluded at grazing angles; lower if glow still leaks.
const float OCCLUSION_BIAS = 0.10;

void main() {
    if (textureLod(Sampler0, texCoord0, 0.0).a < 0.05) {
        discard;
    }
    int id = clamp(int(vertexColor.a * 255.0 + 0.5), 1, 127);

    // Reconstruct the screen UV and sample the scene depth. sceneDepth <= 0 means Sampler1 is unbound (the
    // GUI drain) or the near plane — treat as visible so occlusion can never erase the whole outline.
    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(Sampler1, uv).r;

    // Window depth → linear eye distance via the same projection the silhouette was drawn with (matches the
    // composite's cg_eyeDist): ProjMat[3][2] / (ndc + ProjMat[2][2]). Occluded when this fragment sits more
    // than OCCLUSION_BIAS blocks behind the scene geometry at its pixel.
    float ndc = sceneDepth * 2.0 - 1.0;
    float sceneDist = ProjMat[3][2] / (ndc + ProjMat[2][2]);
    float bias = OCCLUSION_BIAS + viewDist * OcclusionBiasScale;
    bool visible = sceneDepth <= 0.0 || viewDist <= sceneDist + bias;

    int a = visible ? (128 + id) : id;
    fragColor = vec4(vertexColor.rgb, float(a) / 255.0);
}
