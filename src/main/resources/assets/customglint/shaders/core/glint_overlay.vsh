#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints NORMAL-glint overlay vertex shader, the post-Iris counterpart of core/glint_color.vsh.
// Under an active shader pack a normal glint layer can't draw in-phase: Iris substitutes our program for
// one of its own, and every gbuffer entity program is opaque, so the grayscale design paints SOLID over
// the item (you can't see through it). So under a pack the layer is queued (EntityGlintRender.queueGlint
// OverlayXxx) and re-rendered AFTER Iris finishes the frame onto an isolated target, then composited back
// with the GLINT blend. Like core/chromatic_overlay.vsh it forwards the view distance + clip position so
// the fragment can sample the committed scene depth for a per-fragment occlusion test.
//
// TWO texture coords: the scrolled/scaled design coord (Sampler0 = grayscale pattern, via TextureMat, same
// animation matrix the in-phase RT uses) and the raw model/atlas UV (Sampler1 = the cutout silhouette the
// in-phase glint got for free from its EQUAL depth test against the already-drawn surface).

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 designCoord;
out vec2 cutoutCoord;
out vec4 vertexColor;
out float viewDist;
out vec4 screenPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    designCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy; // Sampler0 = grayscale design (scrolled + scaled)
    cutoutCoord = UV0;                                   // Sampler1 = model/atlas texture (alpha cutout)
    vertexColor = Color;
    viewDist = -viewPos.z;
    screenPos = gl_Position;
}
