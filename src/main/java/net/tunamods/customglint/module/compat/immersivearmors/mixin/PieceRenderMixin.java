package net.tunamods.customglint.module.compat.immersivearmors.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.tunamods.customglint.module.compat.immersivearmors.client.ImmersiveArmorsGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Standalone-only Immersive Armors compat, glint half. Every IA {@code Piece} draws its geometry through
 * {@code Piece.renderParts}, which resolves a RenderType off the piece's own {@code isTranslucent()} /
 * {@code isGlowing()} flags and then calls {@code EntityModel.renderToBuffer}. {@code LayerPiece} calls
 * {@code renderParts} several times (stacked texture layers), each its own draw at its own depth.
 *
 * <p>We redirect that {@code renderToBuffer} so the glint fans into the SAME submission as the armor (same
 * pose, same vertices) via a {@code VertexMultiConsumer}, so the EQUAL-depth glint matches the
 * armor's depth exactly and never z-fights, unlike a separate re-render at {@code render} RETURN. The stack
 * + wearer + offset choice were recorded by {@code ArmorPieceMixin} at {@code render} HEAD, which brackets
 * this call. {@code EntityModel} is handed on as its {@code Model} supertype (the glue only needs
 * {@code renderToBuffer}). {@code remap = false}: the target is IA-owned, vanilla-typed, stable.
 */
@Pseudo
@Mixin(targets = "immersive_armors.client.render.entity.piece.Piece", remap = false)
public class PieceRenderMixin {

    private static final String RENDER_PARTS =
            "renderParts(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "I"
            + "Lnet/minecraft/world/item/ItemStack;"
            + "Limmersive_armors/item/ExtendedArmorItem;"
            + "Lnet/minecraft/client/model/EntityModel;"
            + "IZ)V";

    @Redirect(
        method = RENDER_PARTS,
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
        require = 0, remap = false
    )
    private void cg_fanGlint(EntityModel<?> model, PoseStack pose, VertexConsumer base, int light, int overlay, int color) {
        ImmersiveArmorsGlint.fanGlint(model, pose, base, light, overlay, color);
    }
}
