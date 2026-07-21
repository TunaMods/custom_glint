package net.tunamods.customglint.module.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.network.NetworkHooks;
import net.tunamods.customglint.module.menu.GlintBagMenu;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A portable container for the mod's own loot (trims, glow trims, tears, rainbow dye) and the Glint Table
 * materials it takes to print them. Right-click opens it; storable items are auto-picked into it while it sits
 * in the inventory (see the pickup hook in {@link CustomGlintMod}). Contents live in the stack's own
 * {@code Inventory} NBT via an item-handler capability, so they travel with the bag.
 */
public class GlintBagItem extends Item {
    public static final int ROWS = 6;
    public static final int COLS = 9;
    public static final int SIZE = ROWS * COLS;

    /** NBT sub-tag on the bag stack that backs the item handler. */
    private static final String INVENTORY_TAG = "Inventory";
    /** NBT flag: whether the bag pulls loose Glint items into itself. Absent = on. */
    private static final String AUTO_COLLECT_TAG = "AutoCollect";
    /** Auto-collect only scans the inventory this often (ticks); the pickup hook handles instant collection. */
    private static final int SCAN_INTERVAL = 10;

    public GlintBagItem(Properties properties) {
        super(properties);
    }

    /** Whether this bag auto-collects. Default on (a bag with no flag yet). */
    public static boolean isAutoCollect(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null || !tag.contains(AUTO_COLLECT_TAG) || tag.getBoolean(AUTO_COLLECT_TAG);
    }

    /** Toggle auto-collect. The golden glint + glow doubles as the on/off indicator, so it's applied when on
     *  and stripped when off. */
    public static void setAutoCollect(ItemStack stack, boolean on) {
        stack.getOrCreateTag().putBoolean(AUTO_COLLECT_TAG, on);
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

    /** The mod's own loot items: trims, tears, rainbow dye. The base set of {@link #canStore}; auto-collect
     *  pulls in everything {@code canStore} accepts (this plus every Glint Table material). */
    public static boolean isGlintLoot(ItemStack stack) {
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
        if (isGlintLoot(stack)) return true;
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
            NetworkHooks.openScreen(sp, provider, buf -> buf.writeBoolean(mainHand));
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

        IItemHandler handler = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (handler == null) return;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !canStore(s)) continue;
            inv.items.set(i, ItemHandlerHelper.insertItemStacked(handler, s, false));
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Holds Glint Trims, Tears and Table materials").withStyle(ChatFormatting.GRAY));
        boolean on = isAutoCollect(stack);
        tooltip.add(Component.literal("Auto-collect: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)));
        tooltip.add(Component.literal("Shift-right-click to toggle auto-collect").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Shift-right-click a Glint Table to unload into it").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Nullable
    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new BagInventoryProvider(stack);
    }

    /** Exposes an {@link IItemHandler} backed by the bag stack's {@code Inventory} NBT. */
    private static final class BagInventoryProvider implements ICapabilityProvider {
        private final LazyOptional<IItemHandler> optional;

        BagInventoryProvider(ItemStack stack) {
            this.optional = LazyOptional.of(() -> new BagItemHandler(stack));
        }

        @NotNull
        @Override
        public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            return ForgeCapabilities.ITEM_HANDLER.orEmpty(cap, optional);
        }
    }

    /** Item handler that reads from / writes back to the bag stack's NBT and only accepts storable items. */
    private static final class BagItemHandler extends ItemStackHandler {
        private final ItemStack bag;

        BagItemHandler(ItemStack bag) {
            super(SIZE);
            this.bag = bag;
            CompoundTag tag = bag.getTagElement(INVENTORY_TAG);
            if (tag != null) deserializeNBT(tag);
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return canStore(stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            bag.getOrCreateTag().put(INVENTORY_TAG, serializeNBT());
        }
    }
}
