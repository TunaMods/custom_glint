#version 150

// Custom Glints glow-silhouette fragment shader. Writes the item's shape into the offscreen mask:
//   rgb   = glow colour
//   alpha = (128 + key), where key = (category<<5 | id) in 1..127 — the +128 marks the texel VISIBLE
//           so the composite (glow_composite) treats it as a ring source.
// Sampler0 (the item atlas) drives the shape: transparent texels discard. Sampled at LOD 0 so the
// shape never depends on the atlas filter / mipmap state (Sodium replaces sprite mip generation).
//
// NOTE: scene-depth occlusion is intentionally NOT done here yet. Reading the scene depth while the
// composite writes the main colour buffer is a feedback loop, and the depth math was the source of the
// crackle/speckle. The base ring is dialed in first; occlusion is a later, separate step.

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    if (textureLod(Sampler0, texCoord0, 0.0).a < 0.05) {
        discard;
    }
    int id = clamp(int(vertexColor.a * 255.0 + 0.5), 1, 127);
    fragColor = vec4(vertexColor.rgb, float(128 + id) / 255.0);
}
