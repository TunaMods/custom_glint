#version 150

#moj_import <fog.glsl>

// Custom Glints CHROMATIC MODEL vertex shader (1.21.1). core/chromatic_cutout.vsh declared for NEW_ENTITY, with
// NO camera-ward bias and no mask coord. For a model that IS its own shape (an elytra): the slick tests EQUAL
// against the base's cutout depth, which self-occludes overlapping geometry (a closed elytra layers its two
// wings at the center) so only the nearest wing draws and the one behind fails, instead of a proud bias that
// would let both pass and additively double the seam. NEW_ENTITY so it rides Sodium's renderCuboid alongside the
// base and quantizes identically, which is what lets the EQUAL test match under Sodium.
//
// Per-layer payload rides TextureMat spare slots exactly like core/chromatic.vsh:
//   TextureMat[2][3] = per-trim seed · TextureMat[2][0] = morph speed · TextureMat[2][1] = colour count.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;
uniform int FogShape;

out float vertexDistance;
out vec2 noiseCoord;
out float vSeed;
out float vMorph;
out float vCount;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position, FogShape);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
}
