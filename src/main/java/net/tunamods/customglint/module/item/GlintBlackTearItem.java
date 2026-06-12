package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.entity.EntityGlintEvents;

import javax.annotation.Nullable;
import java.util.List;

public class GlintBlackTearItem extends Item {

    public GlintBlackTearItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        CustomGlint.write(stack, CustomGlint.SOLID,
                new int[]{ CustomGlint.BLACK, CustomGlint.WHITE }, 1.0f, true, 1.0f, false);
        return stack;
    }

    /**
     * Right-click on a mob: strip the entity's own glint + glowing + glowColors (the per-instance
     * data set via {@link CustomGlint#writeEntity}/{@link CustomGlint#setEntityGlowColors}/
     * {@link CustomGlint#setEntityGlowing}). Does not touch the mob's equipment. Consumes one tear
     * per successful cleanup. Returns PASS when nothing was changed so the tear isn't wasted and
     * other interaction handlers can run. Server-side mutates and broadcasts; client returns
     * SUCCESS for the swing animation.
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
        if (player.level().isClientSide) {
            return CustomGlint.hasEntity(entity) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!CustomGlint.hasEntity(entity)) return InteractionResult.PASS;
        CustomGlint.removeEntity(entity);
        EntityGlintEvents.broadcast(entity);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        pTooltipComponents.add(Component.literal("Craft with any glinted item to").withStyle(ChatFormatting.GRAY));
        pTooltipComponents.add(Component.literal("strip all glint data from it").withStyle(ChatFormatting.GRAY));
        pTooltipComponents.add(Component.literal("Right-click a mob to clear its glint and glow").withStyle(ChatFormatting.GRAY));
    }
}
