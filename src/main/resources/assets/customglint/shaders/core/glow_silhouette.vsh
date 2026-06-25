#version 150

// Custom Glints glow-silhouette vertex shader (1.21.1 port of the 26.1 core/glow_silhouette.vsh).
// NEW_ENTITY format passthrough. Forwards (a) the camera-space distance so the fragment can run the
// per-fragment occlusion test, and (b) the clip-space position so the fragment can reconstruct its
// screen UV and sample the scene depth. viewDist is -(view-space Z): linear blocks-from-camera.
// ModelViewMat here is the camera ROTATION only (the camera translation is baked into Position at
// capture time, cam-relative), so ModelViewMat * Position lands in view space and -z is eye distance.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 texCoord0;
out float viewDist;
out vec4 screenPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    vertexColor = Color;
    texCoord0 = UV0;
    viewDist = -viewPos.z;
    screenPos = gl_Position;
}
