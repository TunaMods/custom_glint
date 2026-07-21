#version 150

// Custom Glints GUI ITEM-GLINT fragment shader (1.21.1). Samples this icon's design from a shared atlas cell
// (Sampler0), tiled via fract() inside the cell's content rect, and paints it with the animated
// layer colour. Matches vanilla rendertype_glint's output (design * colour * GlintAlpha, alpha = design
// alpha) so the atlased GUI glint looks identical to the per-design forGlint path it replaces. The vsh header
// documents the vertex payload.
//
// Atlas layout MUST match CustomGlintRenderer.GUI_ATLAS_* : a square grid of CONTENT-px cells, each with a
// GUTTER-px wrapped border. The grid dimension is sized CPU-side to the design count, so it is recovered here
// from the (square) atlas size, dim = grid * STRIDE, and no per-draw uniform is needed.

uniform sampler2D Sampler0;   // shared GUI design atlas (greyscale designs; CLAMP + NEAREST)
uniform float GlintAlpha;

in vec2 designCoord;
in vec4 vColor;
in float vCell;

out vec4 fragColor;

const float ATLAS_CONTENT = 64.0;
const float ATLAS_GUTTER  = 4.0;
const float ATLAS_STRIDE  = ATLAS_CONTENT + 2.0 * ATLAS_GUTTER; // 72

// Fold a (tiled) design coord onto this icon's atlas cell content rect. fract() folds duv into one tile; the
// content sub-rect sits GUTTER px inside the cell, so the fold never crosses into a neighbour or the border.
vec2 atlasCell(vec2 duv, float cell) {
    float dim  = float(textureSize(Sampler0, 0).x);   // square atlas: dim = grid * STRIDE
    int   grid = int(dim / ATLAS_STRIDE + 0.5);        // cells per side, recovered exactly
    int   ci   = int(cell + 0.5);
    float col  = float(ci - (ci / grid) * grid);       // ci % grid
    float row  = float(ci / grid);                     // ci / grid
    vec2 origin = (vec2(col, row) * ATLAS_STRIDE + ATLAS_GUTTER) / dim;
    return origin + fract(duv) * (ATLAS_CONTENT / dim);
}

void main() {
    vec4 color = texture(Sampler0, atlasCell(designCoord, vCell)) * vColor;
    if (color.a < 0.1) {
        discard;   // the design's own alpha is the glint SHAPE (matches rendertype_glint's 0.1 cutoff)
    }
    // No fog in the GUI, so vanilla's linear_fog_fade collapses to 1: fade is just GlintAlpha.
    fragColor = vec4(color.rgb * GlintAlpha, color.a);
}
