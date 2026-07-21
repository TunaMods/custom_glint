#version 150

// Custom Glints TEXTURED-GLINT overlay vertex shader (1.21.1). Post-Iris counterpart of core/glint_cutout.vsh
// for horse armor. Under an active shader pack the in-phase glint_cutout program is hijacked by the pack (its
// custom samplers never bind, so the cutout alpha-test discards every fragment and the glint never appears),
// so the horse model is re-rendered AFTER the pack finishes the frame onto an isolated target, and occlusion
// is decided in-shader against the committed scene depth. Mirrors core/chromatic_overlay.vsh: NEW_ENTITY
// passthrough replaying the captured [Position, UV0], forwarding the scrolled design UV plus the linear view
// distance + clip position the fragment needs for the scene-depth occlusion test.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;

out vec2 designCoord; // scrolled design UV (Sampler1 = glint pattern)
out vec2 maskCoord;   // raw model UV (Sampler0 = armor texture, its alpha is the cutout)
out float viewDist;
out vec4 screenPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    designCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    maskCoord   = UV0;
    viewDist    = -viewPos.z; // linear blocks-from-camera (ModelViewMat here is camera rotation only)
    screenPos   = gl_Position;
}
