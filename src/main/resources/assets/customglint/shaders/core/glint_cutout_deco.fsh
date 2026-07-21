#version 150

#moj_import <fog.glsl>

// Custom Glints DECORATION CUTOUT GLINT fragment shader (1.21.1). Vanilla rendertype_glint.fsh plus a
// two-texture cutout up front. The Epic Knights (magistuarmory) counterpart of glint_cutout.fsh, which
// cuts against ONE texture (horse armor); this cuts against the UNION of two.
//
// EK dyeable decorations split their shape across two files: a dye-tinted base and an un-tinted overlay.
// Most decorations keep their shape in the base (plumes, surcoats); crowns invert it (band in the overlay,
// only the gems in the base). Cutting against a single texture would clip the glint to just one half, so
// the glint is kept where EITHER texture is opaque (max of the two alphas). When a decoration has no
// sibling overlay the caller binds the base to Sampler2 as well, so the union reduces to a single-texture
// cutout. The 0.1 cutoff matches rendertype_entity_cutout_no_cull, which draws the decoration, so the glint
// discards on exactly the texels the decoration discarded on: it lands on the decoration and nowhere else.

uniform sampler2D Sampler0; // glint design (greyscale), scrolled through TextureMat
uniform sampler2D Sampler1; // decoration base texture; its alpha is the base shape
uniform sampler2D Sampler2; // decoration overlay texture; its alpha is the overlay shape (== Sampler1 when none)

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float GlintAlpha;

in float vertexDistance;
in vec2 texCoord0;
in vec2 maskCoord;

out vec4 fragColor;

void main() {
    float maskAlpha = max(texture(Sampler1, maskCoord).a, texture(Sampler2, maskCoord).a);
    if (maskAlpha < 0.1) {
        discard;
    }

    vec4 color = texture(Sampler0, texCoord0) * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    float fade = linear_fog_fade(vertexDistance, FogStart, FogEnd) * GlintAlpha;
    fragColor = vec4(color.rgb * fade, color.a);
}
