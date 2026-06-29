package net.tunamods.customglint.module.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.PacketDistributor;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.item.GlintLayerTearItem;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Server-side container for the Glint Table. The slots mirror every item the trim-modifying recipes consume
 * (dye→color, redstone→speed, slime→scale, glass→opacity, glowstone→glow, name tag→name, a second trim→merge
 * or layer, tears→mode + layers, rainbow dye→custom-hex color). See the SLOT_* constants for the index→role
 * mapping; slots are added in container-index order so {@code menu.slots.get(i) == container slot i}.
 */
public class GlintTableMenu extends AbstractContainerMenu {

    public static final int SLOT_TRIM      = 0;
    public static final int SLOT_SLIME     = 1;
    public static final int SLOT_REDSTONE  = 2;
    public static final int SLOT_GLASS     = 3;
    public static final int SLOT_GLOWSTONE = 4;
    public static final int SLOT_NAMETAG   = 5;
    public static final int SLOT_TRIM_B    = 6;
    public static final int SLOT_TEAR      = 7;
    public static final int SLOT_DYE_START = 8;
    public static final int SLOT_NAME_DYE  = SLOT_DYE_START + 16; // 24, name-color dye (shown when Name)
    public static final int SLOT_GLOW_DYE  = SLOT_NAME_DYE + 1;   // 25, glow-color dye (shown when Glow + manual)
    public static final int SLOT_LAYER_TEAR = SLOT_GLOW_DYE + 1;  // 26, layer tears (one per extra layer)
    public static final int SLOT_TEAR_SEQ  = SLOT_LAYER_TEAR + 1; // 27, sequential tear (beside SLOT_TEAR, the simultaneous one)
    public static final int SLOT_RAINBOW_DYE = SLOT_TEAR_SEQ + 1; // 28, rainbow dye (17th dye-bar slot, enables custom-hex colors)

    /** Marks a custom-hex colour inside a print packet's shard array ({@code CUSTOM_FLAG | rgb}); costs one
     *  rainbow dye. Mirrors the screen's encoding. */
    private static final int CUSTOM_FLAG = 0x40000000;

    public static final int TABLE_SIZE = SLOT_RAINBOW_DYE + 1;   // 29

    /** Stack cap for every table slot except the name tag (a boolean gate, capped at 1). */
    private static final int SLOT_MAX = 64;

    private static final int INV_START = TABLE_SIZE;          // 29
    private static final int INV_END   = TABLE_SIZE + 36;     // 65

    /** Client-only flags set by the screen to show/hide the conditional name/glow dye slots. */
    public boolean showNameDye = false, showGlowDye = false;

    /** The 16 vanilla dye items, indexed by {@link DyeColor#getId()}. */
    public static final Item[] DYE_ITEMS = {
            Items.WHITE_DYE, Items.ORANGE_DYE, Items.MAGENTA_DYE, Items.LIGHT_BLUE_DYE,
            Items.YELLOW_DYE, Items.LIME_DYE, Items.PINK_DYE, Items.GRAY_DYE,
            Items.LIGHT_GRAY_DYE, Items.CYAN_DYE, Items.PURPLE_DYE, Items.BLUE_DYE,
            Items.BROWN_DYE, Items.GREEN_DYE, Items.RED_DYE, Items.BLACK_DYE
    };

    private final Container container;
    private final ContainerLevelAccess access;
    private final Player player;

    /** Client constructor — a transient container; the server syncs the real contents into it. */
    public GlintTableMenu(int containerId, Inventory inventory, FriendlyByteBuf extraData) {
        this(containerId, inventory, new SimpleContainer(TABLE_SIZE) {
            @Override
            public int getMaxStackSize() {
                return SLOT_MAX; // mirror the block entity's raised cap so client/server agree
            }
        }, ContainerLevelAccess.NULL);
    }

