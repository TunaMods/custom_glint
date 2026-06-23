#version 330

// Custom Glints: composites the isolated chromatic-overlay target onto the main target. Passthrough —
// the overlay was rendered (oil-slick, occlusion-discarded to the visible surface) into its own target
// cleared to 0; this draws it back over the finished frame with the pipeline's GLINT blend so it reads
// like the in-world chromatic glint does off the shader path. See EntityGlintRender.drainChromaticOverlays.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, texCoord);
}
