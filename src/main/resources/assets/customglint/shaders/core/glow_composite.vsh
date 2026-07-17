#version 150

// Custom Glints glow-outline composite vertex shader. Fullscreen quad: Position.xy arrives in [0,1]
// (built by GlowOutlineRenderer's Tesselator quad) and is remapped to NDC. UV0 passes straight through
// as the screen texCoord. Neither stage declares ProjMat/ModelViewMat. Positioning needs no transform,
// and the fsh linearises the scene depth from the ProjA/ProjB scalars instead.

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
    texCoord = UV0;
}
