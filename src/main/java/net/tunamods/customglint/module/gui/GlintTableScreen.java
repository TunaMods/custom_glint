package net.tunamods.customglint.module.gui;

import net.tunamods.customglint.module.item.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import net.tunamods.customglint.module.client.GlintGuiConfig;
import net.tunamods.customglint.module.client.GlintTableModelClient;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.network.GlintDepositPacket;
import net.tunamods.customglint.module.network.GlintGiveDesignPacket;
import net.tunamods.customglint.module.network.GlintPrintPacket;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.tunamods.customglint.module.network.GlintWithdrawPacket;
import net.tunamods.customglint.module.network.ModNetworking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The Glint Table screen: a slot-based, multi-layer trim builder. A scrollable design palette (left) and the
 * player's printed-trim library (right) flank a center column with the live preview, layer strip, color
 * shards, modifier controls and the Print button. The container slots (trim, dyes, materials, tears, rainbow
 * dye) are real menu slots drawn over the skin's PNG window. Faithful 1.21.1 port of the 26.1.2 screen.
 */
public class GlintTableScreen extends AbstractContainerScreen<GlintTableMenu> {

    private static final int GRID_COLS = 6, GRID_ROWS = 7, CELL = 18;
    private static final int LGRID_X = 8, RGRID_X = 221, GRID_Y = 22;

    private static final int PREVIEW_X = 129, PREVIEW_Y = 68, PREVIEW_W = 82, PREVIEW_H = 82;
    private static final int PRINT_X = 128, PRINT_Y = 165, PRINT_W = 84, PRINT_H = 14;

    private static final int COLOR_STRIP_X = 122, COLOR_CELL = 12, COLOR_ICON = 12;
    private static final int COLOR_STRIP_Y = PREVIEW_Y + PREVIEW_H; // 150

    // ── Active skin palette ───────────────────────────────────────────────────
    private GlintTableSkin skin = GlintTableSkin.DEFAULT;
    private int DIM_GHOST, DIM_PREVIEW;
    private int RING_MAIN, RING_DONOR;
    private int GUI_FACE, GUI_SHADOW;
    private int SLOT_DARK;
    private int LABEL_HDR, COST_OK, COST_BAD;
    private int COLOR_UNSET, BTN_DISABLED;
    private int HOVER_TINT, BTN_HOVER;

    private void applySkin(GlintTableSkin s) {
        skin = s;
        GUI_FACE = s.guiFace; GUI_SHADOW = s.guiShadow;
        SLOT_DARK = s.slotDark;
        LABEL_HDR = s.labelHdr; COST_OK = s.costOk; COST_BAD = s.costBad;
        RING_MAIN = s.ringMain; RING_DONOR = s.ringDonor;
        DIM_GHOST = s.dimGhost; DIM_PREVIEW = s.dimPreview;
        COLOR_UNSET = s.colorUnset; BTN_DISABLED = s.btnDisabled;
        HOVER_TINT = s.hoverTint; BTN_HOVER = s.btnHover;
    }

    private void cycleSkin(int dir) {
        int n = GlintTableSkin.ALL.length;
        int idx = Math.floorMod(GlintTableSkin.indexOf(skin) + dir, n);
        applySkin(GlintTableSkin.ALL[idx]);
        GlintGuiConfig.setTableSkin(idx);
        GlintTableModelClient.refresh(); // the placed block follows the GUI skin
    }

    private static final int SKIN_BTN_W = 50, SKIN_BTN_H = 12;
    private static final int SKIN_BTN_X = 342 - SKIN_BTN_W - 4;
    private static final int SKIN_BTN_Y = 5;
    private static final int SND_BTN_W = 12;

    private final List<String> trims = new ArrayList<>();
    private final Map<String, ItemStack> trimCache = new HashMap<>();
    private int gridScroll = 0;
    private int printScroll = 0;
    private int draggingGrid = -1;

    private String selectedMain  = null;
    private ItemStack selectedDonor = ItemStack.EMPTY;
    private boolean selectedDonorPrinted = false;
    private ItemStack selectedPrinted = ItemStack.EMPTY;

    private final List<CustomGlint.Layer> lowerLayers = new ArrayList<>();
    private final List<CustomGlint.Layer> upperLayers = new ArrayList<>();
    private static final int MAX_LAYERS = 8;

    private static final int LAYER_STRIP_X = 122, LAYER_CELL = 12, LAYER_ICON = 12;
    private static final int LAYER_STRIP_Y = PREVIEW_Y - LAYER_ICON;

    private float modSpeed = 1.0f, modScale = 1.0f;
    private int modOpacity = 0;
    private int modScrollDir = CustomGlint.SCROLL_E;
    private float modScrollOffset = 0.0f;
    private boolean modInterpolate = true;
    private boolean modGlow = false, modNamed = false;
    private boolean glowAuto = true;
    private boolean tearSimultaneous = true;
    private boolean activeSourceSim = false;

    private final List<List<Integer>> colorShards = new ArrayList<>();
    private int selectedColorIdx = -1;
    private static final int RAINBOW = 16, CUSTOM_FLAG = 0x40000000;
    private EditBox hexBox;
    private boolean hexOpen = false;
    private int hexShardIdx = -1;
    private int hexMode = HEX_SHARD;
    private static final int HEX_SHARD = 0, HEX_GLOW = 1, HEX_NAME = 2;
    private int glowHex = -1, nameHex = -1;
    private ItemStack lastMain = ItemStack.EMPTY;
    private String trimName = "";
    private EditBox nameBox;
    private static final int ALPHA_MIN = 32;

    private BevelButton printBtn, glowModeBtn, glowToggleBtn, nameToggleBtn;
    private BevelButton offsetMinus, offsetPlus;

    private static final int SCROLL_X = 12, SCROLL_Y = 194, SCROLL_W = 100, SCROLL_H = 12;
    private static final int SCROLL_OFF_Y = 208;
    private static final int INTERP_X = 217, INTERP_Y = 194, INTERP_W = 116, INTERP_H = 12;
    private static final int NAME_BOX_X = 129, NAME_BOX_Y = 186, NAME_BOX_W = 62;
    private static final int GLOW_MODE_X = 129, GLOW_MODE_Y = 204, GLOW_MODE_W = 62, GLOW_MODE_H = 12;

    // Per-draw memo of the preview stacks (rebuilt many times per cascade otherwise).
    private boolean frameMemo = false;
    private ItemStack frameActiveTrim, framePreviewSource, frameActiveIcon;
    private Boolean frameCanPrint;
    private boolean frameCaptureDone;
    private CustomGlint.Layer frameCaptureActive;
    private Integer frameTotalLayers;
    private final Map<CustomGlint.Layer, ItemStack> layerIconCache = new IdentityHashMap<>();

    private boolean restored = false;
    private static Build savedBuild;

    public GlintTableScreen(GlintTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 342;
        this.imageHeight = 334;
        this.titleLabelY = 6;
        this.inventoryLabelY = 242;
    }

    private static ResourceLocation designRL(String name) {
        return CustomGlint.designFromName(name);
    }

