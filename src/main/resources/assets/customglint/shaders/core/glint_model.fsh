#version 150

#moj_import <fog.glsl>

// Custom Glints MODEL GLINT fragment shader (1.21.1). Vanilla rendertype_glint.fsh, line-for-line. No armor-shape
// alpha-test: the model IS its own shape, and the EQUAL depth test against the base's cutout depth already clips
// the glint to exactly the base's opaque texels (transparent texels wrote no depth, so the glint fails EQUAL
// there). See glint_model.vsh for why this variant carries no camera-ward bias.

uniform sampler2D Sampler0; // glint design (greyscale), scrolled through TextureMat

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float GlintAlpha;

in float vertexDistance;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    float fade = linear_fog_fade(vertexDistance, FogStart, FogEnd) * GlintAlpha;
    fragColor = vec4(color.rgb * fade, color.a);
}
