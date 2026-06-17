#version 330

// Custom Glints glow-silhouette — OCCLUSION-DISABLED variant (client config outlineOcclusion = false).
// Drops the DepthSampler tap + the linear-depth reconstruct that core/glow_silhouette does per fragment,
// so the mask shader is much cheaper on weak GPUs. Every shape fragment is marked "visible", so the
// outline shows through walls (the perf / see-through tradeoff the player opted into).

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;
in float viewDist;

out vec4 fragColor;

// Same distance thickness curve as the occluding shader so the ring matches the composite's budget.
const float NEAR_FULL = 4.0;
const float FLOOR = 0.05;

void main() {
    if (texture(Sampler0, texCoord0).a == 0.0) {
        discard;
    }
    // No occlusion test: always VISIBLE (alpha = 128 + id). The per-object id rides the forced vertex-colour
    // alpha; see core/glow_silhouette for the packed encoding the composite decodes.
    int id = clamp(int(vertexColor.a * 255.0 + 0.5), 1, 127);
    fragColor = vec4(vertexColor.rgb, float(128 + id) / 255.0);
}
