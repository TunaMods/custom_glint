package net.tunamods.customglint.module.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.entity.EntityGlintEvents;
import net.tunamods.customglint.module.client.GlintWandClientHandler;

public class GlintWandItem extends Item {

    public GlintWandItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() && FMLEnvironment.getDist() == Dist.CLIENT) {
            GlintWandClientHandler.openEditor(hand);
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack wand, Player player, LivingEntity target, InteractionHand hand) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (target.level().isClientSide()) return InteractionResult.SUCCESS;

        CompoundTag glintTag = CustomGlint.itemGlintTag(wand);
        if (glintTag.isEmpty()) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp)
                sp.sendSystemMessage(Component.literal("Wand has no glint to apply"), true);
            return InteractionResult.FAIL;
        }

        CustomGlint.writeEntityTag(target, glintTag);
        EntityGlintEvents.broadcast(target);
        return InteractionResult.SUCCESS;
    }
}
