#version 150

#moj_import <fog.glsl>

// Byte-for-byte vanilla 1.20.1 rendertype_glint.fsh. We ship our own copy of the program only so we can
// declare a different blend in glint.json - see the LOAD-BEARING note in CustomGlintRenderer. Keep this in
// sync with vanilla if it ever changes; the look is supposed to be identical.
//
// ColorModulator carries the layer's animated colour (the RenderType's texturing shard sets it), Sampler0
// the greyscale design texture, TextureMat the scroll/scale matrix.

uniform sampler2D Sampler0;

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
