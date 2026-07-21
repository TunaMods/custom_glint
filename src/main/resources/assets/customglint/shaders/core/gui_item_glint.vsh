#version 150

// Custom Glints GUI ITEM-GLINT vertex shader (1.21.1). Draws the scrolling design glint over an inventory
// item's OWN quads while sampling a SHARED design atlas, so every glinted icon on a many-icon screen (the
// creative tab, the Glint Table palettes) batches into ONE draw regardless of how many distinct designs are
// visible. See CustomGlintRenderer#guiAtlasGlintRenderType / #guiAtlasGlintBuffer.
//
// A batched draw has no per-item uniform hook, so the per-icon design/colour/scroll/scale ride the vertex
// payload (written by GuiAtlasGlintConsumer, NOT the item's real overlay/light):
//   Color = animated layer colour, premultiplied RGB + alpha 1 (stands in for forGlint's ColorModulator)
//   UV1   = scroll offset, pre-fract'd to [0,1) and x16000 CPU-side (the fsh fract-folds, so the dropped
//           integer part is irrelevant; staying in [0,16000) keeps it positive inside the signed short)
//   UV2.x = patternScale x4096 ; UV2.y = this design's atlas cell index
// TextureMat carries the constant per-axis block-atlas scale on its diagonal (scaleU, scaleV), so the
// atlased glint tiles IDENTICALLY to setItemScrollMatrix / forGlint, the per-design path this replaces.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;

out vec2 designCoord;   // scrolled + scaled design coord, unbounded (the fsh fract-folds it into the cell)
out vec4 vColor;
out float vCell;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vColor = Color;
    vCell  = float(UV2.y);
    float ps = float(UV2.x) / 4096.0;
    vec2 scroll = vec2(UV1) / 16000.0;
    // Reproduce setItemScrollMatrix EXACTLY: scale about the texture centre (0.5) by scaleUV * patternScale,
    // then add the scroll. scaleUV = TextureMat's diagonal (constant across the whole batch).
    vec2 scaleUV = vec2(TextureMat[0][0], TextureMat[1][1]);
    designCoord = (UV0 - 0.5) * scaleUV * ps + 0.5 + scroll;
}