    public GlintTableMenu(int containerId, Inventory inventory, Container container, ContainerLevelAccess access) {
        super(ModMenuTypes.GLINT_TABLE_MENU.get(), containerId);
        this.container = container;
        this.access = access;
        this.player = inventory.player;

        addSlot(new FilteredSlot(container, SLOT_TRIM,      136, 19,  GlintTableMenu::isAnyTrim,        SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_SLIME,     90,  154, s -> s.is(Items.SLIME_BALL),      SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_REDSTONE,  18,  154, s -> s.is(Items.REDSTONE),        SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_GLASS,     54,  154, s -> s.is(Items.GLASS),           SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_GLOWSTONE, 233, 154, s -> s.is(Items.GLOWSTONE_DUST),  SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_NAMETAG,   267, 154, s -> s.is(Items.NAME_TAG),        1));
        addSlot(new FilteredSlot(container, SLOT_TRIM_B,    188, 19,  GlintTableMenu::isAnyTrim,        SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_TEAR,      292, 154, GlintTableMenu::isSimTear,        SLOT_MAX));

        // 16 dye slots, full-width bar (the rainbow dye is the 17th cell, added below).
        for (int i = 0; i < 16; i++) {
            Item dyeItem = DYE_ITEMS[i];
            addSlot(new FilteredSlot(container, SLOT_DYE_START + i, 18 + i * 18, 222, s -> s.getItem() == dyeItem, SLOT_MAX));
        }

        // Conditional color-dye slots. Active client-side only when toggled on; always active server-side.
        addSlot(new ToggleDyeSlot(container, SLOT_NAME_DYE, 195, 184, () -> isServer() || showNameDye));
        addSlot(new ToggleDyeSlot(container, SLOT_GLOW_DYE, 195, 202, () -> isServer() || showGlowDye));

        addSlot(new FilteredSlot(container, SLOT_LAYER_TEAR, 162, 20, GlintTableMenu::isLayerTear, SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_TEAR_SEQ, 310, 154, GlintTableMenu::isSeqTear, SLOT_MAX));
        addSlot(new FilteredSlot(container, SLOT_RAINBOW_DYE, 18 + 16 * 18, 222, GlintTableMenu::isRainbowDye, SLOT_MAX));

        addPlayerInventory(inventory, 90, 252);

        // Push the player's stored-design set + printed-trim library to the client when the table opens.
        if (player instanceof ServerPlayer sp) {
            sendTo(sp, new GlintStoredSyncPacket(new ArrayList<>(GlintTablePlayerData.storedDesigns(sp))));
            List<ItemStack> printed = new ArrayList<>();
            for (ItemStack s : GlintTablePlayerData.printedTrims(sp)) if (!s.isEmpty()) printed.add(s);
            sendTo(sp, new GlintPrintedSyncPacket(printed));
        }
    }

