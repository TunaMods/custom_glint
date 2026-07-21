#version 150

#moj_import <fog.glsl>

// Custom Glints CHROMATIC CUTOUT vertex shader (1.21.1). core/chromatic.vsh for horse armor: the procedural
// oil-slick, but the model is the whole horse and the armor texture is alpha-cutout, so maskCoord keeps the
// raw model UV for the fsh to alpha-test the armor shape (Sampler2). Also carries the coplanar depth bias
// from glint_cutout.vsh: the armor mesh is coplanar with the horse body on the torso and neck, and under
// Sodium the glint must sit proud in gl_Position (not a layering shard) to win the tie. NEW_ENTITY so the
// draw rides Sodium's EntityRenderer.renderCuboid alongside the armor; the fsh reads Position + UV0.
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
out vec2 maskCoord;
out float vSeed;
out float vMorph;
out float vCount;

void main() {
    // Camera-ward coplanar bias in VIEW space (see glint_cutout.vsh for the full Sodium rationale).
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    viewPos.z += 0.01;
    gl_Position = ProjMat * viewPos;

    vertexDistance = fog_distance(Position, FogShape);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    maskCoord = UV0;                 // raw model UV so the fsh samples the armor texture at the model's own coord
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
}
