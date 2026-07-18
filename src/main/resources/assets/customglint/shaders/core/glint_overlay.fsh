#version 150

// Custom Glints TEXTURED-GLINT overlay fragment shader (1.21.1). Draws the scrolled design texture tinted by
// ColorModulator and cut to the armor shape (Sampler0 alpha), exactly like the in-phase core/glint_cutout.fsh,
// but AFTER the shader pack finished the frame, so occlusion rides a sampled scene depth instead of the GPU
// depth test (mirrors core/chromatic_overlay.fsh). The composite (chromatic_composite) screen-blends the
// result over the pack's image like a foil.

uniform sampler2D Sampler0; // armor texture: its alpha IS the armor shape (the cutout)
uniform sampler2D Sampler1; // glint design (greyscale), scrolled through TextureMat
uniform sampler2D Sampler2; // full-res scene depth (main target), bound by the drain

uniform mat4 ProjMat;
uniform vec4 ColorModulator;

in vec2 designCoord;
in vec2 maskCoord;
in float viewDist;
in vec4 screenPos;

out vec4 fragColor;

// Occlusion tolerance in blocks of linear view distance. Matches core/chromatic_overlay.fsh: large enough to
// swallow the armor's coplanar offset with the horse body + the reconstruction imprecision, tight enough that
// a leg in front still hides the body behind it.
const float OCCLUSION_BIAS = 0.02;

void main() {
    // Cutout: the model is the whole horse; only the armor texture's alpha says which texels are armor. Discard
    // the rest so the glint follows the real armor shape (the in-phase draw got this from its own cutout).
    if (texture(Sampler0, maskCoord).a < 0.1) {
        discard;
    }

    // Per-fragment occlusion against the committed scene depth: drop anything not on the visible front surface
    // (occluded geometry, or our own back faces since cull is off). sceneDepth<=0 => unbound / near plane =>
    // treat as visible so occlusion can never erase the whole glint.
    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(Sampler2, uv).r;
    float ndc = sceneDepth * 2.0 - 1.0;
    float sceneDist = ProjMat[3][2] / (ndc + ProjMat[2][2]);
    float bias = OCCLUSION_BIAS + viewDist * 0.006;
    if (sceneDepth > 0.0 && viewDist > sceneDist + bias) {
        discard;
    }

    // Greyscale design texel tinted by the (premultiplied) glint colour, alpha-tested on the design's own shape.
    vec4 g = texture(Sampler1, designCoord) * ColorModulator;
    if (g.a < 0.1) {
        discard;
    }
    // rgb = the tinted design, alpha = coverage (1 where drawn). The composite blends this over the pack image.
    fragColor = vec4(g.rgb, 1.0);
}
