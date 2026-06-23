#version 330

// Custom Glints GUI procedural-chromatic vertex shader. The GUI analog of core/chromatic.vsh: draws the
// live oil-slick over a glinted item's cached atlas icon (so the base icon stays cached). The batched GUI
// path has no per-draw uniform hook, so the per-layer payload rides the vertex attributes instead of the
// TextureMat the world path uses:
//   UV1.x = seed*256 (per-trim seed; /256 here for the 0..256 range core/chromatic uses)
//   UV1.y = colour count (0 => rainbow fallback; 1..8 => palette texels in Sampler1)
//   UV2.x = patternScale*4096   UV2.y = guiScale (low 7 bits) | speed*16 (high bits)
// UV0 = the atlas slot coords (silhouette mask + item-local reconstruction). Pairs with gui_chromatic.fsh.

// DynamicTransforms + Projection inlined (startup core shaders can't moj_import).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;
out float vGuiScale;
out float vSeed;
out float vCount;
out float vPS;
out float vSpeed;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    vSeed     = float(UV1.x) / 256.0;
    vCount    = float(UV1.y);
    vPS       = float(UV2.x) / 4096.0;
    vGuiScale = float(UV2.y & 127);
    vSpeed    = float(UV2.y >> 7) / 16.0;   // morph speed (matches the world chromatic's TextureMat[2][0])
}
