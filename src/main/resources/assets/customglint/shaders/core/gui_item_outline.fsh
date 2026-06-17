#version 330

// Custom Glints GUI item-outline fragment shader. Pairs with vanilla core/position_tex_color.vsh.
//
// Draws the inventory-icon glow halo as a THICKNESS-px ring JUST OUTSIDE the item's silhouette, in the flat
// per-vertex Color (the glow colour fed by BlitRenderState). The slot texture (the rendered icon) is used
// only as an alpha mask: a fragment rings iff it is OUTSIDE the item (alpha here ~0) but an adjacent texel
// is INSIDE (alpha > 0). Because the interior is discarded, this can draw ON TOP of the item without
// covering it — which matters since the 26.1 GUI sorts blits by pipeline, not submission order, so a
// "behind" copy can't be guaranteed. fwidth() gives UV-per-screen-pixel, so the ring stays ~constant
// screen width at any GUI scale.

// DynamicTransforms (inlined — startup core shaders can't moj_import; mirrors position_tex_color.fsh).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// Ring width in screen pixels. Taps are CLAMPED to this item's atlas slot (see the atlasSize decode), so a
// thicker ring no longer bleeds into the neighbouring icon — it just clips where the item reaches the slot
// edge. Freely tunable now that the bleed is gone.
const float THICKNESS = 2.0;
const float EDGE = 0.05;       // alpha threshold separating item from empty

// Atlas slot index of a UV. GuiItemAtlas tiles slots at slotUvSize = 1/atlasSize: u grows right, v grows
// DOWN encoded as 1 - k*slotUvSize (see GuiItemAtlas.getSlotView). A tap landing in a DIFFERENT slot than
// this fragment is in the neighbouring icon and must not count, or the ring bleeds across the slot seam.
vec2 slotIndex(vec2 uv, float s) {
    return vec2(floor(uv.x / s), floor((1.0 - uv.y) / s));
}

// Icon alpha at a tap, but 0 if the tap fell outside this fragment's slot (so it can never read a neighbour).
float inSlotAlpha(vec2 uv, vec2 mySlot, float s) {
    return slotIndex(uv, s) == mySlot ? texture(Sampler0, uv).a : 0.0;
}

void main() {
    if (texture(Sampler0, texCoord0).a > EDGE) {
        discard;               // inside the item — leave the icon untouched
    }
    // slotTextureSize (= 16*guiScale) rides the colour's alpha byte (packed by GuiRendererMixin). The icon
    // displays 1:1 (1 atlas texel = 1 screen pixel), so fwidth(texCoord0) = 1/atlasTextureSize EXACTLY, and
    // slotUvSize = slotTextureSize * fwidth — pixel-exact at ANY guiScale, including 3 where the 48px slot
    // doesn't divide the power-of-two atlas (1/slotUvSize is non-integer, which broke the old packing).
    float s = floor(vertexColor.a * 255.0 + 0.5) * fwidth(texCoord0.x);
    vec2 mySlot = slotIndex(texCoord0, s);

    // 8 SMOOTH taps at a fractional THICKNESS-px offset (fwidth = UV per screen pixel → constant width at
    // any GUI scale), each ignored if it crosses out of this slot. Smooth offsets trace the silhouette
    // cleanly; an integer-grid kernel snaps to texel centres and leaves gaps along anti-aliased edges.
    vec2 d = vec2(fwidth(texCoord0.x), fwidth(texCoord0.y)) * THICKNESS;
    float neighbour =
          inSlotAlpha(texCoord0 + vec2( d.x, 0.0), mySlot, s)
        + inSlotAlpha(texCoord0 + vec2(-d.x, 0.0), mySlot, s)
        + inSlotAlpha(texCoord0 + vec2(0.0,  d.y), mySlot, s)
        + inSlotAlpha(texCoord0 + vec2(0.0, -d.y), mySlot, s)
        + inSlotAlpha(texCoord0 + vec2( d.x,  d.y), mySlot, s)
        + inSlotAlpha(texCoord0 + vec2(-d.x, -d.y), mySlot, s)
        + inSlotAlpha(texCoord0 + vec2( d.x, -d.y), mySlot, s)
        + inSlotAlpha(texCoord0 + vec2(-d.x,  d.y), mySlot, s);
    if (neighbour < EDGE) {
        discard;               // no item within reach inside this slot — not an edge pixel
    }
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, ColorModulator.a);
}
