#version 150

// Custom Glints chromatic composite fragment shader: blit the offscreen chromatic overlay target over the
// pack's final image. The target holds the pre-faded slick (rgb) with coverage in alpha; the drain sets an
// additive blend (SRC_ALPHA, ONE) so the slick adds as a foil only where the model drew.

uniform sampler2D Sampler0;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(Sampler0, texCoord);
}
