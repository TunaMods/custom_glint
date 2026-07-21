package net.tunamods.customglint.module.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
import net.tunamods.customglint.module.client.GlintWandClientHandler;

public class GlintWandItem extends Item {

    public GlintWandItem(Properties props) {
        super(props);
    }

    /** True if the player holds the wand in either hand. The wand is creative/command-only, so this is the
     *  server-side gate for the wand-driven shared-blueprint edits: a crafted packet from a wandless client
     *  is rejected rather than trusted. */
    public static boolean isHeldBy(Player player) {
        return player.getMainHandItem().is(ModItems.GLINT_WAND.get())
                || player.getOffhandItem().is(ModItems.GLINT_WAND.get());
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

        // Read the glint on both sides (component read is server-safe) so an empty wand returns FAIL on the
        // client too, otherwise the client always plays the swing animation before the server-only check
        // fails it.
        CustomGlint.GlintState glint = CustomGlint.readState(wand);
        if (glint.isEmpty()) {
            if (player instanceof ServerPlayer sp)
                sp.sendSystemMessage(Component.literal("Wand has no glint to apply"), true);
            return InteractionResult.FAIL;
        }

        if (target.level().isClientSide()) return InteractionResult.SUCCESS;

        CustomGlint.writeEntityState(target, glint);
        return InteractionResult.SUCCESS;
    }
}