    private static void sendTo(ServerPlayer sp, Object packet) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), packet);
    }

    private void addPlayerInventory(Inventory inv, int x, int y) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, x + col * 18, y + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, x + col * 18, y + 58));
    }

    /** A "painted" trim (color applied) lives in the printed library; an empty trim goes to the palette. */
    private static boolean isPainted(ItemStack stack) {
        if (stack.getItem() instanceof GlintTrimItem) {
            return GlintTrimItem.getColors(stack).length > 0 || GlintTrimItem.isGlowing(stack);
        }
        if (stack.getItem() instanceof GlowTrimItem) {
            return GlowTrimItem.getColors(stack).length > 0;
        }
        return false;
    }

    /** Records one finished painted trim into the player's printed library (deduped, capped at 128) + syncs. */
    private static void storePrinted(ServerPlayer sp, ItemStack trim) {
        List<ItemStack> list = GlintTablePlayerData.printedTrims(sp);
        for (ItemStack s : list) if (ItemStack.isSameItemSameTags(s, trim)) return;
        if (list.size() >= 128) return;
        ItemStack one = trim.copy();
        one.setCount(1);
        List<ItemStack> updated = new ArrayList<>();
        for (ItemStack s : list) if (!s.isEmpty()) updated.add(s);
        updated.add(one);
        GlintTablePlayerData.setPrintedTrims(sp, updated);
        sendTo(sp, new GlintPrintedSyncPacket(new ArrayList<>(updated)));
    }

    /**
     * Print a finished trim: build it from the sent params + the dye/material slots, validate and consume
     * the cost, then give it to the player and store it in the printed library. No-op on validation fail.
     */
    public void print(String designId, float speed, float scale, int opacity,
                      boolean glow, boolean glowAuto, boolean named, String name, boolean simultaneous,
                      int scrollDir, float scrollOffset, boolean interpolate, int glowHex, int nameHex, int[][] shardDyes,
                      CustomGlint.Layer[] belowLayers, CustomGlint.Layer[] aboveLayers, boolean sourceSimultaneous) {
        if (!(player instanceof ServerPlayer sp)) return;
        ResourceLocation design = ResourceLocation.tryParse(designId);
        if (design == null) return;

        int extraLayers = belowLayers.length + aboveLayers.length;
        if (extraLayers > 0) {
            if (container.getItem(SLOT_LAYER_TEAR).getCount() < extraLayers) return;
            for (CustomGlint.Layer l : belowLayers) if (!ownsDesign(sp, l.design())) return;
            for (CustomGlint.Layer l : aboveLayers) if (!ownsDesign(sp, l.design())) return;
        }

        ItemStack base = container.getItem(SLOT_TRIM);
        boolean fromBase = base.getItem() instanceof GlintTrimItem;

        // Donor colors are always derived from the real donor slot server-side (never trusted from the client).
        ItemStack donor = container.getItem(SLOT_TRIM_B);
        int[] donorColors = donor.getItem() instanceof GlintTrimItem ? GlintTrimItem.getColors(donor) : new int[0];

        if (!fromBase && !ownsDesign(sp, design)) return;

        int alpha = Math.round(255f - opacity * (255f - 32f) / 8f);
        int[] baseColors = fromBase ? GlintTrimItem.getColors(base) : new int[0];
        int colorBudget = Math.max(0, 8 - baseColors.length);
        List<Integer> newColors = new ArrayList<>();
        List<Integer> usedDyeSlots = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        int rainbowNeeded = 0;
        for (int[] shard : shardDyes) {
            if (shard.length == 0 || newColors.size() >= colorBudget) continue;
            if (shard.length == 1 && (shard[0] & CUSTOM_FLAG) != 0) {
                int rgb = shard[0] & 0xFFFFFF;
                if (container.getItem(SLOT_RAINBOW_DYE).getCount() < rainbowNeeded + 1) continue;
                if (containsRgb(baseColors, rgb)) continue;
                newColors.add((alpha << 24) | rgb);
                rainbowNeeded++;
                continue;
            }
            int r = 0, g = 0, b = 0;
            boolean allPresent = true;
            for (int idx : shard) {
                if (idx < 0 || idx >= 16 || dyeOf(container.getItem(SLOT_DYE_START + idx)) == null) { allPresent = false; break; }
                int rgb = GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF;
                r += (rgb >> 16) & 0xFF; g += (rgb >> 8) & 0xFF; b += rgb & 0xFF;
            }
            if (!allPresent) continue;
            int n = shard.length;
            int rgb = ((r / n) << 16) | ((g / n) << 8) | (b / n);
            if (containsRgb(baseColors, rgb)) continue;
            newColors.add((alpha << 24) | rgb);
            for (int idx : shard) if (consumed.add(SLOT_DYE_START + idx)) usedDyeSlots.add(SLOT_DYE_START + idx);
        }
        if (baseColors.length + newColors.size() == 0) return;
        if (baseColors.length + newColors.size() + donorColors.length > 8) return;

        int redCost = Math.abs(speed - 1.0f) > 0.001f ? 1 : 0;
        int slimeCost = Math.abs(scale - 1.0f) > 0.001f ? 1 : 0;
        int glassCost = opacity > 0 ? 1 : 0;
        for (CustomGlint.Layer l : belowLayers) { redCost += layerTunedSpeed(l); slimeCost += layerTunedScale(l); glassCost += layerTranslucent(l); }
        for (CustomGlint.Layer l : aboveLayers) { redCost += layerTunedSpeed(l); slimeCost += layerTunedScale(l); glassCost += layerTranslucent(l); }

        if (redCost > 0 && container.getItem(SLOT_REDSTONE).getCount() < redCost) return;
        if (slimeCost > 0 && container.getItem(SLOT_SLIME).getCount() < slimeCost) return;
        if (glassCost > 0 && container.getItem(SLOT_GLASS).getCount() < glassCost) return;
        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        boolean baseNamed = fromBase && base.hasCustomHoverName();
        if (glow && !baseGlowing && container.getItem(SLOT_GLOWSTONE).isEmpty()) return;
        if (named && !baseNamed && container.getItem(SLOT_NAMETAG).isEmpty()) return;
        DyeColor glowDye = dyeOf(container.getItem(SLOT_GLOW_DYE));
        DyeColor nameDye = dyeOf(container.getItem(SLOT_NAME_DYE));
        boolean glowRainbow = isRainbowDye(container.getItem(SLOT_GLOW_DYE));
        boolean nameRainbow = isRainbowDye(container.getItem(SLOT_NAME_DYE));
        int glowColor = glowRainbow ? glowHex : dyeColor(glowDye);
        int nameColor = nameRainbow ? nameHex : dyeColor(nameDye);
        if (glow && !glowAuto && glowColor < 0 && !baseHasGlowColors) return;

        ItemStack trim;
        if (fromBase) {
            trim = base.copy();
            trim.setCount(1);
        } else {
            trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        }
        for (int c : newColors) GlintTrimItem.addColor(trim, c);
        for (int c : donorColors) GlintTrimItem.addColor(trim, c);
        // Sanitize client-sent floats before they persist into the (broadcast) trim — a crafted packet must
        // not write NaN/Infinity/out-of-range speed or scale, matching the editor's own clamps.
        GlintTrimItem.setSpeed(trim, Float.isFinite(speed) ? Math.max(0.10f, Math.min(8.0f, speed)) : 1.0f);
        GlintTrimItem.setScale(trim, Float.isFinite(scale) ? Math.max(0.10f, Math.min(8.0f, scale)) : 1.0f);
        GlintTrimItem.setScrollDir(trim, scrollDir);
        GlintTrimItem.setScrollOffset(trim, Float.isFinite(scrollOffset) ? Math.max(0.0f, Math.min(1.0f, scrollOffset)) : 0.0f);
        GlintTrimItem.setPattern(trim, design);
        int committedSim = 0;
        for (CustomGlint.Layer l : belowLayers) if (l.simultaneous()) committedSim++;
        for (CustomGlint.Layer l : aboveLayers) if (l.simultaneous()) committedSim++;
        int availSimTears = container.getItem(SLOT_TEAR).getCount();
        if (availSimTears < committedSim) return;
        boolean activeSim = simultaneous && availSimTears > committedSim;
        int simTearsUsed = committedSim + (activeSim ? 1 : 0);
        boolean consumeSeqTear = !simultaneous && sourceSimultaneous && !container.getItem(SLOT_TEAR_SEQ).isEmpty();
        GlintTrimItem.setGlowing(trim, glow);
        CustomGlint.setGlowing(trim, glow);
        if (glow && !glowAuto && glowColor >= 0) CustomGlint.setGlowColors(trim, new int[]{0xFF000000 | glowColor});
        if (named && !name.isEmpty()) {
            int rgb = nameColor >= 0 ? nameColor : 0xFFFFFF;
            trim.setHoverName(Component.literal(name).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
        }
        // Final active-layer Data write carrying the chosen interpolation (and simultaneous when a tear is
        // present). Written LAST — setGlowing/setPattern reset interpolate/simultaneous.
        CustomGlint.write(trim, design, GlintTrimItem.getColors(trim), speed, interpolate, scale,
                activeSim, GlintTrimItem.getScrollDir(trim), GlintTrimItem.getScrollOffset(trim));

        if (extraLayers > 0) {
            CustomGlint.Data activeData = CustomGlint.read(trim);
            List<CustomGlint.Layer> all = new ArrayList<>();
            Collections.addAll(all, belowLayers);
            if (activeData != null) Collections.addAll(all, activeData.layers());
            Collections.addAll(all, aboveLayers);
            CustomGlint.write(trim, all.toArray(new CustomGlint.Layer[0]));
        }

        // Roll a stable oil-slick seed into any unseeded chromatic layer once, so the printed trim keeps one
        // pattern (the editor builds layers without a seed; only re-write when something actually changed).
        CustomGlint.Data printed = CustomGlint.read(trim);
        if (printed != null) {
            CustomGlint.Layer[] seeded = CustomGlint.ensureChromaticSeeds(printed.layers());
            if (seeded != printed.layers()) CustomGlint.write(trim, seeded);
        }

        // Consume the cost.
        if (redCost > 0) container.removeItem(SLOT_REDSTONE, redCost);
        if (slimeCost > 0) container.removeItem(SLOT_SLIME, slimeCost);
        if (glassCost > 0) container.removeItem(SLOT_GLASS, glassCost);
        for (int slot : usedDyeSlots) container.removeItem(slot, 1);
        if (rainbowNeeded > 0) container.removeItem(SLOT_RAINBOW_DYE, rainbowNeeded);
        if (glow && !baseGlowing) container.removeItem(SLOT_GLOWSTONE, 1);
        if (simTearsUsed > 0) container.removeItem(SLOT_TEAR, simTearsUsed);
        if (consumeSeqTear) container.removeItem(SLOT_TEAR_SEQ, 1);
        if (glow && !glowAuto && glowColor >= 0) container.removeItem(SLOT_GLOW_DYE, 1);
        if (named && !name.isEmpty() && (nameDye != null || nameRainbow)) container.removeItem(SLOT_NAME_DYE, 1);
        if (extraLayers > 0) container.removeItem(SLOT_LAYER_TEAR, extraLayers);

        if (!sp.addItem(trim)) sp.drop(trim, false);
        storePrinted(sp, trim);
    }

    /** Storage-library name for a trim stack (a Glint design name, or the Glow Trim sentinel), or null. */
    private static String designName(ItemStack stack) {
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.STORAGE_KEY;
        ResourceLocation pattern = GlintTrimItem.getPattern(stack);
        if (pattern == null) return null;
        return pattern.equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(pattern);
    }

    @Override
    public void slotsChanged(Container changed) {
        super.slotsChanged(changed);
        if (!(player instanceof ServerPlayer sp)) return;

        List<String> stored = GlintTablePlayerData.storedDesigns(sp);
        List<String> updated = null;
        for (int idx : new int[]{SLOT_TRIM, SLOT_TRIM_B}) {
            ItemStack s = container.getItem(idx);
            if (!isAnyTrim(s)) continue;
            String name = designName(s);
            if (name == null || stored.contains(name) || (updated != null && updated.contains(name))) continue;
            if ((updated != null ? updated.size() : stored.size()) >= MAX_STORED_DESIGNS) continue;
            if (updated == null) updated = new ArrayList<>(stored);
            updated.add(name);
        }
        if (updated != null) {
            GlintTablePlayerData.setStoredDesigns(sp, updated);
            sendTo(sp, new GlintStoredSyncPacket(new ArrayList<>(updated)));
        }
    }

    private static final int MAX_STORED_DESIGNS = 128;

    private static void storeDesign(ServerPlayer sp, String name) {
        List<String> stored = GlintTablePlayerData.storedDesigns(sp);
        if (stored.contains(name) || stored.size() >= MAX_STORED_DESIGNS) return;
        List<String> updated = new ArrayList<>(stored);
        updated.add(name);
        GlintTablePlayerData.setStoredDesigns(sp, updated);
        sendTo(sp, new GlintStoredSyncPacket(new ArrayList<>(updated)));
    }

    /** The {@link DyeColor} an item dyes with (vanilla {@link DyeItem}), or null. */
    private static DyeColor dyeOf(ItemStack stack) {
        return stack.getItem() instanceof DyeItem di ? di.getDyeColor() : null;
    }

    private static int dyeColor(DyeColor dye) {
        if (dye == null) return -1;
        int idx = dye.ordinal();
        if (idx < 0 || idx >= GlintTrimItem.DYE_COLORS.length) return -1;
        return GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF;
    }

    private boolean isServer() {
        return !player.level().isClientSide();
    }

    /** Right-click on a dye slot is reserved for the screen's color-selection gesture, so block its
     *  pickup/place at the menu level (runs on both sides). Left-click and shift still behave normally. */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
        if (clickType == ClickType.PICKUP && button == 1
                && slotId >= SLOT_DYE_START && slotId < SLOT_DYE_START + 16) {
            return;
        }
        super.clicked(slotId, button, clickType, clicker);
    }

    private static boolean containsRgb(int[] colors, int rgb) {
        for (int c : colors) if ((c & 0xFFFFFF) == rgb) return true;
        return false;
    }

    private static boolean ownsDesign(ServerPlayer sp, ResourceLocation design) {
        String dn = design.equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(design);
        return GlintTablePlayerData.storedDesigns(sp).contains(dn);
    }

    private static int layerTunedSpeed(CustomGlint.Layer l) { return Math.abs(l.speed() - 1.0f) > 0.001f ? 1 : 0; }
    private static int layerTunedScale(CustomGlint.Layer l) { return Math.abs(l.patternScale() - 1.0f) > 0.001f ? 1 : 0; }
    private static int layerTranslucent(CustomGlint.Layer l) { int[] c = l.colors(); return (c.length > 0 && ((c[0] >>> 24) & 0xFF) < 255) ? 1 : 0; }

    private static boolean isAnyTrim(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem || stack.getItem() instanceof GlowTrimItem;
    }

    private static boolean isSimTear(ItemStack stack) {
        return stack.getItem() == CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get();
    }

    private static boolean isSeqTear(ItemStack stack) {
        return stack.getItem() == CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get();
    }

    private static boolean isRainbowDye(ItemStack stack) {
        return stack.getItem() == CustomGlintMod.RAINBOW_DYE.get();
    }

    private static boolean isLayerTear(ItemStack stack) {
        return stack.getItem() instanceof GlintLayerTearItem;
    }

    private static int[] candidateSlots(ItemStack stack) {
        if (isAnyTrim(stack))               return new int[]{SLOT_TRIM, SLOT_TRIM_B};
        if (isLayerTear(stack))             return new int[]{SLOT_LAYER_TEAR};
        if (isSimTear(stack))               return new int[]{SLOT_TEAR};
        if (isSeqTear(stack))               return new int[]{SLOT_TEAR_SEQ};
        if (stack.is(Items.SLIME_BALL))     return new int[]{SLOT_SLIME};
        if (stack.is(Items.REDSTONE))       return new int[]{SLOT_REDSTONE};
        if (stack.is(Items.GLASS))          return new int[]{SLOT_GLASS};
        if (stack.is(Items.GLOWSTONE_DUST)) return new int[]{SLOT_GLOWSTONE};
        if (stack.is(Items.NAME_TAG))       return new int[]{SLOT_NAMETAG};
        if (isRainbowDye(stack))            return new int[]{SLOT_RAINBOW_DYE};
        DyeColor dye = dyeOf(stack);
        if (dye != null)                    return new int[]{SLOT_DYE_START + dye.getId()};
        return new int[0];
    }

    /** Drag-in deposit (server): the cursor-held trim is dropped onto a scrollable grid. */
    public void depositCarried() {
        if (!(player instanceof ServerPlayer sp)) return;
        ItemStack carried = getCarried();
        if (carried.isEmpty() || !isAnyTrim(carried)) return;
        if (isPainted(carried)) storePrinted(sp, carried);
        else if (designName(carried) != null) storeDesign(sp, designName(carried));
        else return;
        carried.shrink(1);
        setCarried(carried);
    }

    /** Withdraw (server): shift-click a trim in the printed library pulls one copy into the player's inventory. */
    public void withdrawPrinted(int index) {
        if (!(player instanceof ServerPlayer sp)) return;
        List<ItemStack> list = GlintTablePlayerData.printedTrims(sp);
        if (index < 0 || index >= list.size()) return;
        ItemStack trim = list.get(index);
        if (trim.isEmpty()) return;
        ItemStack one = trim.copy();
        one.setCount(1);
        if (!sp.addItem(one)) return;

        List<ItemStack> updated = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (i == index) continue;
            ItemStack s = list.get(i);
            if (!s.isEmpty()) updated.add(s);
        }
        GlintTablePlayerData.setPrintedTrims(sp, updated);
        sendTo(sp, new GlintPrintedSyncPacket(new ArrayList<>(updated)));
    }

    /** Give the player a free blank trim of a palette design (shift-click in the left grid). */
    public void giveDesignCopy(String name) {
        if (!(player instanceof ServerPlayer sp)) return;
        ItemStack stack;
        if (GlowTrimItem.STORAGE_KEY.equals(name)) {
            stack = new ItemStack(CustomGlintMod.GLOW_TRIM.get());
        } else {
            stack = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(stack, CustomGlint.designFromName(name));
        }
        sp.addItem(stack);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < TABLE_SIZE) {
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else if (isAnyTrim(stack) && (isPainted(stack) || designName(stack) != null)) {
            if (player instanceof ServerPlayer sp) {
                if (isPainted(stack)) storePrinted(sp, stack);
                else storeDesign(sp, designName(stack));
            }
            stack.shrink(1);
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            return ItemStack.EMPTY;
        } else {
            int[] candidates = candidateSlots(stack);
            if (candidates.length == 0) return ItemStack.EMPTY;
            boolean moved = false;
            for (int c : candidates) {
                if (moveItemStackTo(stack, c, c + 1, false)) {
                    moved = true;
                    if (stack.isEmpty()) break;
                }
            }
            if (!moved) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();

        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.GLINT_TABLE_BLOCK.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Contents are stored per player and saved on every change, so closing keeps them as-is.
    }

    /** Dye-only slot that is hidden/shown by a client toggle (via {@link Slot#isActive()}). */
    private static final class ToggleDyeSlot extends Slot {
        private final BooleanSupplier active;

        ToggleDyeSlot(Container container, int index, int x, int y, BooleanSupplier active) {
            super(container, index, x, y);
            this.active = active;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return dyeOf(stack) != null || isRainbowDye(stack);
        }

        @Override
        public int getMaxStackSize() {
            return SLOT_MAX;
        }

        @Override
        public boolean isActive() {
            return active.getAsBoolean();
        }
    }

    /** Slot that only accepts items matching a predicate and caps its own stack size. */
    private static final class FilteredSlot extends Slot {
        private final Predicate<ItemStack> filter;
        private final int max;

        FilteredSlot(Container container, int index, int x, int y, Predicate<ItemStack> filter, int max) {
            super(container, index, x, y);
            this.filter = filter;
            this.max = max;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return filter.test(stack);
        }

        @Override
        public int getMaxStackSize() {
            return max;
        }
    }
}
