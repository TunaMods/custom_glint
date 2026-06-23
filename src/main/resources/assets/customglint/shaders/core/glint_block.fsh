#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// Custom Glints BLOCK glint fragment shader. Identical to glint_color.fsh except it first alpha-tests the
// block texture (Sampler1) at the raw block UV and discards transparent texels, so the glint is masked to
// the block model's cutout shape instead of tiling over the whole quad plane. 0.1 matches the block sheet's
// ALPHA_CUTOUT (entityCutoutCull). Sampler0 is the grayscale glint design (TextureMat-scrolled).

uniform sampler2D Sampler0; // grayscale glint design
uniform sampler2D Sampler1; // block atlas (cutout mask)

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec2 texCoordMask;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (texture(Sampler1, texCoordMask).a < 0.1) {
        discard; // transparent part of the block texture → no glint here
    }
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    fragColor = vec4(color.rgb * fade * vertexColor.a, color.a);
}
