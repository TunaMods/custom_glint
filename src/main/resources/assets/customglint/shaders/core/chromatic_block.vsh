#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC vertex shader for BLOCK-model entity layers. Identical to
// core/chromatic.vsh, but also passes the raw block-atlas UV (texCoordMask) so the fragment shader can
// alpha-test the block texture (Sampler2) and mask the oil-slick to the block's cutout shape instead of
// tiling over the whole quad plane. The per-layer payload still rides the TextureMat spare slots.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 noiseCoord;
out vec2 texCoordMask;
out vec4 vertexColor;
out float vSeed;
out float vMorph;
out float vCount;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    texCoordMask = UV0;
    vertexColor = Color;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
}
