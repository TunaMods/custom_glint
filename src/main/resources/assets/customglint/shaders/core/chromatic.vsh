#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC vertex shader. Same skeleton as core/glint_color.vsh, but the
// chromatic design has no texture: the fragment shader synthesises an oil-slick from value-noise.
// The per-layer payload that the immutable RenderPipeline can't carry as a uniform rides spare slots
// of the TextureMat (fed by CustomGlintRenderer's chromatic animation supplier):
//   TextureMat[2][3] = per-trim seed   (decorrelates each trim's pattern — "no two look alike")
//   TextureMat[2][0] = morph speed     (scales the GameTime-driven flow)
//   TextureMat[2][1] = colour count    (0 => rainbow fallback; 1..8 => palette texels)
// The 2D part of TextureMat still scales/positions the noise UV exactly like the normal glint.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 noiseCoord;
out vec4 vertexColor;
out float vSeed;
out float vMorph;
out float vCount;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    vertexColor = Color;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
}
