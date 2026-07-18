#version 150

#moj_import <fog.glsl>

// Custom Glints CUTOUT GLINT fragment shader (1.21.1). Vanilla rendertype_glint.fsh, kept line-for-line,
// plus one alpha-test against the armor texture up front (the vsh header says why this shader exists).
//
// The 0.1 cutoff matches rendertype_entity_cutout_no_cull.fsh, which is what draws the base armor. Same
// texture, same coordinate, same threshold, so this discards on exactly the texels the armor discarded on:
// the glint lands on the armor and nowhere else. An exact per-fragment test, where the stencil mask it
// replaced could only carry one screen-space bit per pixel.

uniform sampler2D Sampler0; // glint design (greyscale), scrolled through TextureMat
uniform sampler2D Sampler1; // armor texture, sampled at the raw model UV; its alpha IS the armor's shape

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float GlintAlpha;

in float vertexDistance;
in vec2 texCoord0;
in vec2 maskCoord;

out vec4 fragColor;

void main() {
    if (texture(Sampler1, maskCoord).a < 0.1) {
        discard;
    }

    vec4 color = texture(Sampler0, texCoord0) * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    float fade = linear_fog_fade(vertexDistance, FogStart, FogEnd) * GlintAlpha;
    fragColor = vec4(color.rgb * fade, color.a);
}
