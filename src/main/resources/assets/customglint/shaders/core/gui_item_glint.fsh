#version 330

// Custom Glints GUI item-glint fragment shader. Emits the scrolling glint over a glinted item's cached
// icon, masked to the item silhouette (Sampler0 = the cached atlas slot, Sampler1 = the grayscale design).
// Order on top of the opaque icon is guaranteed by GuiRendererMixin adding this as a GLYPH (glyphs draw
// after the icon's sorted element in the same node). Drawn with BlendFunction.GLINT, exactly like the
// world/in-hand glint, so the look matches.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
// Globals carries GlintAlpha — the vanilla glint-strength dimmer. Inlined (startup core shaders can't
// moj_import); bound for GUI draws by RenderSystem.bindDefaultUniforms, same as the world glint.
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler0;   // cached item slot — silhouette mask
uniform sampler2D Sampler1;   // grayscale glint design (REPEAT + LINEAR)

in vec2 texCoord0;
in vec4 vertexColor;
in float vGuiScale;
in vec2 vScroll;   // 2D scroll vector (design-UV), direction+speed/static already resolved CPU-side
in float vPS;

out vec4 fragColor;

// Silhouette alpha threshold (same role as gui_item_outline's EDGE).
const float EDGE = 0.1;
// Apparent design size on the icon: BIGGER = bigger motifs (fewer repeats). It's the inverse of the
// tiling factor (k = patternScale / GLINT_SIZE), so the knob reads the intuitive way now. The 64x64
// source caps crispness at large sizes — pushing this much past ~3 magnifies the texels (soft/blocky).
const float GLINT_SIZE = 3.0;

// Sample coord for the design + scroll. The world's forGlint samples V twice as densely as U (scaleU =
// 8*atlasW/1024, scaleV = 8*atlasH/512 → effective spriteW/128 : spriteH/64 = 1:2 once the atlas factor
// cancels), which is what keeps diamond motifs as diamonds. That ratio is INTRINSIC, not atlas-derived —
// halving U here reproduces it on any atlas size. (The earlier atlasW/atlasH multiplier only matched a
// since-reverted symmetric /2048 world formula and read 1:1 on the square atlas → squares.)
// The scroll vector is supplied per-vertex (direction/speed, or a frozen static offset, resolved CPU-side).
vec2 glintUV(vec2 p, vec2 scroll, float ps) {
    float k = ps / GLINT_SIZE;
    return vec2(p.x * k * 0.5, p.y * k) + scroll;
}

void main() {
    // Item-local (0..1 over the 16px icon, v measured downward) from the atlas slot coord. s is the slot
    // UV size, derived EXACTLY from guiScale (the slot is always 16*guiScale atlas texels) — same scheme
    // as gui_item_outline, no fwidth/drift.
    float s = 16.0 * vGuiScale / float(textureSize(Sampler0, 0).x);
    float vv = 1.0 - texCoord0.y;
    vec2 origin = vec2(floor(texCoord0.x / s), floor(vv / s)) * s;
    vec2 itemLocal = vec2((texCoord0.x - origin.x) / s, (vv - origin.y) / s);

    // Only draw over the item itself — the cached slot's alpha is the silhouette.
    if (texture(Sampler0, texCoord0).a < EDGE) {
        discard;
    }

    vec2 duv = glintUV(itemLocal, vScroll, vPS);
    vec4 design = texture(Sampler1, duv) * vertexColor;
    if (design.a < 0.004) {
        discard;   // design texture's own alpha decides the glint SHAPE
    }
    // Match core/glint_color exactly: design renders at full brightness (alpha is shape only), scaled by
    // the colour's own alpha AND GlintAlpha (the world's `fade` with no fog in the GUI). Without GlintAlpha
    // the glint was far too bright — the blown-out highlights merged into a blob that also read as oversized.
    // GLINT blend (SRC_COLOR, ONE) then squares + adds onto the icon.
    fragColor = vec4(design.rgb * vertexColor.a * GlintAlpha, design.a);
}
