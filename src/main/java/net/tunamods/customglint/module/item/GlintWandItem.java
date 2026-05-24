package net.tunamods.customglint.module.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.entity.EntityGlintEvents;

public class GlintWandItem extends Item {

    public GlintWandItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    net.tunamods.customglint.module.client.GlintWandClientHandler.openEditor(hand));
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack wand, Player player, LivingEntity target, InteractionHand hand) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (target.level().isClientSide()) return InteractionResult.SUCCESS;

        CompoundTag glintTag = CustomGlint.itemGlintTag(wand);
        if (glintTag.isEmpty()) {
            player.displayClientMessage(Component.literal("Wand has no glint to apply"), true);
            return InteractionResult.FAIL;
        }

        CustomGlint.writeEntityTag(target, glintTag);
        EntityGlintEvents.broadcast(target);
        return InteractionResult.SUCCESS;
    }
}
