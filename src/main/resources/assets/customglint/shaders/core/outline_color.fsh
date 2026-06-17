#version 330

// Custom Glints outline fragment shader. The glow ring is drawn directly into the MAIN target (not
// vanilla's outline framebuffer), so unlike vanilla core/rendertype_outline.fsh it must NOT depend on
// the global ColorModulator uniform. Vanilla outputs `vec4(ColorModulator.rgb * vertexColor.rgb,
// ColorModulator.a)`; since this mod never sets ColorModulator for the outline draw, that left the
// ring tinted/dimmed by a stale per-draw value (looked translucent and varied by angle). Here the
// colour rides the per-vertex Color and is emitted opaque. Sampler0 is the silhouette texture; its
// alpha drives the discard so the ring follows the real shape (full silhouette when bound to white.png).

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    if (texel.a == 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, 1.0);
}
