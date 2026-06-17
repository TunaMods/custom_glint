#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints colored glint vertex shader. Identical to vanilla core/glint.vsh except it carries
// a per-vertex Color so simultaneous multi-color glint layers can be batched into one draw (the
// 26.1 RenderPipeline model has no per-RenderType ColorModulator hook; color is baked into the
// vertices instead). Animation still rides the TextureMat uniform fed by a per-layer
// TextureTransform supplier (see CustomGlintRenderer.forGlint). See 26/09-renderer-api-verified.md.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    vertexColor = Color;
}
