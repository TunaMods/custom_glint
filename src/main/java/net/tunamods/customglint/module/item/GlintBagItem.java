package net.tunamods.customglint.module.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ComponentItemHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.menu.GlintBagMenu;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A portable container that holds only Custom Glint items (trims, glow trims, tears, rainbow dye). Right-click
 * opens it; storable items are auto-picked into it while it sits in the inventory (see the pickup hook in
 * {@link net.tunamods.customglint.CustomGlintMod}). Contents live in the stack's {@code minecraft:container}
 * component via an item-handler capability (registered in {@code CustomGlintMod}), so they travel with the bag.
 */
public class GlintBagItem extends Item {
    public static final int ROWS = 6;
    public static final int COLS = 9;
    public static final int SIZE = ROWS * COLS;

    /** Auto-collect only scans the inventory this often (ticks); the pickup hook handles instant collection. */
    private static final int SCAN_INTERVAL = 10;

    public GlintBagItem(Properties properties) {
        super(properties);
    }

    /** An {@link IItemHandler} backed by the bag stack's {@code minecraft:container} component, accepting only
     *  storable Glint items. Used by the menu, the pickup hook, and the item-handler capability registration. */
    public static IItemHandler createHandler(ItemStack stack) {
        return new BagItemHandler(stack);
    }

    /** Whether this bag auto-collects. Default on (a bag with no flag yet). */
    public static boolean isAutoCollect(ItemStack stack) {
        return stack.getOrDefault(ModComponents.BAG_AUTO_COLLECT.get(), Boolean.TRUE);
    }

    /** Toggle auto-collect. The golden glint + glow doubles as the on/off indicator, so it's applied when on
     *  and stripped when off. */
    public static void setAutoCollect(ItemStack stack, boolean on) {
        stack.set(ModComponents.BAG_AUTO_COLLECT.get(), on);
        if (on) {
            applyGoldenGlint(stack);
        } else {
            CustomGlint.remove(stack);
            CustomGlint.setGlowing(stack, false);
            CustomGlint.clearGlowColors(stack);
        }
    }

    /** The bag's signature look: the "Golden" glow trim, a slow golden solid layer under a faster shimmer,
     *  with the glowing outline on. Mirrors config/glint-and-glamour/trims/Golden.json. */
    public static void applyGoldenGlint(ItemStack stack) {
        CustomGlint.write(stack, new CustomGlint.Layer[]{
                new CustomGlint.Layer(CustomGlint.SOLID, new int[]{0xFFFFD940}, 0.25f, true, 1.0f, false),
                new CustomGlint.Layer(CustomGlint.SHIMMER, new int[]{0xFFFFE890}, 1.0f, false, 0.5f, false)
        });
        CustomGlint.setGlowing(stack, true);
    }

    /** Display instance (creative tab, JEI) carries the Golden glow trim. */
    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        applyGoldenGlint(stack);
        return stack;
    }

    /** The mod's own loot items (trims, tears, rainbow dye). Only these are auto-collected (pulled in on pickup
     *  or by the inventory sweep); generic materials are not, so the bag never eats your redstone or dyes. */
    public static boolean isAutoCollectable(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof GlintTrimItem
                || item instanceof GlowTrimItem
                || item instanceof GlintTearItem
                || item instanceof GlintLayerTearItem
                || item instanceof GlintBlackTearItem
                || item instanceof RainbowDyeItem;
    }

    /** Everything the bag may hold: the glint loot above plus every Glint Table material (dyes, redstone, slime,
     *  glass, glowstone, name tag), so shift-right-clicking the table can stock its slots straight from the bag.
     *  A bag never stores itself. */
    public static boolean canStore(ItemStack stack) {
        if (isAutoCollectable(stack)) return true;
        if (stack.getItem() instanceof DyeItem) return true;
        return stack.is(Items.REDSTONE) || stack.is(Items.SLIME_BALL) || stack.is(Items.GLASS)
                || stack.is(Items.GLOWSTONE_DUST) || stack.is(Items.NAME_TAG);
    }

    /** Shift-right-click on a Glint Table dumps the bag's trims into the player's table libraries (empty trims
     *  teach their design, painted trims join the printed library). A normal click is intercepted by the block
     *  first (it opens the table), so this only fires while sneaking. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockState state = level.getBlockState(context.getClickedPos());
        if (!state.is(ModBlocks.GLINT_TABLE_BLOCK.get())) return InteractionResult.PASS;
        if (!level.isClientSide && context.getPlayer() instanceof ServerPlayer sp) {
            GlintTableMenu.depositBagContents(sp, context.getItemInHand());
            level.playSound(null, context.getClickedPos(), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.8f, 1.0f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Shift-right-click toggles auto-collect (and the glint/glow that marks it).
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                boolean on = !isAutoCollect(stack);
                setAutoCollect(stack, on);
                player.displayClientMessage(Component.literal("Auto-collect: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(on ? "ON" : "OFF")
                                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            boolean mainHand = hand == InteractionHand.MAIN_HAND;
            MenuProvider provider = new SimpleMenuProvider(
                    (id, inv, p) -> new GlintBagMenu(id, inv, hand), stack.getHoverName());
            sp.openMenu(provider, buf -> buf.writeBoolean(mainHand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Auto-move: while carried with auto-collect on, periodically sweep the player inventory and pull any loose
     *  storable Glint items into the bag. The ground-pickup hook catches items before they land; this catches
     *  the rest: dragged out of a loot chest, shift-clicked in, given, etc. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(entity instanceof Player player)) return;
        if (!isAutoCollect(stack)) return;
        if (level.getGameTime() % SCAN_INTERVAL != 0) return;

        IItemHandler handler = createHandler(stack);
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !isAutoCollectable(s)) continue; // glint loot only, never vacuums generic materials
            inv.items.set(i, ItemHandlerHelper.insertItemStacked(handler, s, false));
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Holds Glint Trims, Tears and Table materials").withStyle(ChatFormatting.GRAY));
        boolean on = isAutoCollect(stack);
        tooltip.add(Component.literal("Auto-collect: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)));
        tooltip.add(Component.literal("Shift-right-click to toggle auto-collect").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Shift-right-click a Glint Table to unload into it").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Item handler that reads from / writes back to the bag stack's {@code minecraft:container} component and
     *  only accepts storable items. */
    private static final class BagItemHandler extends ComponentItemHandler {
        BagItemHandler(ItemStack bag) {
            super(bag, DataComponents.CONTAINER, SIZE);
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            // Must accept the empty stack: ComponentItemHandler.setStackInSlot throws on an invalid stack, and
            // the menu clears slots by setting them empty (AbstractContainerMenu.initializeContents). Only
            // reject real, non-storable items.
            return stack.isEmpty() || canStore(stack);
        }
    }
}
