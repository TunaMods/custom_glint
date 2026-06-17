#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints outline vertex shader. Identical passthrough to vanilla core/rendertype_outline.vsh
// (POSITION_TEX_COLOR): forwards the per-vertex Color (the chosen glow colour, baked in by the
// outline draw) and UV. The colour does NOT route through ColorModulator — see outline_color.fsh.

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
}
