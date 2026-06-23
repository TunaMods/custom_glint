#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC overlay vertex shader. Same payload as core/chromatic.vsh (the
// per-trim seed / morph speed / colour count packed into spare TextureMat slots), but it ALSO forwards
// the linear view-space distance and the clip-space position so the fragment can sample the scene depth
// and keep only the visible front surface. Used by the post-Iris drain (EntityGlintRender.drainChromatic
// Overlays): under an active shader pack the chromatic glint can't draw in-phase (Iris replaces our
// procedural program with a pack program → flat white), so we re-render the model AFTER the framegraph
// onto an isolated target and decide occlusion in-shader instead of via the GPU depth test.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 noiseCoord;
out vec2 texCoord0;
out vec4 vertexColor;
out float vSeed;
out float vMorph;
out float vCount;
out float viewDist;
out vec4 screenPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    texCoord0 = UV0;   // raw model UV for the cutout alpha-test (Sampler0 = the model texture)
    vertexColor = Color;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
    viewDist = -viewPos.z;
    screenPos = gl_Position;
}
