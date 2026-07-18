#version 150

#moj_import <fog.glsl>

// Custom Glints CUTOUT GLINT vertex shader (1.21.1). Vanilla rendertype_glint.vsh plus one extra varying and a
// camera-ward depth bias in gl_Position (see main for why the bias is here and not in a layering shard).
//
// This exists for armor whose MODEL is not its SHAPE: HorseArmorLayer renders the whole horse mesh with the
// armor texture, and only that texture's alpha says which texels are actually armor. rendertype_glint samples
// the design, not the armor, so it has no alpha to cut itself with and paints bare hide. That cutout used to
// be done with a stencil mask pass; it is done here instead, per-fragment, in the same draw.
//
// UV0 feeds both samplers. The design is scrolled through TextureMat like any glint; maskCoord keeps UV0 raw
// so the fsh can sample the armor texture at the model's own coordinate.
//
// TRIED (2026-07-18, Sodium coplanar z-fight, shaders OFF): this is a NEW_ENTITY shader, NOT POSITION_TEX.
// The glint must sit exactly on the base horse armor, which HorseArmorLayer draws through
// rendertype_entity_cutout_no_cull (NEW_ENTITY). Sodium reimplements entity geometry
// (EntityRenderer.renderCuboid) and only takes that fast path when the target buffer's format matches
// EntityVertex.FORMAT == NEW_ENTITY (BufferBuilderExtension.canUseIntrinsics is a reference-equality check
// on the format). While this glint was POSITION_TEX the base armor went through Sodium's renderCuboid but the
// glint fell back to the vanilla per-vertex path (VertexConsumerUtils.convertOrLog returns null on the format
// mismatch → no cancel → vanilla compile), so the two coplanar meshes were built by DIFFERENT vertex pipelines
// under Sodium and the +0.01 bias could not deterministically arbitrate the tie (clean in vanilla, where both
// use the same pipeline, hence "only flickers with Sodium"). Declaring NEW_ENTITY routes the glint through the
// SAME renderCuboid path as the base armor, so the coplanar corners are bit-identical again and the bias below
// is a clean, path-independent separation. The extra NEW_ENTITY attributes (Color/UV1/UV2/Normal) are declared
// only to bind the format; the shader reads Position and UV0, exactly as the old POSITION_TEX build did.
// Do NOT revert to POSITION_TEX to "match rendertype_glint": that reintroduces the split-pipeline z-fight.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;
out vec2 maskCoord;

void main() {
    // Camera-ward depth bias in VIEW space. The horse armor mesh is coplanar with the horse body on the torso
    // and neck, so without a proud bias the glint z-fights there (the speckle). The bias lives HERE, in the
    // vertex program, not in glPolygonOffset or a layering shard: Sodium reimplements entity rendering (its own
    // ModelCuboid vertex path) and drops flush-time GL / modelview layering state, so a bias put there renders
    // clean in vanilla but vanishes under Sodium. gl_Position is the value the rasterizer depth-tests; nothing
    // can drop it. Eye space looks down -Z, so += moves toward the camera. 0.01 (~1cm) is far above any coplanar
    // divergence and far below the ~0.5-block gap to an occluded part, so a far leg still fails the LEQUAL test.
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    viewPos.z += 0.01;
    gl_Position = ProjMat * viewPos;

    vertexDistance = fog_distance(Position, FogShape);
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    maskCoord = UV0;
}
