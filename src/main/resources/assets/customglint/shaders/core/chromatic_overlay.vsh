#version 150

// Custom Glints PROCEDURAL CHROMATIC overlay vertex shader (1.21.1). Post-Iris counterpart of
// core/chromatic.vsh: under an active shader pack the in-phase chromatic program is replaced/hijacked by
// the pack (the slick goes flat white / never appears), so the model is re-rendered AFTER the pack finishes
// the frame, onto an isolated target, and occlusion is decided in-shader against the committed scene depth.
// NEW_ENTITY passthrough (the captured armor/entity vertices replay as [Position, UV0]); forwards the same
// TextureMat payload as core/chromatic.vsh plus the linear view distance + clip position for the fragment's
// scene-depth occlusion (mirrors core/glow_silhouette.vsh).

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;

out vec2 noiseCoord;
out vec2 texCoord0;
out float vSeed;
out float vMorph;
out float vCount;
out float viewDist;
out vec4 screenPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    texCoord0  = UV0;                 // raw model UV for the cutout alpha-test (Sampler0 = model texture)
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
    viewDist = -viewPos.z;            // linear blocks-from-camera (ModelViewMat here is camera rotation only)
    screenPos = gl_Position;
}
