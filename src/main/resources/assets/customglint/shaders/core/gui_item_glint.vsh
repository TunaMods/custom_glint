#version 330

// Custom Glints GUI item-glint vertex shader. Draws the animated glint LIVE over a glinted item's
// cached atlas icon, so the base icon can stay cached (not re-baked every frame) while the glint still
// scrolls. See GuiRendererMixin / GuiItemGlintRenderState. Pairs with core/gui_item_glint.fsh.
//
// Per-layer animation params ride the vertex attributes (the batched GUI path has no per-draw uniform
// hook): UV1 = the two scroll scalars f,f1 (wall-clock, computed CPU-side so the GUI scroll matches the
// in-hand glint exactly), UV2 = packed (patternScale, guiScale). UV0 = the atlas slot coords (used both
// to sample the silhouette mask and to reconstruct item-local coords). Color = the animated layer colour.

// DynamicTransforms + Projection inlined (mirrors vanilla core/position_tex_color.vsh; startup core
// shaders can't moj_import).
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
out vec2 vScroll;
out float vPS;
out float vAtlasMode;   // 1 = Sampler1 is the shared design atlas (sample cell vCellIndex); 0 = single design
out float vCellIndex;   // atlas cell index for this layer's design (only meaningful when vAtlasMode == 1)

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    // UV1 = the 2D scroll vector (design-UV, wrapped to 0..1, direction + speed already resolved CPU-side
    // in GuiRendererMixin so the GUI drift matches the in-hand glint exactly).
    vScroll   = vec2(UV1) / 16000.0;
    vPS       = float(UV2.x) / 4096.0;     // patternScale
    // UV2.y bit layout: bits 0-6 guiScale, bit 7 atlas-mode flag, bits 8-15 atlas cell index. Sharing the
    // design atlas (atlas-mode) lets every glinted icon's glint glyph carry one TextureSetup and batch.
    int p2    = UV2.y;
    vGuiScale = float(p2 & 127);           // guiScale, for the exact slot UV size
    vAtlasMode = float((p2 >> 7) & 1);
    vCellIndex = float((p2 >> 8) & 255);
}