    private static ItemStack trimStack(String name) {
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, designRL(name));
        return s;
    }

    private static DyeColor dyeOf(ItemStack stack) {
        return stack.getItem() instanceof DyeItem di ? di.getDyeColor() : null;
    }

    @Override
    protected void init() {
        super.init();
        applySkin(GlintTableSkin.byIndex(GlintGuiConfig.tableSkin()));
        GlintTableSkin.preloadTextures();
        addButtons();
        if (trims.isEmpty()) {
            trims.add(GlowTrimItem.STORAGE_KEY);
            trims.addAll(GlintTrimItem.PATTERNS);
        }
        if (trimCache.isEmpty()) {
            trimCache.put(GlowTrimItem.STORAGE_KEY, new ItemStack(ModItems.GLOW_TRIM.get()));
            for (String name : GlintTrimItem.PATTERNS) trimCache.put(name, trimStack(name));
        }
        this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;

        nameBox = new EditBox(font, leftPos + NAME_BOX_X, topPos + NAME_BOX_Y, NAME_BOX_W, 12,
                Component.translatable("screen.customglint.glint_table.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(trimName);
        nameBox.setResponder(s -> trimName = s);
        nameBox.setVisible(modNamed);
        addRenderableWidget(nameBox);

        hexBox = new EditBox(font, leftPos, topPos, 56, COLOR_ICON, Component.translatable("screen.customglint.glint_table.hex"));
        hexBox.setMaxLength(6);
        hexBox.setResponder(this::applyHex);
        hexBox.setVisible(false);
        addWidget(hexBox);

        if (!restored) { restoreBuild(); restored = true; }
    }

    @Override
    public void removed() {
        saveBuild();
        GlintGuiConfig.flush();
        layerIconCache.clear();
        super.removed();
    }

    // ── In-progress build persistence ──────────────────────────────────────────
    public static void clearSavedBuild() { savedBuild = null; }

    private static final class Build {
        String selectedMain, trimName;
        ItemStack selectedDonor, selectedPrinted;
        boolean selectedDonorPrinted, modInterpolate, modGlow, modNamed, glowAuto, tearSimultaneous, activeSourceSim;
        List<CustomGlint.Layer> lowerLayers, upperLayers;
        List<List<Integer>> colorShards;
        float modSpeed, modScale, modScrollOffset;
        int modOpacity, modScrollDir, selectedColorIdx, glowHex, nameHex;
    }

    private static List<List<Integer>> copyShards(List<List<Integer>> src) {
        List<List<Integer>> out = new ArrayList<>(src.size());
        for (List<Integer> s : src) out.add(new ArrayList<>(s));
        return out;
    }

    private void saveBuild() {
        Build b = new Build();
        b.selectedMain = selectedMain;
        b.selectedDonor = selectedDonor.copy();
        b.selectedDonorPrinted = selectedDonorPrinted;
        b.selectedPrinted = selectedPrinted.copy();
        b.lowerLayers = new ArrayList<>(lowerLayers);
        b.upperLayers = new ArrayList<>(upperLayers);
        b.colorShards = copyShards(colorShards);
        b.modSpeed = modSpeed; b.modScale = modScale; b.modOpacity = modOpacity;
        b.modScrollDir = modScrollDir; b.modScrollOffset = modScrollOffset;
        b.modInterpolate = modInterpolate; b.modGlow = modGlow; b.modNamed = modNamed;
        b.glowAuto = glowAuto; b.tearSimultaneous = tearSimultaneous; b.activeSourceSim = activeSourceSim;
        b.selectedColorIdx = selectedColorIdx; b.glowHex = glowHex; b.nameHex = nameHex;
        b.trimName = trimName;
        savedBuild = b;
    }

    private void restoreBuild() {
        Build b = savedBuild;
        if (b == null) return;
        selectedMain = b.selectedMain;
        selectedDonor = b.selectedDonor == null ? ItemStack.EMPTY : b.selectedDonor.copy();
        selectedDonorPrinted = b.selectedDonorPrinted;
        selectedPrinted = b.selectedPrinted == null ? ItemStack.EMPTY : b.selectedPrinted.copy();
        lowerLayers.clear(); lowerLayers.addAll(b.lowerLayers);
        upperLayers.clear(); upperLayers.addAll(b.upperLayers);
        colorShards.clear(); colorShards.addAll(copyShards(b.colorShards));
        modSpeed = b.modSpeed; modScale = b.modScale; modOpacity = b.modOpacity;
        modScrollDir = b.modScrollDir; modScrollOffset = b.modScrollOffset;
        modInterpolate = b.modInterpolate; modGlow = b.modGlow; modNamed = b.modNamed;
        glowAuto = b.glowAuto; tearSimultaneous = b.tearSimultaneous; activeSourceSim = b.activeSourceSim;
        selectedColorIdx = b.selectedColorIdx; glowHex = b.glowHex; nameHex = b.nameHex;
        trimName = b.trimName == null ? "" : b.trimName;
        if (nameBox != null) { nameBox.setValue(trimName); nameBox.setVisible(modNamed); }
    }

    private void clearBuild() {
        selectedMain = null;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        selectedPrinted = ItemStack.EMPTY;
        lowerLayers.clear();
        upperLayers.clear();
        colorShards.clear();
        selectedColorIdx = -1;
        modSpeed = modScale = 1.0f;
        modOpacity = 0;
        modScrollDir = CustomGlint.SCROLL_E;
        modScrollOffset = 0.0f;
        modInterpolate = true;
        modGlow = modNamed = false;
        glowAuto = true;
        tearSimultaneous = true;
        activeSourceSim = false;
        glowHex = nameHex = -1;
        trimName = "";
        if (nameBox != null) { nameBox.setValue(""); nameBox.setVisible(false); }
        closeHex();
    }

    private void quickMoveSlot(int slot) {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, minecraft.player);
    }

    // ── Selection ───────────────────────────────────────────────────────────────

    private ItemStack selectionPreview() {
        if (selectedMain == null) return ItemStack.EMPTY;
        ItemStack main = trimCache.get(selectedMain);
        return main == null ? ItemStack.EMPTY : main.copy();
    }

    private ItemStack printedSelection() {
        return selectedPrinted.isEmpty() ? ItemStack.EMPTY : selectedPrinted;
    }

    private ItemStack donorStack() {
        ItemStack phys = menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem();
        if (phys.getItem() instanceof GlintTrimItem) return phys;
        return selectedDonor;
    }

    private int[] donorColors() {
        ItemStack d = donorStack();
        return d.getItem() instanceof GlintTrimItem ? GlintTrimItem.getColors(d) : new int[0];
    }

    private boolean donorOwned() {
        if (menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem().getItem() instanceof GlintTrimItem) return true;
        if (selectedDonor.isEmpty() || selectedDonorPrinted) return true;
        String name = trimDesignName(selectedDonor);
        return name != null && GlintStoredSyncPacket.CLIENT_STORED.contains(name);
    }

    private ItemStack mergeDonor(ItemStack base) {
        if (!(base.getItem() instanceof GlintTrimItem) || donorColors().length == 0) return base;
        return GlintTrimItem.mergeColors(base, donorStack());
    }

    /** The active base trim WITHOUT the merge donor folded in (main slot, else the selected/printed trim). */
    private ItemStack activeBase() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!main.isEmpty()) return main;
        ItemStack sel = selectedPrinted.isEmpty() ? selectionPreview() : selectedPrinted;
        return applyMods(sel);
    }

    private ItemStack activeTrim() {
        if (frameMemo && frameActiveTrim != null) return frameActiveTrim;
        ItemStack result = mergeDonor(activeBase());
        if (frameMemo) frameActiveTrim = result;
        return result;
    }

    private ItemStack previewSource() {
        if (frameMemo && framePreviewSource != null) return framePreviewSource;
        ItemStack result = computePreviewSource();
        if (frameMemo) framePreviewSource = result;
        return result;
    }

    private ItemStack computePreviewSource() {
        // A trim in the merge slot is previewed STACKED on the main: the main design AND the donor's design(s)
        // shown together — what the merged result would look like — not the donor's colours folded into one
        // layer. Shown immediately, with no layer tear / ownership required (preview only; printing an unowned
        // or tear-less stack is still blocked). When stacking we use the un-merged base so the donor isn't
        // also colour-folded into the active layer.
        CustomGlint.Layer[] pending = donorLayers();
        boolean stackDonor = pending.length > 0;
        ItemStack active = stackDonor ? activeBase() : activeTrim();
        if (lowerLayers.isEmpty() && upperLayers.isEmpty() && !stackDonor) return active;
        List<CustomGlint.Layer> all = new ArrayList<>(lowerLayers);
        boolean activeValid = active.getItem() instanceof GlintTrimItem;
        if (activeValid) {
            CustomGlint.Data ad = CustomGlint.read(active);
            if (ad != null) Collections.addAll(all, ad.layers());
        }
        all.addAll(upperLayers);
        if (stackDonor) Collections.addAll(all, pending);
        if (all.isEmpty()) return active;
        ItemStack carrier = activeValid ? active.copy() : new ItemStack(ModItems.GLINT_TRIM.get());
        if (!activeValid) GlintTrimItem.setPattern(carrier, all.get(0).design());
        CustomGlint.write(carrier, all.toArray(new CustomGlint.Layer[0]));
        return carrier;
    }

    private CustomGlint.Layer captureActive() {
        if (frameMemo && frameCaptureDone) return frameCaptureActive;
        CustomGlint.Layer result = computeCaptureActive();
        if (frameMemo) { frameCaptureActive = result; frameCaptureDone = true; }
        return result;
    }

    private CustomGlint.Layer computeCaptureActive() {
        ItemStack a = activeTrim();
        if (!(a.getItem() instanceof GlintTrimItem)) return null;
        ResourceLocation design = GlintTrimItem.getPattern(a);
        int[] colors = GlintTrimItem.getColors(a);
        if (design == null) return null;
        boolean sim = false;
        CustomGlint.Data d = CustomGlint.read(a);
        if (d != null && d.layers().length > 0) sim = d.layers()[0].simultaneous();
        return new CustomGlint.Layer(design, colors, GlintTrimItem.getSpeed(a), modInterpolate,
                GlintTrimItem.getScale(a), sim, GlintTrimItem.getScrollDir(a), GlintTrimItem.getScrollOffset(a));
    }

    private void loadControlsFromLayer(CustomGlint.Layer l) {
        selectedPrinted = ItemStack.EMPTY;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        selectedMain = gridNameFor(l.design());
        modSpeed = Math.max(0.10f, Math.min(8.0f, l.speed()));
        modScale = Math.max(0.10f, Math.min(8.0f, l.patternScale()));
        modScrollDir = l.scrollDir();
        modScrollOffset = l.scrollOffset();
        modInterpolate = l.interpolate();
        tearSimultaneous = l.simultaneous();
        activeSourceSim = l.simultaneous();
        int[] c = l.colors();
        int alpha = c.length > 0 ? (c[0] >>> 24) & 0xFF : 255;
        modOpacity = Math.max(0, Math.min(8, Math.round((255 - alpha) * 8f / (255f - ALPHA_MIN))));
        colorShards.clear();
        selectedColorIdx = -1;
        for (int col : c) addColorShard(col & 0xFFFFFF);
    }

    private static List<Integer> newShard(int dye) {
        List<Integer> s = new ArrayList<>();
        s.add(dye);
        return s;
    }

    private void addColorShard(int rgb) {
        if (colorShards.size() >= 8) return;
        for (int i = 0; i < 16; i++) if (dyeRgb(i) == rgb) { colorShards.add(newShard(i)); return; }
        List<Integer> custom = new ArrayList<>();
        custom.add(CUSTOM_FLAG | rgb);
        colorShards.add(custom);
    }

    private void loadFromTrim(ItemStack trim) {
        selectedPrinted = trim;
        selectedMain = null;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        lowerLayers.clear();
        upperLayers.clear();
        loadModsFrom(trim);
        CustomGlint.Data d = CustomGlint.read(trim);
        if (d != null && d.layers().length > 1) {
            CustomGlint.Layer[] ls = d.layers();
            for (int i = 1; i < ls.length; i++) upperLayers.add(ls[i]);
            loadControlsFromLayer(ls[0]);
        }
    }

    private static String gridNameFor(ResourceLocation p) {
        if (p.equals(CustomGlint.VANILLA)) return "vanilla";
        String name = GlintTrimItem.extractPatternName(p);
        return p.getNamespace().equals("customglint") ? name : p.getNamespace() + ":" + name;
    }

    private int totalLayers() {
        if (frameMemo && frameTotalLayers != null) return frameTotalLayers;
        int result = lowerLayers.size() + upperLayers.size() + (captureActive() != null ? 1 : 0);
        if (frameMemo) frameTotalLayers = result;
        return result;
    }

    private int layerTearCount() {
        return menu.slots.get(GlintTableMenu.SLOT_LAYER_TEAR).getItem().getCount();
    }

    private boolean layerValid(CustomGlint.Layer l) {
        if (l.colors().length == 0 || l.colors().length > 8) return false;
        return GlintStoredSyncPacket.CLIENT_STORED.contains(gridNameFor(l.design()));
    }

    private ItemStack reproSource() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (main.getItem() instanceof GlintTrimItem) return main;
        return selectedPrinted.isEmpty() ? ItemStack.EMPTY : selectedPrinted;
    }

    private ItemStack applyMods(ItemStack base) {
        if (!(base.getItem() instanceof GlintTrimItem)) return base;
        ItemStack s = base.copy();
        GlintTrimItem.setSpeed(s, modSpeed);
        GlintTrimItem.setScale(s, modScale);
        GlintTrimItem.setScrollDir(s, modScrollDir);
        GlintTrimItem.setScrollOffset(s, modScrollOffset);
        GlintTrimItem.setColors(s, buildColors());
        GlintTrimItem.setGlowing(s, modGlow);
        CustomGlint.setGlowing(s, modGlow);
        int gc = modGlow && !glowAuto ? slotColor(GlintTableMenu.SLOT_GLOW_DYE, glowHex) : -1;
        if (gc >= 0) CustomGlint.setGlowColors(s, new int[]{0xFF000000 | gc});
        else CustomGlint.clearGlowColors(s);
        if (modNamed && !trimName.isEmpty()) {
            int nc = slotColor(GlintTableMenu.SLOT_NAME_DYE, nameHex);
            int rgb = nc >= 0 ? nc : 0xFFFFFF;
            s.setHoverName(Component.literal(trimName).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
        }
        ResourceLocation pat = GlintTrimItem.getPattern(s);
        if (pat != null) {
            boolean sim = menu.slots.get(activeTearSlot()).hasItem() && tearSimultaneous;
            CustomGlint.write(s, pat, GlintTrimItem.writeColors(pat, GlintTrimItem.getColors(s)), GlintTrimItem.getSpeed(s), modInterpolate,
                    GlintTrimItem.getScale(s), sim, GlintTrimItem.getScrollDir(s), GlintTrimItem.getScrollOffset(s), GlintTrimItem.getSeed(s));
        }
        return s;
    }

    private int modAlpha() {
        return Math.round(255f - modOpacity * (255f - ALPHA_MIN) / 8f);
    }

    private int[] buildColors() {
        int alpha = modAlpha();
        List<Integer> cols = new ArrayList<>();
        for (List<Integer> shard : colorShards) {
            int rgb = mixRgb(shard);
            if (rgb < 0) continue;
            if (cols.size() >= 8) break;
            cols.add((alpha << 24) | rgb);
        }
        int[] arr = new int[cols.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = cols.get(i);
        return arr;
    }

    private int countSelectedDyes() {
        int n = 0;
        for (List<Integer> shard : colorShards) if (mixRgb(shard) >= 0) n++;
        return n;
    }

    private static int mixRgb(List<Integer> shard) {
        if (shard.isEmpty()) return -1;
        if (shard.size() == 1) {
            int v = shard.get(0);
            if (v == RAINBOW) return -1;
            if ((v & CUSTOM_FLAG) != 0) return v & 0xFFFFFF;
        }
        int r = 0, g = 0, b = 0, n = 0;
        for (int d : shard) {
            if (d < 0 || d >= 16) continue;
            int rgb = dyeRgb(d); r += (rgb >> 16) & 0xFF; g += (rgb >> 8) & 0xFF; b += rgb & 0xFF; n++;
        }
        if (n == 0) return -1;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    private static boolean isCustomShard(List<Integer> shard) {
        if (shard.size() != 1) return false;
        int v = shard.get(0);
        return v == RAINBOW || (v & CUSTOM_FLAG) != 0;
    }

    private static int dyeRgb(int idx) {
        return GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF;
    }

    private void loadModsFrom(ItemStack trim) {
        colorShards.clear();
        selectedColorIdx = -1;
        if (trim.getItem() instanceof GlintTrimItem) {
            modSpeed = Math.max(0.10f, Math.min(8.0f, GlintTrimItem.getSpeed(trim)));
            modScale = Math.max(0.10f, Math.min(8.0f, GlintTrimItem.getScale(trim)));
            modScrollDir = GlintTrimItem.getScrollDir(trim);
            modScrollOffset = GlintTrimItem.getScrollOffset(trim);
            int[] c = GlintTrimItem.getColors(trim);
            int alpha = c.length > 0 ? (c[0] >>> 24) & 0xFF : 255;
            modOpacity = Math.max(0, Math.min(8, Math.round((255 - alpha) * 8f / (255f - ALPHA_MIN))));
            for (int col : c) addColorShard(col & 0xFFFFFF);
            modGlow = GlintTrimItem.isGlowing(trim);
            modNamed = trim.hasCustomHoverName();
            Component nm = (trim.hasCustomHoverName() ? trim.getHoverName() : null);
            trimName = (modNamed && nm != null) ? nm.getString() : "";
            if (nameBox != null) nameBox.setValue(trimName);
            glowAuto = CustomGlint.getGlowColors(trim).length == 0;
            CustomGlint.Data d = CustomGlint.read(trim);
            if (d != null && d.layers().length > 0) {
                tearSimultaneous = d.layers()[0].simultaneous();
                modInterpolate = d.layers()[0].interpolate();
            }
            activeSourceSim = tearSimultaneous;
        } else {
            modSpeed = modScale = 1.0f;
            modOpacity = 0;
            modScrollDir = CustomGlint.SCROLL_E;
            modScrollOffset = 0.0f;
            modInterpolate = true;
            modGlow = modNamed = false;
            trimName = "";
            activeSourceSim = false;
            if (nameBox != null) nameBox.setValue("");
        }
    }

    private static float stepUp(float v) {
        float nv = v < 1.0f ? v + 0.10f : v + 0.5f;
        return Math.min(8.0f, Math.round(nv * 100f) / 100f);
    }

    private static float stepDown(float v) {
        float nv = v <= 1.0f ? v - 0.10f : v - 0.5f;
        return Math.max(0.10f, Math.round(nv * 100f) / 100f);
    }

    private static String fmtVal(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format("%.2f", v).replaceAll("0+$", "");
    }

    // ── Render ──────────────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        // Drawn before the real slots: skin window, conditional wells, ghost hints.
        skin.windowPanel(g, leftPos, topPos, imageWidth, imageHeight);
        drawConditionalWell(g, GlintTableMenu.SLOT_NAME_DYE);
        drawConditionalWell(g, GlintTableMenu.SLOT_GLOW_DYE);
        for (int i = 0; i < GlintTableMenu.TABLE_SIZE; i++) {
            if (!menu.slots.get(i).isActive()) continue;
            ItemStack ghost = ghostFor(i);
            if (!ghost.isEmpty()) drawGhostItem(g, menu.slots.get(i), ghost);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        prologue();

        // Arm the per-frame memo BEFORE super.render: the widget face/label suppliers (printBtn, glowModeBtn)
        // call canPrint()/previewSource() during super.render, and without the memo armed each of those re-runs
        // the full uncached path (ItemStack.copy + NBT writes) every frame. syncButtons() also runs first so
        // widget .active states are fresh for the same draw rather than a frame stale.
        frameActiveTrim = null; framePreviewSource = null; frameActiveIcon = null; frameCanPrint = null;
        frameCaptureDone = false; frameTotalLayers = null; frameMemo = true;
        try {
            syncButtons();
            super.render(g, mx, my, dt); // dim + renderBg + slots + renderLabels + widgets + cursor + slot tooltips
            drawTrimGrid(g, mx, my);
            drawPrintedGrid(g, mx, my);
            drawMainPreview(g);
            drawDonorPreview(g);
            drawMainSlotRings(g);
            drawLayerStrip(g, mx, my);
            drawPreview(g);
            drawColorStrip(g, mx, my);
            drawModifierControls(g);
            drawNameGlowSection(g);
            drawDyeSelection(g);
            drawSectionLabels(g);
            if (hexBox != null && hexBox.isVisible()) hexBox.render(g, mx, my, dt);
        } finally {
            frameMemo = false;
        }
        // The custom panels above (trim/printed grids, previews) are drawn AFTER super.render, so any glowing
        // icons among them queue their glow rings too late for the container Foreground drain (which already
        // fired inside super.render). Drain them here, before our own tooltips, so the rings stay under the
        // tooltip instead of compositing over it at the ScreenEvent.Render.Post drain (which fires after render).
        GlowOutlineRenderer.drainGui();
        renderTableTooltip(g, mx, my);
    }

    /** Pre-draw state settling (was the head of 26.1.2's extractContents): runs before super.render so the
     *  conditional dye-slot flags + button states are correct for the slot/widget draw. */
    private void prologue() {
        ItemStack curMain = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!ItemStack.matches(curMain, lastMain)) {
            lastMain = curMain.copy();
            if (curMain.getItem() instanceof GlintTrimItem) loadModsFrom(curMain);
        }
        boolean mainTrim = curMain.getItem() instanceof GlintTrimItem;

        if (colorShards.isEmpty()) colorShards.add(new ArrayList<>());
        if (colorShards.size() == 1) selectedColorIdx = 0;

        if (!mainTrim) {
            if (!menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE).hasItem()) modGlow = false;
            if (!menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem()) modNamed = false;
        }

        menu.showNameDye = modNamed;
        menu.showGlowDye = modGlow && !glowAuto && !glowTrimMain();
        if (nameBox != null) nameBox.setVisible(modNamed);

        if (hexBox != null) {
            boolean show;
            int hx = 0, hy = 0;
            if (hexMode == HEX_GLOW) {
                Slot s = menu.slots.get(GlintTableMenu.SLOT_GLOW_DYE);
                show = hexOpen && menu.showGlowDye && rainbowInSlot(GlintTableMenu.SLOT_GLOW_DYE);
                hx = leftPos + s.x + 18; hy = topPos + s.y;
            } else if (hexMode == HEX_NAME) {
                Slot s = menu.slots.get(GlintTableMenu.SLOT_NAME_DYE);
                show = hexOpen && menu.showNameDye && rainbowInSlot(GlintTableMenu.SLOT_NAME_DYE);
                hx = leftPos + s.x + 18; hy = topPos + s.y;
            } else {
                show = hexOpen && hexShardIdx >= 0 && hexShardIdx < colorShards.size()
                        && isCustomShard(colorShards.get(hexShardIdx));
                hx = leftPos + COLOR_STRIP_X + hexShardIdx * COLOR_CELL + COLOR_ICON + 1;
                hy = topPos + COLOR_STRIP_Y;
            }
            if (hexOpen && !show) closeHex();
            hexBox.setVisible(show);
            if (show) { hexBox.setX(hx); hexBox.setY(hy); }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xFF000000 | LABEL_HDR, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF000000 | LABEL_HDR, false);
    }

    private void renderTableTooltip(GuiGraphics g, int mx, int my) {
        if (!menu.getCarried().isEmpty()) return;
        if (mx >= leftPos + SKIN_BTN_X && mx < leftPos + SKIN_BTN_X + SKIN_BTN_W
                && my >= topPos + SKIN_BTN_Y && my < topPos + SKIN_BTN_Y + SKIN_BTN_H) {
            g.renderTooltip(font, Component.translatable("screen.customglint.glint_table.skin_tooltip"), mx, my);
            return;
        }
        if (mx >= leftPos + SKIN_BTN_X - SND_BTN_W - 2 && mx < leftPos + SKIN_BTN_X - 2
                && my >= topPos + SKIN_BTN_Y && my < topPos + SKIN_BTN_Y + SKIN_BTN_H) {
            g.renderTooltip(font, Component.translatable("screen.customglint.glint_table.sound",
                    Component.translatable(GlintGuiConfig.sound()
                            ? "screen.customglint.glint_table.on" : "screen.customglint.glint_table.off")), mx, my);
            return;
        }
        if (mx >= leftPos + PRINT_X && mx < leftPos + PRINT_X + PRINT_W
                && my >= topPos + PRINT_Y && my < topPos + PRINT_Y + PRINT_H) {
            g.renderComponentTooltip(font, printTooltip(), mx, my);
            return;
        }
        int ri = gridIndexAt(mx, my, leftPos + RGRID_X, topPos + GRID_Y, printScroll, GlintPrintedSyncPacket.CLIENT_PRINTED.size());
        if (ri >= 0) { g.renderTooltip(font, GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri), mx, my); return; }
        int li = gridIndexAt(mx, my, leftPos + LGRID_X, topPos + GRID_Y, gridScroll, trims.size());
        if (li >= 0) {
            ItemStack stack = trimCache.get(trims.get(li));
            if (stack != null) g.renderTooltip(font, stack, mx, my);
        }
    }

    private void drawConditionalWell(GuiGraphics g, int containerSlot) {
        Slot s = menu.slots.get(containerSlot);
        if (s.isActive()) slotWell(g, leftPos + s.x - 1, topPos + s.y - 1);
    }

    private static ItemStack cyclingDyeGhost(int phase) {
        int n = GlintTableMenu.DYE_ITEMS.length;
        int idx = (int) (((Util.getMillis() / 1500) + phase) % n);
        return new ItemStack(GlintTableMenu.DYE_ITEMS[idx]);
    }

    // The constant ghost hints (materials, palette dyes, tears) never change, but the tear ghosts run a
    // full CustomGlint.write through getDefaultInstance(); cache them so renderBg doesn't rebuild every
    // frame. The two cycling-dye wells animate and are rebuilt per call below.
    private static final java.util.Map<Integer, ItemStack> GHOST_CACHE = new java.util.HashMap<>();

    private ItemStack ghostFor(int containerSlot) {
        if (containerSlot == GlintTableMenu.SLOT_NAME_DYE) return cyclingDyeGhost(0);
        if (containerSlot == GlintTableMenu.SLOT_GLOW_DYE) return cyclingDyeGhost(8);
        return GHOST_CACHE.computeIfAbsent(containerSlot, GlintTableScreen::buildGhost);
    }

    private static ItemStack buildGhost(int containerSlot) {
        if (containerSlot >= GlintTableMenu.SLOT_DYE_START && containerSlot < GlintTableMenu.SLOT_DYE_START + 16) {
            return new ItemStack(GlintTableMenu.DYE_ITEMS[containerSlot - GlintTableMenu.SLOT_DYE_START]);
        }
        return switch (containerSlot) {
            case GlintTableMenu.SLOT_SLIME     -> new ItemStack(Items.SLIME_BALL);
            case GlintTableMenu.SLOT_REDSTONE  -> new ItemStack(Items.REDSTONE);
            case GlintTableMenu.SLOT_GLASS     -> new ItemStack(Items.GLASS);
            case GlintTableMenu.SLOT_GLOWSTONE -> new ItemStack(Items.GLOWSTONE_DUST);
            case GlintTableMenu.SLOT_NAMETAG   -> new ItemStack(Items.NAME_TAG);
            case GlintTableMenu.SLOT_TEAR      -> ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance();
            case GlintTableMenu.SLOT_TEAR_SEQ  -> ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance();
            case GlintTableMenu.SLOT_LAYER_TEAR -> ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance();
            case GlintTableMenu.SLOT_RAINBOW_DYE -> ModItems.RAINBOW_DYE.get().getDefaultInstance();
            // SLOT_NAME_DYE / SLOT_GLOW_DYE animate and are handled in ghostFor (not cached here).
            default -> ItemStack.EMPTY;
        };
    }

    private void drawGhostItem(GuiGraphics g, Slot slot, ItemStack ghost) {
        if (slot.hasItem()) return;
        int x = leftPos + slot.x, y = topPos + slot.y;
        g.renderItem(ghost, x, y);
        overlay(g, x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    private static String trimDesignName(ItemStack stack) {
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.STORAGE_KEY;
        ResourceLocation p = GlintTrimItem.getPattern(stack);
        if (p == null) return null;
        if (p.equals(CustomGlint.VANILLA)) return "vanilla";
        String name = GlintTrimItem.extractPatternName(p);
        return p.getNamespace().equals("customglint") ? name : p.getNamespace() + ":" + name;
    }

    private String highlightedMain() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem());
        return n != null ? n : selectedMain;
    }

    private String highlightedDonor() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem());
        if (n != null) return n;
        return (!selectedDonor.isEmpty() && !selectedDonorPrinted) ? trimDesignName(selectedDonor) : null;
    }

    private void drawTrimGrid(GuiGraphics g, int mx, int my) {
        int gx = leftPos + LGRID_X, gy = topPos + GRID_Y;
        int total = trims.size();
        String mainName  = highlightedMain();
        String donorName = highlightedDonor();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cx = gx + col * CELL, cy = gy + row * CELL;
                slotWell(g, cx - 1, cy - 1);
                int idx = (gridScroll + row) * GRID_COLS + col;
                if (idx >= total) continue;
                String name = trims.get(idx);
                g.renderItem(trimCache.getOrDefault(name, ItemStack.EMPTY), cx, cy);
                if (!GlintStoredSyncPacket.CLIENT_STORED.contains(name)) overlay(g, cx, cy, cx + 16, cy + 16, DIM_GHOST);
                if (name.equals(mainName))       border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                else if (name.equals(donorName)) border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);
                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) overlay(g, cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, total, gridScroll);
    }

    private int printedCapacity() {
        int size = GlintPrintedSyncPacket.CLIENT_PRINTED.size();
        int cells = ((size / GRID_COLS) + 2) * GRID_COLS;
        return Math.max(GRID_ROWS * GRID_COLS, Math.min(128, cells));
    }

    private void drawPrintedGrid(GuiGraphics g, int mx, int my) {
        int gx = leftPos + RGRID_X, gy = topPos + GRID_Y;
        List<ItemStack> list = GlintPrintedSyncPacket.CLIENT_PRINTED;
        int cap = printedCapacity();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = (printScroll + row) * GRID_COLS + col;
                if (idx >= cap) continue;
                int cx = gx + col * CELL, cy = gy + row * CELL;
                slotWell(g, cx - 1, cy - 1);
                if (idx < list.size()) {
                    ItemStack s = list.get(idx);
                    g.renderItem(s, cx, cy);
                    if (!selectedPrinted.isEmpty() && ItemStack.isSameItemSameTags(s, selectedPrinted))
                        border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                    else if (selectedDonorPrinted && ItemStack.isSameItemSameTags(s, selectedDonor))
                        border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);
                }
                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) overlay(g, cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, cap, printScroll);
    }

    private void drawScrollbar(GuiGraphics g, int gx, int gy, int total, int scroll) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        if (maxRow <= 0) return;
        int trackX = gx + GRID_COLS * CELL + 1, trackH = GRID_ROWS * CELL;
        g.fill(trackX, gy, trackX + 4, gy + trackH, SLOT_DARK);
        int thumbH = Math.max(8, trackH * GRID_ROWS / rows);
        int thumbY = gy + (int) ((trackH - thumbH) * (float) scroll / maxRow);
        g.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, GUI_SHADOW);
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH - 1, GUI_FACE);
    }

    private boolean onScrollbar(double mx, double my, int gx, int gy, int total) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        if (rows - GRID_ROWS <= 0) return false;
        int trackX = gx + GRID_COLS * CELL + 1, trackH = GRID_ROWS * CELL;
        return mx >= trackX && mx < trackX + 4 && my >= gy && my < gy + trackH;
    }

    private int scrollFromMouse(double my, int gy, int total) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        if (maxRow <= 0) return 0;
        int trackH = GRID_ROWS * CELL;
        int thumbH = Math.max(8, trackH * GRID_ROWS / rows);
        float f = (float) ((my - gy - thumbH / 2.0) / (trackH - thumbH));
        return Math.round(Math.max(0f, Math.min(1f, f)) * maxRow);
    }

    private void drawMainPreview(GuiGraphics g) {
        Slot main = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        if (main.hasItem()) return;
        boolean printed = !printedSelection().isEmpty();
        boolean decomposed = !lowerLayers.isEmpty() || !upperLayers.isEmpty();
        ItemStack sel = printed ? printedSelection() : decomposed ? previewSource() : selectionPreview();
        if (sel.isEmpty()) return;
        int x = leftPos + main.x, y = topPos + main.y;
        g.renderItem(sel, x, y);
        boolean owned = printed || decomposed || (selectedMain != null && GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain));
        if (!owned) overlay(g, x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    private void drawMainSlotRings(GuiGraphics g) {
        Slot s1 = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        Slot s2 = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        border(g, leftPos + s1.x - 1, topPos + s1.y - 1, 18, 18, RING_MAIN);
        border(g, leftPos + s2.x - 1, topPos + s2.y - 1, 18, 18, RING_DONOR);
    }

    private void drawDonorPreview(GuiGraphics g) {
        Slot s = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        if (s.hasItem() || selectedDonor.isEmpty()) return;
        int x = leftPos + s.x, y = topPos + s.y;
        g.renderItem(selectedDonor, x, y);
        if (!donorOwned()) overlay(g, x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    // ── Layer strip ──────────────────────────────────────────────────────────────

    private record Chip(int x, int kind, int index) {}

    private List<Chip> layerChips() {
        List<Chip> chips = new ArrayList<>();
        int i = 0;
        for (int k = 0; k < lowerLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 0, k));
        if (activeTrim().getItem() instanceof GlintTrimItem) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 1, 0));
        for (int k = 0; k < upperLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 2, k));
        if (i < MAX_LAYERS) chips.add(new Chip(LAYER_STRIP_X + i * LAYER_CELL, 3, 0));
        return chips;
    }

    private ItemStack layerIcon(CustomGlint.Layer l) {
        ItemStack cached = layerIconCache.get(l);
        if (cached != null) return cached;
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, l.design());
        CustomGlint.write(s, new CustomGlint.Layer[]{ l });
        layerIconCache.put(l, s);
        return s;
    }

    private ItemStack activeLayerIcon() {
        if (frameMemo && frameActiveIcon != null) return frameActiveIcon;
        CustomGlint.Layer active = captureActive();
        ItemStack result;
        if (active == null) {
            result = activeTrim();
        } else {
            result = new ItemStack(ModItems.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, active.design());
            CustomGlint.write(result, new CustomGlint.Layer[]{ active });
        }
        if (frameMemo) frameActiveIcon = result;
        return result;
    }

    private boolean layerStripVisible() {
        return layerTearCount() > 0 || !lowerLayers.isEmpty() || !upperLayers.isEmpty();
    }

    private void drawLayerStrip(GuiGraphics g, int mx, int my) {
        if (!layerStripVisible()) return;
        for (Chip c : layerChips()) {
            int x = leftPos + c.x(), y = topPos + LAYER_STRIP_Y;
            if (c.kind() == 3) {
                boolean ok = canAddLayer();
                boolean hover = ok && mx >= x && mx < x + LAYER_ICON && my >= y && my < y + LAYER_ICON;
                raisedPanel(g, x, y, LAYER_ICON, LAYER_ICON, hover ? BTN_HOVER : (ok ? GUI_FACE : BTN_DISABLED));
                centered(g, "+", x + LAYER_ICON / 2, y + 2, ok ? LABEL_HDR : COST_BAD);
                continue;
            }
            g.fill(x, y, x + LAYER_ICON, y + LAYER_ICON, SLOT_DARK);
            ItemStack icon = c.kind() == 0 ? layerIcon(lowerLayers.get(c.index()))
                    : c.kind() == 2 ? layerIcon(upperLayers.get(c.index()))
                    : activeLayerIcon();
            drawScaledIcon(g, icon, x, y, LAYER_ICON);
            shardBevel(g, x, y, LAYER_ICON);
            if (c.kind() == 1) border(g, x, y, LAYER_ICON, LAYER_ICON, RING_MAIN);
            if (mx >= x && mx < x + LAYER_ICON && my >= y && my < y + LAYER_ICON)
                overlay(g, x, y, x + LAYER_ICON, y + LAYER_ICON, HOVER_TINT);
        }
    }

    // ── Color-shard strip ────────────────────────────────────────────────────────

    private void drawColorStrip(GuiGraphics g, int mx, int my) {
        int n = Math.min(colorShards.size(), 8);
        for (int k = 0; k < n; k++) {
            int x = leftPos + COLOR_STRIP_X + k * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            List<Integer> shard = colorShards.get(k);
            int rgb = mixRgb(shard);
            if (rgb < 0 && isCustomShard(shard)) {
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, SLOT_DARK);
                drawScaledIcon(g, ModItems.RAINBOW_DYE.get().getDefaultInstance(), x, y, COLOR_ICON);
            } else {
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, rgb < 0 ? COLOR_UNSET : 0xFF000000 | rgb);
            }
            shardBevel(g, x, y, COLOR_ICON);
            if (k == selectedColorIdx) border(g, x, y, COLOR_ICON, COLOR_ICON, RING_MAIN);
            if (mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON)
                overlay(g, x, y, x + COLOR_ICON, y + COLOR_ICON, HOVER_TINT);
        }
        if (n < 8 && !hexOpen) {
            int x = leftPos + COLOR_STRIP_X + n * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            boolean hover = mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON;
            raisedPanel(g, x, y, COLOR_ICON, COLOR_ICON, hover ? BTN_HOVER : GUI_FACE);
            centered(g, "+", x + COLOR_ICON / 2, y + 2, LABEL_HDR);
        }
    }

    private boolean colorStripClick(double mx, double my, int button) {
        int y = topPos + COLOR_STRIP_Y;
        int n = Math.min(colorShards.size(), 8);
        for (int k = 0; k < n; k++) {
            int x = leftPos + COLOR_STRIP_X + k * COLOR_CELL;
            if (mx < x || mx >= x + COLOR_ICON || my < y || my >= y + COLOR_ICON) continue;
            if (button == 1) {
                colorShards.remove(k);
                if (selectedColorIdx == k) selectedColorIdx = -1;
                else if (selectedColorIdx > k) selectedColorIdx--;
                closeHex();
            } else if (selectedColorIdx == k) {
                if (isCustomShard(colorShards.get(k))) { if (hexOpen) closeHex(); else openHex(k); }
            } else {
                selectedColorIdx = k;
                closeHex();
            }
            return true;
        }
        if (n < 8 && !hexOpen) {
            int x = leftPos + COLOR_STRIP_X + n * COLOR_CELL;
            if (mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON) {
                if (button == 0) { colorShards.add(new ArrayList<>()); selectedColorIdx = n; closeHex(); }
                return true;
            }
        }
        return false;
    }

    private void openHex(int k) {
        hexMode = HEX_SHARD;
        hexShardIdx = k;
        int v = colorShards.get(k).get(0);
        openHexWith((v & CUSTOM_FLAG) != 0 ? String.format("%06X", v & 0xFFFFFF) : "");
    }

    private void openHexColor(int mode) {
        hexMode = mode;
        int cur = mode == HEX_GLOW ? glowHex : nameHex;
        openHexWith(cur >= 0 ? String.format("%06X", cur) : "");
    }

    private void openHexWith(String val) {
        hexOpen = true;
        hexBox.setValue(val);
        hexBox.setFocused(true);
        setFocused(hexBox);
    }

    private void closeHex() {
        hexOpen = false;
        hexShardIdx = -1;
        hexMode = HEX_SHARD;
        if (hexBox != null) hexBox.setFocused(false);
    }

    private void applyHex(String s) {
        if (!hexOpen) return;
        String hex = s.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 6) return;
        int rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
        switch (hexMode) {
            case HEX_GLOW -> glowHex = rgb;
            case HEX_NAME -> nameHex = rgb;
            default -> {
                if (hexShardIdx < 0 || hexShardIdx >= colorShards.size()) return;
                List<Integer> shard = colorShards.get(hexShardIdx);
                shard.clear();
                shard.add(CUSTOM_FLAG | rgb);
            }
        }
    }

    private boolean rainbowInSlot(int slotConst) {
        return menu.slots.get(slotConst).getItem().getItem() == ModItems.RAINBOW_DYE.get();
    }

    /** The {@code +} layer shard is addable (white) whenever a trim is in the merge slot AND stacking it would
     *  not push past {@link #MAX_LAYERS}. Ownership and layer-tear cost are NOT gated here — they are preview-
     *  free like {@link #computePreviewSource()}, and an unowned / tear-short stack is still blocked at print. */
    private boolean canAddLayer() {
        CustomGlint.Layer[] dl = donorLayers();
        return dl.length > 0 && totalLayers() + dl.length <= MAX_LAYERS;
    }

    private CustomGlint.Layer[] donorLayers() {
        ItemStack d = donorStack();
        if (!(d.getItem() instanceof GlintTrimItem)) return new CustomGlint.Layer[0];
        CustomGlint.Data dd = CustomGlint.read(d);
        if (dd != null && dd.layers().length > 0) return dd.layers();
        ResourceLocation design = GlintTrimItem.getPattern(d);
        if (design == null) return new CustomGlint.Layer[0];
        return new CustomGlint.Layer[]{ new CustomGlint.Layer(design, GlintTrimItem.getColors(d),
                GlintTrimItem.getSpeed(d), true, GlintTrimItem.getScale(d), false,
                GlintTrimItem.getScrollDir(d), GlintTrimItem.getScrollOffset(d)) };
    }

    private void addLayer() {
        CustomGlint.Layer[] dl = donorLayers();
        if (dl.length == 0 || totalLayers() + dl.length > MAX_LAYERS) return;
        Collections.addAll(upperLayers, dl);
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
    }

    private void editLayer(int kind, int k) {
        CustomGlint.Layer cur = captureActive();
        if (kind == 0) {
            CustomGlint.Layer clicked = lowerLayers.get(k);
            List<CustomGlint.Layer> head = new ArrayList<>(lowerLayers.subList(0, k));
            List<CustomGlint.Layer> tail = new ArrayList<>(lowerLayers.subList(k + 1, lowerLayers.size()));
            List<CustomGlint.Layer> newUpper = new ArrayList<>(tail);
            if (cur != null) newUpper.add(cur);
            newUpper.addAll(upperLayers);
            lowerLayers.clear(); lowerLayers.addAll(head);
            upperLayers.clear(); upperLayers.addAll(newUpper);
            loadControlsFromLayer(clicked);
        } else if (kind == 2) {
            CustomGlint.Layer clicked = upperLayers.get(k);
            List<CustomGlint.Layer> head = new ArrayList<>(upperLayers.subList(0, k));
            List<CustomGlint.Layer> tail = new ArrayList<>(upperLayers.subList(k + 1, upperLayers.size()));
            List<CustomGlint.Layer> newLower = new ArrayList<>(lowerLayers);
            if (cur != null) newLower.add(cur);
            newLower.addAll(head);
            lowerLayers.clear(); lowerLayers.addAll(newLower);
            upperLayers.clear(); upperLayers.addAll(tail);
            loadControlsFromLayer(clicked);
        }
    }

    private void drawPreview(GuiGraphics g) {
        int bx = leftPos + PREVIEW_X, by = topPos + PREVIEW_Y;
        ItemStack src = previewSource();
        if (src.isEmpty()) return;
        // Mirror the wand editor's working GUI-glint path: scissor to the box + z 200 so the custom glint
        // foil (which depth-tests EQUAL) renders on top instead of being culled at a lower stratum.
        g.enableScissor(bx, by, bx + PREVIEW_W, by + PREVIEW_H);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(bx + PREVIEW_W / 2f, by + PREVIEW_H / 2f, 200);
        float scale = (Math.min(PREVIEW_W, PREVIEW_H) - 2) / 16f;
        pose.scale(scale, scale, 1f);
        g.renderItem(src, -8, -8);
        pose.popPose();
        g.disableScissor();
    }

    private DyeColor dyeIn(int slotConst) {
        return dyeOf(menu.slots.get(slotConst).getItem());
    }

    private int slotColor(int slotConst, int customHex) {
        if (rainbowInSlot(slotConst)) return customHex;
        DyeColor d = dyeIn(slotConst);
        return d != null ? dyeRgb(d.ordinal()) : -1;
    }

    private void drawDyeSelection(GuiGraphics g) {
        if (selectedColorIdx < 0 || selectedColorIdx >= colorShards.size()) return;
        for (int idx : colorShards.get(selectedColorIdx)) {
            Slot s = (idx == RAINBOW || (idx & CUSTOM_FLAG) != 0)
                    ? menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE)
                    : menu.slots.get(GlintTableMenu.SLOT_DYE_START + idx);
            border(g, leftPos + s.x - 1, topPos + s.y - 1, 18, 18, RING_MAIN);
        }
    }

    private boolean glowTrimMain() {
        return previewSource().getItem() instanceof GlowTrimItem;
    }

    private void drawNameGlowSection(GuiGraphics g) {
        if (!modNamed) {
            int nx = leftPos + NAME_BOX_X, ny = topPos + NAME_BOX_Y;
            raisedPanel(g, nx, ny, NAME_BOX_W, 12, GUI_FACE);
            var pose = g.pose();
            pose.pushPose();
            pose.translate(nx + NAME_BOX_W / 2f, ny + 3f, 0);
            pose.scale(0.85f, 0.85f, 1f);
            centered(g, Component.translatable("screen.customglint.glint_table.not_available").getString(), 0, 0, COST_BAD);
            pose.popPose();
        }
    }

    private boolean speedTuned() { return Math.abs(modSpeed - 1.0f) > 0.001f; }
    private boolean scaleTuned() { return Math.abs(modScale - 1.0f) > 0.001f; }

    private int[] layerCosts() {
        int red = speedTuned() ? 1 : 0, slime = scaleTuned() ? 1 : 0, glass = modOpacity > 0 ? 1 : 0;
        for (CustomGlint.Layer l : lowerLayers) { red += layerTunedSpeed(l); slime += layerTunedScale(l); glass += layerTranslucent(l); }
        for (CustomGlint.Layer l : upperLayers) { red += layerTunedSpeed(l); slime += layerTunedScale(l); glass += layerTranslucent(l); }
        return new int[]{red, slime, glass};
    }
    private static int layerTunedSpeed(CustomGlint.Layer l) { return Math.abs(l.speed() - 1.0f) > 0.001f ? 1 : 0; }
    private static int layerTunedScale(CustomGlint.Layer l) { return Math.abs(l.patternScale() - 1.0f) > 0.001f ? 1 : 0; }
    private static int layerTranslucent(CustomGlint.Layer l) { int[] c = l.colors(); return (c.length > 0 && ((c[0] >>> 24) & 0xFF) < 255) ? 1 : 0; }

    private int committedSimLayers() {
        int n = 0;
        for (CustomGlint.Layer l : lowerLayers) if (l.simultaneous()) n++;
        for (CustomGlint.Layer l : upperLayers) if (l.simultaneous()) n++;
        return n;
    }

    private int simTearCost() {
        int committed = committedSimLayers();
        int avail = menu.slots.get(GlintTableMenu.SLOT_TEAR).getItem().getCount();
        return committed + (tearSimultaneous && avail > committed ? 1 : 0);
    }

    private boolean canPrint() {
        if (frameMemo && frameCanPrint != null) return frameCanPrint;
        boolean result = computeCanPrint();
        if (frameMemo) frameCanPrint = result;
        return result;
    }

    private boolean computeCanPrint() {
        ItemStack src = activeTrim();
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem) || GlintTrimItem.getPattern(src) == null) return false;
        if (totalLayers() > MAX_LAYERS) return false;
        if (layerTearCount() < totalLayers() - 1) return false;
        for (CustomGlint.Layer l : lowerLayers) if (!layerValid(l)) return false;
        for (CustomGlint.Layer l : upperLayers) if (!layerValid(l)) return false;

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;
        if (!fromBase && selectedPrinted.isEmpty()
                && (selectedMain == null || !GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain))) return false;
        if (!donorOwned()) return false;

        int baseColorCount = fromBase ? GlintTrimItem.getColors(base).length : 0;
        if (baseColorCount == 0 && countSelectedDyes() == 0) return false;
        int mainCount = fromBase ? baseColorCount : countSelectedDyes();
        if (mainCount + donorColors().length > 8) return false;

        ItemStack repro = reproSource();
        if (!repro.isEmpty()) {
            CustomGlint.Data d = CustomGlint.read(repro);
            if (d != null && d.layers().length > 1) return false;
            if (d != null && d.layers().length == 1
                    && d.layers()[0].simultaneous() && d.layers()[0].colors().length >= 2
                    && tearSimultaneous && !menu.slots.get(GlintTableMenu.SLOT_TEAR).hasItem()) return false;
        }
        if (menu.slots.get(GlintTableMenu.SLOT_TEAR).getItem().getCount() < committedSimLayers()) return false;
        if (activeSourceSim && !tearSimultaneous && !menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).hasItem()) return false;

        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        boolean baseNamed = fromBase && base.hasCustomHoverName();
        int[] cost = layerCosts();
        if (menu.slots.get(GlintTableMenu.SLOT_REDSTONE).getItem().getCount() < cost[0]) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_SLIME).getItem().getCount() < cost[1]) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_GLASS).getItem().getCount() < cost[2]) return false;
        if (modGlow && !baseGlowing && !menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE).hasItem()) return false;
        if (modNamed && !baseNamed && !menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem()) return false;
        if (modGlow && !glowAuto && slotColor(GlintTableMenu.SLOT_GLOW_DYE, glowHex) < 0 && !baseHasGlowColors) return false;
        return true;
    }

    private List<Component> printIssues() {
        List<Component> out = new ArrayList<>();
        ItemStack src = activeTrim();
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem) || GlintTrimItem.getPattern(src) == null) {
            out.add(Component.translatable("screen.customglint.glint_table.issue.pick_design"));
            return out;
        }
        if (totalLayers() > MAX_LAYERS) out.add(Component.translatable("screen.customglint.glint_table.issue.too_many_layers", MAX_LAYERS));
        if (layerTearCount() < totalLayers() - 1) out.add(Component.translatable("screen.customglint.glint_table.issue.need_layer_tear"));
        boolean badLayer = false;
        for (CustomGlint.Layer l : lowerLayers) if (!layerValid(l)) badLayer = true;
        for (CustomGlint.Layer l : upperLayers) if (!layerValid(l)) badLayer = true;
        if (badLayer) out.add(Component.translatable("screen.customglint.glint_table.issue.bad_layer"));

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;
        if (!fromBase && selectedPrinted.isEmpty()
                && (selectedMain == null || !GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain)))
            out.add(Component.translatable("screen.customglint.glint_table.issue.design_not_owned"));
        if (!donorOwned()) out.add(Component.translatable("screen.customglint.glint_table.issue.donor_not_owned"));

        int baseColorCount = fromBase ? GlintTrimItem.getColors(base).length : 0;
        if (baseColorCount == 0 && countSelectedDyes() == 0) out.add(Component.translatable("screen.customglint.glint_table.issue.add_color"));
        int mainCount = fromBase ? baseColorCount : countSelectedDyes();
        if (mainCount + donorColors().length > 8) out.add(Component.translatable("screen.customglint.glint_table.issue.too_many_colors"));

        ItemStack repro = reproSource();
        if (!repro.isEmpty()) {
            CustomGlint.Data d = CustomGlint.read(repro);
            if (d != null && d.layers().length > 1) out.add(Component.translatable("screen.customglint.glint_table.issue.no_multilayer"));
            else if (d != null && d.layers().length == 1 && d.layers()[0].simultaneous()
                    && d.layers()[0].colors().length >= 2
                    && tearSimultaneous && !menu.slots.get(GlintTableMenu.SLOT_TEAR).hasItem())
                out.add(Component.translatable("screen.customglint.glint_table.issue.need_sim_tear_color"));
        }
        if (activeSourceSim && !tearSimultaneous && !menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).hasItem())
            out.add(Component.translatable("screen.customglint.glint_table.issue.need_seq_tear",
                    itemName(ModItems.GLINT_TEAR_SEQUENTIAL.get())));
        int simNeed = committedSimLayers();
        if (menu.slots.get(GlintTableMenu.SLOT_TEAR).getItem().getCount() < simNeed)
            out.add(Component.translatable("screen.customglint.glint_table.issue.need_sim_tears",
                    itemName(ModItems.GLINT_TEAR_SIMULTANEOUS.get()), simNeed));

        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        boolean baseNamed = fromBase && base.hasCustomHoverName();
        int[] cost = layerCosts();
        if (menu.slots.get(GlintTableMenu.SLOT_REDSTONE).getItem().getCount() < cost[0])
            out.add(Component.translatable("screen.customglint.glint_table.issue.speed_needs", itemName(Items.REDSTONE), cost[0]));
        if (menu.slots.get(GlintTableMenu.SLOT_SLIME).getItem().getCount() < cost[1])
            out.add(Component.translatable("screen.customglint.glint_table.issue.scale_needs", itemName(Items.SLIME_BALL), cost[1]));
        if (menu.slots.get(GlintTableMenu.SLOT_GLASS).getItem().getCount() < cost[2])
            out.add(Component.translatable("screen.customglint.glint_table.issue.opacity_needs", itemName(Items.GLASS), cost[2]));
        if (modGlow && !baseGlowing && !menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE).hasItem())
            out.add(Component.translatable("screen.customglint.glint_table.issue.glow_needs", itemName(Items.GLOWSTONE_DUST)));
        if (modNamed && !baseNamed && !menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem())
            out.add(Component.translatable("screen.customglint.glint_table.issue.name_needs", itemName(Items.NAME_TAG)));
        if (modGlow && !glowAuto && slotColor(GlintTableMenu.SLOT_GLOW_DYE, glowHex) < 0 && !baseHasGlowColors)
            out.add(Component.translatable("screen.customglint.glint_table.issue.glow_manual_needs"));
        return out;
    }

    private static Component itemName(Item item) {
        return new ItemStack(item).getHoverName();
    }

    private List<Component> printTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.customglint.glint_table.print").withStyle(ChatFormatting.WHITE));
        List<Component> issues = printIssues();
        if (!issues.isEmpty()) {
            lines.add(Component.translatable("screen.customglint.glint_table.cant_print").withStyle(ChatFormatting.RED));
            for (Component s : issues) lines.add(Component.literal("• ").append(s).withStyle(ChatFormatting.RED));
            return lines;
        }
        lines.add(Component.translatable("screen.customglint.glint_table.consumes").withStyle(ChatFormatting.GRAY));
        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;
        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean any = false;
        int[] cost = layerCosts();
        if (cost[0] > 0)             { reqLine(lines, itemName(Items.REDSTONE), cost[0], GlintTableMenu.SLOT_REDSTONE); any = true; }
        if (cost[1] > 0)             { reqLine(lines, itemName(Items.SLIME_BALL), cost[1], GlintTableMenu.SLOT_SLIME); any = true; }
        if (cost[2] > 0)             { reqLine(lines, itemName(Items.GLASS), cost[2], GlintTableMenu.SLOT_GLASS); any = true; }
        if (modGlow && !baseGlowing) { reqLine(lines, itemName(Items.GLOWSTONE_DUST), 1, GlintTableMenu.SLOT_GLOWSTONE); any = true; }
        int extraLayers = lowerLayers.size() + upperLayers.size();
        if (extraLayers > 0)         { reqLine(lines, itemName(ModItems.GLINT_LAYER_TEAR.get()), extraLayers, GlintTableMenu.SLOT_LAYER_TEAR); any = true; }
        int simTears = simTearCost();
        if (simTears > 0)            { reqLine(lines, itemName(ModItems.GLINT_TEAR_SIMULTANEOUS.get()), simTears, GlintTableMenu.SLOT_TEAR); any = true; }
        if (activeSourceSim && !tearSimultaneous) { reqLine(lines, itemName(ModItems.GLINT_TEAR_SEQUENTIAL.get()), 1, GlintTableMenu.SLOT_TEAR_SEQ); any = true; }
        if (!any) lines.add(Component.translatable("screen.customglint.glint_table.nothing").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private void reqLine(List<Component> lines, Component name, int need, int slotConst) {
        int have = menu.slots.get(slotConst).getItem().getCount();
        lines.add(Component.translatable("screen.customglint.glint_table.consume_line", name, need)
                .withStyle(have >= need ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    // ── Beveled widgets ───────────────────────────────────────────────────────────

    private final class BevelButton extends AbstractWidget {
        private final Supplier<String> label;
        private final IntSupplier textColor;
        private final IntSupplier faceColor;
        private final int textDy;
        private final IntConsumer onPress;
        private final boolean rightToo;

        BevelButton(int x, int y, int w, int h, int textDy, boolean rightToo,
                    Supplier<String> label, IntSupplier textColor, IntSupplier faceColor, IntConsumer onPress) {
            super(x, y, w, h, Component.empty());
            this.label = label; this.textColor = textColor; this.faceColor = faceColor;
            this.textDy = textDy; this.onPress = onPress; this.rightToo = rightToo;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float a) {
            int face = (active && isHovered()) ? BTN_HOVER : faceColor.getAsInt();
            raisedPanel(g, getX(), getY(), getWidth(), getHeight(), face);
            centered(g, label.get(), getX() + getWidth() / 2, getY() + textDy, textColor.getAsInt());
        }

        @Override
        public boolean mouseClicked(double mxd, double myd, int button) {
            if (!active || !visible) return false;
            if (button != 0 && !(rightToo && button == 1)) return false;
            if (!isMouseOver(mxd, myd)) return false;
            playDownSound(Minecraft.getInstance().getSoundManager());
            onPress.accept(button);
            return true;
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            if (GlintGuiConfig.sound()) super.playDownSound(soundManager);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, Component.literal(label.get()));
        }
    }

    private void addButtons() {
        Slot glow = menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE);
        Slot nameS = menu.slots.get(GlintTableMenu.SLOT_NAMETAG);
        Slot tear = menu.slots.get(GlintTableMenu.SLOT_TEAR);

        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X, topPos + SKIN_BTN_Y, SKIN_BTN_W, SKIN_BTN_H, 2, true,
                () -> Component.translatable("screen.customglint.skin." + skin.name.toLowerCase(Locale.ROOT)).getString(),
                () -> LABEL_HDR, () -> GUI_FACE, b -> cycleSkin(b == 1 ? -1 : 1)));

        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X - SND_BTN_W - 2, topPos + SKIN_BTN_Y, SND_BTN_W, SKIN_BTN_H, 2, false,
                () -> "♪", () -> GlintGuiConfig.sound() ? COST_OK : COST_BAD, () -> GUI_FACE,
                b -> GlintGuiConfig.setSound(!GlintGuiConfig.sound())));

        printBtn = addRenderableWidget(new BevelButton(leftPos + PRINT_X, topPos + PRINT_Y, PRINT_W, PRINT_H, 3, false,
                () -> Component.translatable("screen.customglint.glint_table.print").getString(),
                () -> canPrint() ? LABEL_HDR : COST_BAD, () -> canPrint() ? GUI_FACE : BTN_DISABLED,
                b -> { if (canPrint()) onPrint(); }));

        addRenderableWidget(new BevelButton(leftPos + INTERP_X, topPos + INTERP_Y, INTERP_W, INTERP_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.interpolation", boolLabel(modInterpolate)).getString(),
                () -> modInterpolate ? COST_OK : LABEL_HDR, () -> GUI_FACE, b -> modInterpolate = !modInterpolate));

        addRenderableWidget(new BevelButton(leftPos + SCROLL_X, topPos + SCROLL_Y, SCROLL_W, SCROLL_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.scroll", GlintTrimItem.scrollLabel(modScrollDir)).getString(),
                () -> LABEL_HDR, () -> GUI_FACE, b -> modScrollDir = (modScrollDir + 1) % 9));

        addRenderableWidget(new BevelButton(tearToggleCx() - 15, topPos + tear.y + 26, 30, 11, 2, false,
                () -> Component.translatable(tearSimultaneous ? "screen.customglint.glint_table.sim" : "screen.customglint.glint_table.seq").getString(),
                () -> LABEL_HDR, () -> GUI_FACE, b -> tearSimultaneous = !tearSimultaneous));

        glowModeBtn = addRenderableWidget(new BevelButton(leftPos + GLOW_MODE_X, topPos + GLOW_MODE_Y, GLOW_MODE_W, GLOW_MODE_H, 2, false,
                this::glowModeLabel, this::glowModeColor, () -> GUI_FACE, b -> glowAuto = !glowAuto));

        glowToggleBtn = addRenderableWidget(new BevelButton(leftPos + glow.x - 7, topPos + glow.y + 26, 30, 11, 2, false,
                () -> toggleAvailable(GlintTableMenu.SLOT_GLOWSTONE) ? boolLabel(modGlow).getString() : naLabel(),
                () -> !toggleAvailable(GlintTableMenu.SLOT_GLOWSTONE) ? COST_BAD : (modGlow ? COST_OK : LABEL_HDR),
                () -> GUI_FACE, b -> modGlow = !modGlow));

        nameToggleBtn = addRenderableWidget(new BevelButton(leftPos + nameS.x - 7, topPos + nameS.y + 26, 30, 11, 2, false,
                () -> toggleAvailable(GlintTableMenu.SLOT_NAMETAG) ? boolLabel(modNamed).getString() : naLabel(),
                () -> !toggleAvailable(GlintTableMenu.SLOT_NAMETAG) ? COST_BAD : (modNamed ? COST_OK : LABEL_HDR),
                () -> GUI_FACE, b -> modNamed = !modNamed));

        addStepperPair(GlintTableMenu.SLOT_REDSTONE, () -> modSpeed = stepDown(modSpeed), () -> modSpeed = stepUp(modSpeed));
        addStepperPair(GlintTableMenu.SLOT_GLASS, () -> modOpacity = Math.max(0, modOpacity - 1), () -> modOpacity = Math.min(8, modOpacity + 1));
        addStepperPair(GlintTableMenu.SLOT_SLIME, () -> modScale = stepDown(modScale), () -> modScale = stepUp(modScale));

        int ox = leftPos + SCROLL_X + SCROLL_W / 2, oy = topPos + SCROLL_OFF_Y;
        offsetMinus = stepper(ox - 15, oy, "-", () -> modScrollOffset = Math.max(0.0f, Math.round((modScrollOffset - 0.05f) * 20) / 20.0f));
        offsetPlus  = stepper(ox + 6,  oy, "+", () -> modScrollOffset = Math.min(1.0f, Math.round((modScrollOffset + 0.05f) * 20) / 20.0f));
    }

    private void addStepperPair(int slotConst, Runnable minus, Runnable plus) {
        Slot s = menu.slots.get(slotConst);
        int cx = leftPos + s.x + 8, by = topPos + s.y + 26;
        stepper(cx - 15, by, "-", minus);
        stepper(cx + 6, by, "+", plus);
    }

    private BevelButton stepper(int bx, int by, String label, Runnable onPress) {
        return addRenderableWidget(new BevelButton(bx, by, 9, 9, 1, false,
                () -> label, () -> LABEL_HDR, () -> GUI_FACE, b -> onPress.run()));
    }

    private void syncButtons() {
        printBtn.active = canPrint();
        glowModeBtn.active = modGlow && !glowTrimMain();
        glowToggleBtn.active = toggleAvailable(GlintTableMenu.SLOT_GLOWSTONE);
        nameToggleBtn.active = toggleAvailable(GlintTableMenu.SLOT_NAMETAG);
        offsetMinus.visible = offsetPlus.visible = (modScrollDir == CustomGlint.SCROLL_STATIC);
    }

    private String glowModeLabel() {
        if (glowTrimMain()) return naLabel();
        if (!modGlow) return Component.translatable("screen.customglint.glint_table.glow_off").getString();
        return Component.translatable(glowAuto
                ? "screen.customglint.glint_table.glow_auto" : "screen.customglint.glint_table.glow_manual").getString();
    }

    private static Component boolLabel(boolean value) {
        return Component.translatable(value ? "screen.customglint.glint_table.true" : "screen.customglint.glint_table.false");
    }

    private static String naLabel() {
        return Component.translatable("screen.customglint.glint_table.na").getString();
    }

    private static String label(String suffix) {
        return Component.translatable("screen.customglint.glint_table." + suffix).getString();
    }

    private int glowModeColor() {
        return (glowTrimMain() || !modGlow) ? COST_BAD : LABEL_HDR;
    }

    private void centered(GuiGraphics g, String str, int cx, int y, int color) {
        g.drawString(font, str, cx - font.width(str) / 2, y, 0xFF000000 | color, false);
    }

    /** Render an item icon scaled to fit a {@code size}px cell at (x, y), mirroring the (working) preview's
     *  render structure — centered translate at a raised z, then {@code scale}, then {@code renderItem(-8,-8)}
     *  so the item centers on the cell. (The corner-translate form did not honour the pose scale here.)
     *  A 1px inset keeps the icon clear of the shard bevel so it doesn't read as spilling past the frame. */
    private void drawScaledIcon(GuiGraphics g, ItemStack icon, int x, int y, int size) {
        g.enableScissor(x, y, x + size, y + size);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + size / 2f, y + size / 2f, 200);
        float s = (size - 2) / 16f;
        pose.scale(s, s, 1f);
        g.renderItem(icon, -8, -8);
        pose.popPose();
        g.disableScissor();
    }

    /** A fill drawn above GUI items. Icons render at z≈150, so a plain {@code g.fill} (z 0) for a dim / hover
     *  overlay lands behind the icon and never shows; this raises it so the overlay sits on top. */
    private void overlay(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 240);
        g.fill(x0, y0, x1, y1, color);
        g.pose().popPose();
    }

    private void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void raisedPanel(GuiGraphics g, int x, int y, int w, int h, int face) {
        skin.raised(g, x, y, w, h, face);
    }

    private void shardBevel(GuiGraphics g, int x, int y, int s) {
        skin.shardBevel(g, x, y, s);
    }

    private void slotWell(GuiGraphics g, int sx, int sy) {
        skin.slotWell(g, sx, sy);
    }

    private String modValue(int slotConst) {
        return switch (slotConst) {
            case GlintTableMenu.SLOT_REDSTONE -> fmtVal(modSpeed) + "×";
            case GlintTableMenu.SLOT_GLASS    -> Math.round(modOpacity * 100f / 8f) + "%";
            case GlintTableMenu.SLOT_SLIME    -> fmtVal(modScale) + "×";
            default -> "";
        };
    }

    private void drawModifierControls(GuiGraphics g) {
        drawModControl(g, GlintTableMenu.SLOT_REDSTONE, label("speed"));
        drawModControl(g, GlintTableMenu.SLOT_GLASS,    label("opacity"));
        drawModControl(g, GlintTableMenu.SLOT_SLIME,    label("scale"));
        drawControlLabel(g, GlintTableMenu.SLOT_GLOWSTONE, label("glow"));
        drawControlLabel(g, GlintTableMenu.SLOT_NAMETAG,   label("name_label"));
        drawTearLabel(g);
        if (modScrollDir == CustomGlint.SCROLL_STATIC) {
            fitValue(g, String.format("%.2f", modScrollOffset), leftPos + SCROLL_X + SCROLL_W / 2, topPos + SCROLL_OFF_Y + 1);
        }
    }

    private void fitValue(GuiGraphics g, String val, int cx, int y) {
        int w = font.width(val);
        float vs = w > 0 ? Math.min(0.8f, 11f / w) : 0.8f;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, y, 0);
        pose.scale(vs, vs, 1f);
        centered(g, val, 0, 0, LABEL_HDR);
        pose.popPose();
    }

    private int activeTearSlot() {
        return tearSimultaneous ? GlintTableMenu.SLOT_TEAR : GlintTableMenu.SLOT_TEAR_SEQ;
    }

    private int tearToggleCx() {
        return (leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).x + 8
                + leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).x + 8) / 2;
    }

    private boolean toggleAvailable(int slotConst) {
        if (menu.slots.get(slotConst).hasItem()) return true;
        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!(base.getItem() instanceof GlintTrimItem)) return false;
        if (slotConst == GlintTableMenu.SLOT_GLOWSTONE) return CustomGlint.isGlowing(base);
        if (slotConst == GlintTableMenu.SLOT_NAMETAG)   return base.hasCustomHoverName();
        return false;
    }

    private void drawControlLabel(GuiGraphics g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    private void drawTearLabel(GuiGraphics g) {
        int cx = tearToggleCx(), top = topPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).y;
        smallLabel(g, label("type"), cx, top + 19, LABEL_HDR);
        Slot off = menu.slots.get(tearSimultaneous ? GlintTableMenu.SLOT_TEAR_SEQ : GlintTableMenu.SLOT_TEAR);
        int dx = leftPos + off.x - 1, dy = topPos + off.y - 1;
        overlay(g, dx, dy, dx + 18, dy + 18, DIM_GHOST);
    }

    private void drawModControl(GuiGraphics g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        int cx = leftPos + s.x + 8, top = topPos + s.y;
        smallLabel(g, label, cx, top + 19, LABEL_HDR);
        fitValue(g, modValue(slotConst), cx, top + 27);
    }

    private void drawSectionLabels(GuiGraphics g) {
        int x = leftPos, y = topPos;
        g.drawString(font, label("empty_trims"), x + LGRID_X, y + GRID_Y - 11, 0xFF000000 | LABEL_HDR, false);
        g.drawString(font, label("printed"),     x + RGRID_X, y + GRID_Y - 11, 0xFF000000 | LABEL_HDR, false);
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM,       label("main"));
        topSlotLabel(g, GlintTableMenu.SLOT_LAYER_TEAR, label("layer"));
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM_B,     label("merge"));
    }

    private void topSlotLabel(GuiGraphics g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    private void smallLabel(GuiGraphics g, String label, int cx, int y, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, y, 0);
        pose.scale(0.7f, 0.7f, 1f);
        centered(g, label, 0, 0, color);
        pose.popPose();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private static boolean isTrim(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem || stack.getItem() instanceof GlowTrimItem;
    }

    private boolean overEitherGrid(double mx, double my) {
        return overGrid(mx, my, LGRID_X) || overGrid(mx, my, RGRID_X);
    }

    private boolean overGrid(double mx, double my, int gx) {
        int x0 = leftPos + gx, y0 = topPos + GRID_Y;
        return mx >= x0 && mx < x0 + GRID_COLS * CELL && my >= y0 && my < y0 + GRID_ROWS * CELL;
    }

    private boolean overSlot(double mx, double my, int slotConst) {
        Slot s = menu.slots.get(slotConst);
        int sx = leftPos + s.x, sy = topPos + s.y;
        return mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16;
    }

    private int gridIndexAt(double mx, double my, int gx, int gy, int scroll, int total) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int cx = gx + col * CELL, cy = gy + row * CELL;
                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) {
                    int idx = (scroll + row) * GRID_COLS + col;
                    return idx < total ? idx : -1;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = leftPos, y = topPos;

        if (hexOpen && hexBox != null && hexBox.isVisible()
                && mx >= hexBox.getX() && mx < hexBox.getX() + hexBox.getWidth()
                && my >= hexBox.getY() && my < hexBox.getY() + hexBox.getHeight()) {
            return hexBox.mouseClicked(mx, my, button);
        }

        if (button == 1 && menu.getCarried().isEmpty()) {
            if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM)) {
                if (menu.slots.get(GlintTableMenu.SLOT_TRIM).hasItem()) quickMoveSlot(GlintTableMenu.SLOT_TRIM);
                clearBuild();
                return true;
            }
            if (overSlot(mx, my, GlintTableMenu.SLOT_TRIM_B)) {
                if (menu.slots.get(GlintTableMenu.SLOT_TRIM_B).hasItem()) quickMoveSlot(GlintTableMenu.SLOT_TRIM_B);
                selectedDonor = ItemStack.EMPTY;
                selectedDonorPrinted = false;
                return true;
            }
        }

        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && isTrim(carried) && overEitherGrid(mx, my)) {
            ModNetworking.CHANNEL.sendToServer(new GlintDepositPacket());
            return true;
        }

        if (button == 1) {
            if (menu.showGlowDye && rainbowInSlot(GlintTableMenu.SLOT_GLOW_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_GLOW_DYE)) {
                if (hexOpen && hexMode == HEX_GLOW) closeHex(); else openHexColor(HEX_GLOW);
                return true;
            }
            if (menu.showNameDye && rainbowInSlot(GlintTableMenu.SLOT_NAME_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_NAME_DYE)) {
                if (hexOpen && hexMode == HEX_NAME) closeHex(); else openHexColor(HEX_NAME);
                return true;
            }
        }

        if (layerStripVisible()) {
            int cy = y + LAYER_STRIP_Y;
            for (Chip c : layerChips()) {
                int cx = x + c.x();
                if (mx < cx || mx >= cx + LAYER_ICON || my < cy || my >= cy + LAYER_ICON) continue;
                if (c.kind() == 3) { if (button == 0) addLayer(); }
                else if (c.kind() == 1) { /* active chip */ }
                else if (button == 1) {
                    if (c.kind() == 0) lowerLayers.remove(c.index()); else upperLayers.remove(c.index());
                } else editLayer(c.kind(), c.index());
                return true;
            }
        }

        if (colorStripClick(mx, my, button)) return true;

        if (button == 1) {
            boolean shardSel = selectedColorIdx >= 0 && selectedColorIdx < colorShards.size();
            for (int i = 0; i < 16; i++) {
                Slot s = menu.slots.get(GlintTableMenu.SLOT_DYE_START + i);
                int sx = x + s.x, sy = y + s.y;
                if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) {
                    if (s.hasItem() && menu.getCarried().isEmpty() && shardSel) {
                        List<Integer> shard = colorShards.get(selectedColorIdx);
                        if (isCustomShard(shard)) { shard.clear(); shard.add(i); closeHex(); }
                        else if (hasShiftDown()) { if (!shard.contains(i) && shard.size() < 8) shard.add(i); }
                        else { shard.clear(); shard.add(i); }
                    }
                    return true;
                }
            }
            Slot rs = menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE);
            int rx = x + rs.x, ry = y + rs.y;
            if (mx >= rx && mx < rx + 16 && my >= ry && my < ry + 16) {
                if (rs.hasItem() && menu.getCarried().isEmpty() && shardSel) {
                    List<Integer> shard = colorShards.get(selectedColorIdx);
                    shard.clear(); shard.add(RAINBOW);
                }
                return true;
            }
        }

        if (button == 0) {
            if (onScrollbar(mx, my, x + LGRID_X, y + GRID_Y, trims.size())) {
                draggingGrid = 0;
                gridScroll = scrollFromMouse(my, y + GRID_Y, trims.size());
                return true;
            }
            if (onScrollbar(mx, my, x + RGRID_X, y + GRID_Y, printedCapacity())) {
                draggingGrid = 1;
                printScroll = scrollFromMouse(my, y + GRID_Y, printedCapacity());
                return true;
            }
        }

        int li = gridIndexAt(mx, my, x + LGRID_X, y + GRID_Y, gridScroll, trims.size());
        if (li >= 0) {
            String name = trims.get(li);
            if (button == 0 && hasShiftDown()) {
                if (GlintStoredSyncPacket.CLIENT_STORED.contains(name))
                    ModNetworking.CHANNEL.sendToServer(new GlintGiveDesignPacket(name));
                return true;
            }
            if (button == 1) {
                boolean same = !selectedDonorPrinted && name.equals(trimDesignName(selectedDonor));
                selectedDonor = same ? ItemStack.EMPTY : trimCache.getOrDefault(name, ItemStack.EMPTY).copy();
                selectedDonorPrinted = false;
            } else {
                selectedMain = name; selectedPrinted = ItemStack.EMPTY;
            }
            return true;
        }

        int ri = gridIndexAt(mx, my, x + RGRID_X, y + GRID_Y, printScroll, GlintPrintedSyncPacket.CLIENT_PRINTED.size());
        if (ri >= 0) {
            if (button == 0 && hasShiftDown()) {
                ModNetworking.CHANNEL.sendToServer(new GlintWithdrawPacket(ri));
                return true;
            }
            ItemStack picked = GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri).copy();
            if (button == 1) {
                boolean same = selectedDonorPrinted && ItemStack.isSameItemSameTags(picked, selectedDonor);
                selectedDonor = same ? ItemStack.EMPTY : picked;
                selectedDonorPrinted = !same;
            } else {
                loadFromTrim(picked);
            }
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    private void onPrint() {
        // Commit the previewed merge donor into real stacked layers (one per layer, one tear each) so the
        // printed trim matches the stacked preview. Only fires when owned + enough tears (canAddLayer);
        // otherwise the donor stays a colour-merge fallback.
        if (canAddLayer()) addLayer();
        ItemStack src = activeTrim();
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem)) return;
        ResourceLocation design = GlintTrimItem.getPattern(src);
        if (design == null) return;
        List<int[]> shards = new ArrayList<>();
        for (List<Integer> shard : colorShards) {
            if (mixRgb(shard) >= 0) shards.add(shard.stream().mapToInt(Integer::intValue).toArray());
        }
        int[][] shardDyes = shards.toArray(new int[0][]);
        CustomGlint.Layer[] below = lowerLayers.toArray(new CustomGlint.Layer[0]);
        CustomGlint.Layer[] above = upperLayers.toArray(new CustomGlint.Layer[0]);
        ModNetworking.CHANNEL.sendToServer(new GlintPrintPacket(
                design.toString(), modSpeed, modScale, modOpacity, modGlow, glowAuto, modNamed, trimName, tearSimultaneous,
                modScrollDir, modScrollOffset, modInterpolate, glowHex, nameHex, shardDyes, below, above, activeSourceSim));
    }

    @Override
    public boolean keyPressed(int key, int sc, int mods) {
        if (key == 257 || key == 335) { // Enter / numpad Enter
            if (hexOpen) { closeHex(); setFocused(null); return true; }
            if (nameBox != null && nameBox.isFocused()) { nameBox.setFocused(false); setFocused(null); return true; }
        }
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && key != 256) {
            nameBox.keyPressed(key, sc, mods);
            return true;
        }
        if (hexBox != null && hexBox.isVisible() && hexBox.isFocused() && key != 256) {
            hexBox.keyPressed(key, sc, mods);
            return true;
        }
        return super.keyPressed(key, sc, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && nameBox.charTyped(c, mods)) return true;
        if (hexBox != null && hexBox.isVisible() && hexBox.isFocused() && hexBox.charTyped(c, mods)) return true;
        return super.charTyped(c, mods);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingGrid == 0) { gridScroll = scrollFromMouse(my, topPos + GRID_Y, trims.size()); return true; }
        if (draggingGrid == 1) { printScroll = scrollFromMouse(my, topPos + GRID_Y, printedCapacity()); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingGrid = -1;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (overGrid(mx, my, LGRID_X)) { gridScroll = scrolled(gridScroll, trims.size(), scrollY); return true; }
        if (overGrid(mx, my, RGRID_X)) { printScroll = scrolled(printScroll, printedCapacity(), scrollY); return true; }
        return super.mouseScrolled(mx, my, scrollY);
    }

    private int scrolled(int cur, int total, double dir) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        return Math.max(0, Math.min(maxRow, cur - (int) Math.signum(dir)));
    }
}
