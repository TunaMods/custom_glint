#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints BLOCK glint vertex shader. Like glint_color.vsh, but also passes the raw block-atlas UV
// (texCoordMask) untouched so the fragment shader can alpha-test the block texture and mask the glint to
// the block model's cutout silhouette (mooshroom mushrooms, snow-golem pumpkin). Those layers are drawn by
// a different pipeline, so the glint can't lean on EQUAL-depth cutout masking the way item/entity glint does.
// It samples the block texture's alpha itself instead. texCoord0 is still the TextureMat-scrolled glint UV.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec2 texCoordMask;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    texCoordMask = UV0;
    vertexColor = Color;
}
