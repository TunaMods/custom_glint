#version 150

// Custom Glints chromatic composite vertex shader: a plain fullscreen-quad passthrough. Position.xy arrives
// in [0,1] (the drain's Tesselator quad) and is remapped straight to NDC. ProjMat/ModelViewMat are ignored,
// exactly like core/glow_composite.vsh. UV0 passes through as the screen texCoord into the overlay target.

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
    texCoord = UV0;
}
