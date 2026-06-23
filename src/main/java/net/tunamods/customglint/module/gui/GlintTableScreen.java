package net.tunamods.customglint.module.gui;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.GlintClientConfig;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.client.GlintTableModelClient;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.network.GlintDepositPacket;
import net.tunamods.customglint.module.network.GlintGiveDesignPacket;
import net.tunamods.customglint.module.network.GlintPrintPacket;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;
import net.tunamods.customglint.module.network.GlintWithdrawPacket;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class GlintTableScreen extends AbstractContainerScreen<GlintTableMenu> {

    // ── Layout (mockup): two scrollable grids flank a center column ───────────
    // 1 left grid = empty/blank design palette; 2 right grid = printed (colored/glow) trims.
    private static final int GRID_COLS = 6, GRID_ROWS = 7, CELL = 18;
    private static final int LGRID_X = 8, RGRID_X = 221, GRID_Y = 22;

    // Center column boxes. The preview is sized to fit an integer-zoomed (5×) 80px glint icon snugly, an
    // 82px box (≈1px inside the bevel). Bottom stays aligned to the grids' recess bottom (150); only the top
    // moves down. A non-integer zoom would sub-pixel-seam the trim's edge/body layers against the glow halo.
    private static final int PREVIEW_X = 129, PREVIEW_Y = 68, PREVIEW_W = 82, PREVIEW_H = 82;   // 3
    private static final int PRINT_X = 128, PRINT_Y = 165, PRINT_W = 84, PRINT_H = 14;          // 7 (centered under the preview/color strip)

    // Color-shard strip: a row of up to 8 swatches for the active layer's colors, mirroring the layer strip
    // but BELOW the preview (tops flush with the preview's bottom edge). Same x/cell metrics as the layer strip.
    private static final int COLOR_STRIP_X = 122, COLOR_CELL = 12, COLOR_ICON = 12;
    private static final int COLOR_STRIP_Y = PREVIEW_Y + PREVIEW_H; // 150, top flush with preview bottom

    // ── Active skin palette ───────────────────────────────────────────────────
    // The selected skin's colors, copied here by applySkin(). Layout is identical across skins; only these
    // values differ. The names match the original constants so all the draw code below reads unchanged.
    private GlintTableSkin skin = GlintTableSkin.DEFAULT;
    private int DIM_GHOST, DIM_PREVIEW; // dim overlays (inactive tear / dye-hint, ghost-item preview)
    private int RING_MAIN, RING_DONOR;                       // selection rings (main / merge-donor)
    private int GUI_FACE, GUI_SHADOW; // panel / bevel palette
    private int SLOT_DARK;                                   // slot-well / shard bevel shadow
    private int LABEL_HDR, COST_OK, COST_BAD;                // text + status colors
    private int COLOR_UNSET, BTN_DISABLED;                   // unset shard / disabled button face
    private int HOVER_TINT, BTN_HOVER;                       // hover tint, button hover face

    /** Copy a skin's palette into the same-named fields the draw code reads. */
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

    /** Step the skin by {@code dir} (+1 next / -1 previous, wrapping) and persist the choice. */
    private void cycleSkin(int dir) {
        int n = GlintTableSkin.ALL.length;
        int idx = Math.floorMod(GlintTableSkin.indexOf(skin) + dir, n);
        applySkin(GlintTableSkin.ALL[idx]);
        GlintClientConfig.setGlintTableSkin(idx);
        // The placed block follows the GUI skin, re-mesh loaded chunks so it updates immediately.
        GlintTableModelClient.refresh();
    }

    // Skin-cycle button (top-right corner of the window, above the right grid).
    private static final int SKIN_BTN_W = 50, SKIN_BTN_H = 12;
    private static final int SKIN_BTN_X = 342 - SKIN_BTN_W - 4;
    private static final int SKIN_BTN_Y = 5;

    private final List<String> trims = new ArrayList<>();
    private final Map<String, ItemStack> trimCache = new HashMap<>();
    private int gridScroll = 0;

    // Right grid (2), the player's printed (painted) trim library, synced into CLIENT_PRINTED.
    private int printScroll = 0;

    // Which grid's scrollbar is being dragged (0 = left, 1 = right, -1 = none).
    private int draggingGrid = -1;

    // Grid selections: left-click sets the main (layer-1) design; right-click sets the merge donor (its
    // colors fold into the main, the main keeps its design). selectedPrinted is a finished trim picked
    // from the right library (loaded into preview + main slot). The donor can be an empty-design trim
    // (left grid) or a printed trim (right grid); selectedDonorPrinted marks the always-owned latter.
    private String selectedMain  = null;
    private ItemStack selectedDonor = ItemStack.EMPTY;
    private boolean selectedDonorPrinted = false;
    private ItemStack selectedPrinted = ItemStack.EMPTY;

    // Multi-layer build: the controls always edit the ACTIVE layer; committed layers live below/above it
    // (render order = lowerLayers, active, upperLayers). "Add Layer" locks the active into lowerLayers and
    // starts a fresh one (one layer tear per extra layer); clicking a chip pulls that layer back to active.
    private final List<CustomGlint.Layer> lowerLayers = new ArrayList<>();
    private final List<CustomGlint.Layer> upperLayers = new ArrayList<>();
    private static final int MAX_LAYERS = 8; // matches the wand editor's 8-layer cap

    // Layer indicator strip: a row of design-icon chips filling the exact gap between the two scrollable
    // recesses (122..218 = 96px = 8 cells of 12), bottoms flush to the preview top (58). The [+] add chip
    // occupies a cell only until the 8-layer cap, so at most 8 cells span the column.
    private static final int LAYER_STRIP_X = 122, LAYER_CELL = 12, LAYER_ICON = 12;
    private static final int LAYER_STRIP_Y = PREVIEW_Y - LAYER_ICON; // icon bottom aligns to preview top

    // Modifier build state for the trim being built.
    //   speed/scale: 0.10×..8.0× (0.10 steps below 1×, whole steps above); default 1×. Cost = round(value).
    //   opacity:     0..8 glass → opaque..translucent (default 0 = no glass = fully opaque). Cost = count.
    private float modSpeed = 1.0f, modScale = 1.0f;
    private int modOpacity = 0;
    // Scroll motion: a SCROLL_* direction (default East), plus a frozen UV offset used only when STATIC.
    private int modScrollDir = CustomGlint.SCROLL_E;
    private float modScrollOffset = 0.0f;
    private boolean modInterpolate = true; // smooth colour blending between layer colours
    private boolean modGlow = false, modNamed = false; // glow / name-tag true-false toggles
    private boolean glowAuto = true;                    // glow color: auto (from layer 0) vs manual (dye)
    private boolean tearSimultaneous = true;            // tear mode chosen by the tear button (needs a tear)
    private boolean activeSourceSim = false;            // whether the active layer's SOURCE was simultaneous
                                                        // (so reverting it to sequential charges a sequential tear)
    // The active layer's colors, one entry per color shard (up to 8). Each shard is a list of dye indices
    // (0..15) blended into one colour: a single dye is a pure colour, several dyes average into a custom mix
    // that isn't in the dye table. An empty list is a freshly-added shard with no colour picked yet. The "+"
    // box adds a shard; clicking a shard selects it; right-click a dye sets it, shift-right-click mixes in.
    private final List<List<Integer>> colorShards = new ArrayList<>();
    // The color shard currently selected for editing (-1 = none). The dye bar highlights this shard's color,
    // and right-clicking a dye recolors it.
    private int selectedColorIdx = -1;
    // Custom-color encoding inside a shard's list: RAINBOW marks a shard whose colour comes from the rainbow
    // dye (a custom hex, entered in the hex box) instead of dye indices; CUSTOM_FLAG | rgb stores the hex.
    private static final int RAINBOW = 16, CUSTOM_FLAG = 0x40000000;
    private EditBox hexBox;          // custom-hex entry, shown beside a rainbow/custom shard or color slot
    private boolean hexOpen = false; // whether the hex box is open
    private int hexShardIdx = -1;    // the shard the hex box edits (in HEX_SHARD mode)
    private int hexMode = HEX_SHARD; // what the hex box is editing
    private static final int HEX_SHARD = 0, HEX_GLOW = 1, HEX_NAME = 2;
    private int glowHex = -1, nameHex = -1; // custom glow / name hex colours (when a rainbow dye is in the slot)
    private ItemStack lastMain = ItemStack.EMPTY;           // last main-slot item, to detect a placed trim
    private String trimName = "";                       // custom name text (when Name = true)
    private EditBox nameBox;
    private static final int ALPHA_MIN = 32; // most-translucent alpha (at 8 glass)

    // The clickable buttons (created in init()). Labels/colours are pulled live each frame; clickability is
    // refreshed in syncButtons(). Their labels/values and slot dimming are still drawn by the screen.
    // Only buttons whose label/colour is refreshed in syncButtons() need a field; the rest are stateless
    // (their label/colour suppliers read the build state live) and are added as bare widgets in addButtons().
    private BevelButton printBtn, glowModeBtn, glowToggleBtn, nameToggleBtn;
    private BevelButton offsetMinus, offsetPlus; // shown only while the scroll button is on Static
    private static final int SND_BTN_W = 12; // square toggle just left of the skin button

    // Scroll-direction control under the speed/opacity/scale slots (bottom-left). A button cycles the 8
    // compass presets + Static; a small −/value/+ offset stepper shows only while Static.
    private static final int SCROLL_X = 12, SCROLL_Y = 194, SCROLL_W = 100, SCROLL_H = 12; // centered on the left grid
    private static final int SCROLL_OFF_Y = 208; // static-offset stepper row

    // Interpolation toggle (bottom-right), centered on the right grid like the scroll button is on the left.
    private static final int INTERP_X = 217, INTERP_Y = 194, INTERP_W = 116, INTERP_H = 12;

    // Name / glow color sections below the Print button (center column)
    private static final int NAME_BOX_X = 129, NAME_BOX_Y = 186, NAME_BOX_W = 62;
    private static final int GLOW_MODE_X = 129, GLOW_MODE_Y = 204, GLOW_MODE_W = 62, GLOW_MODE_H = 12;

    public GlintTableScreen(GlintTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 342, 334);
        this.titleLabelY = 6;
        this.inventoryLabelY = 242;
    }

    private static Identifier designRL(String name) {
        return CustomGlint.designFromName(name); // handles vanilla + chromatic sentinels
    }

    private static ItemStack trimStack(String name) {
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, designRL(name));
        return s;
    }

    @Override
    protected void init() {
        super.init();
        applySkin(GlintTableSkin.byIndex(GlintClientConfig.glintTableSkin()));
        // Warm all skin background textures now (idempotent) so cycling skins doesn't cold-load a PNG mid-click.
        GlintTableSkin.preloadTextures();
        addButtons();
        if (trims.isEmpty()) {
            trims.add(GlowTrimItem.STORAGE_KEY); // the Glow Trim is an empty trim, not a painted one
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

        // Custom-hex entry beside a rainbow/custom color shard (positioned + shown per-frame in extractContents).
        // Wide enough to show the full 6-digit hex. Added as an event-only widget (not a renderable) so it can
        // be drawn manually at the very end of extractContents, on top of the modifier buttons it may overlap.
        hexBox = new EditBox(font, leftPos, topPos, 56, COLOR_ICON, Component.translatable("screen.customglint.glint_table.hex"));
        hexBox.setMaxLength(6);
        hexBox.setResponder(this::applyHex);
        hexBox.setVisible(false);
        addWidget(hexBox);

        // Restore the in-progress build from the last time the table was open (once per screen instance, so a
        // window resize, which re-runs init(), doesn't clobber the live edits).
        if (!restored) { restoreBuild(); restored = true; }
    }

    @Override
    public void removed() {
        saveBuild(); // keep the editor state so reopening the table resumes the build instead of resetting
        GlintClientConfig.flush(); // persist any skin/sound toggles once, off the per-click path
        super.removed();
    }

    // ── In-progress build persistence ──────────────────────────────────────────
    // The editor controls (design pick, dye shards, modifiers, layers) live only in this transient screen,
    // so closing the table to grab materials would otherwise wipe the WIP. Snapshot it on close, restore on
    // open. Static + per-session: survives reopening the table, not a game restart. The physical slots
    // already persist in the block entity.
    private boolean restored = false;
    private static Build savedBuild;

    /** Drop the persisted in-progress build on disconnect so a later session can't resume the previous
     *  server's WIP trim (matches the sync-mirror clearing in {@code GlintTableClientInit}). */
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

    /** Reset the whole working trim back to a blank editor (right-click the main slot). */
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

    /** Shift-move a table slot's item back into the player inventory (server-authoritative). */
    private void quickMoveSlot(int slot) {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    /** The main (slot-1) design picked from the left grid, on its own (the donor is shown in slot 2). */
    private ItemStack selectionPreview() {
        if (selectedMain == null) return ItemStack.EMPTY;
        ItemStack main = trimCache.get(selectedMain);
        return main == null ? ItemStack.EMPTY : main.copy();
    }

    /** A finished printed trim picked from the right library, or EMPTY. */
    private ItemStack printedSelection() {
        return selectedPrinted.isEmpty() ? ItemStack.EMPTY : selectedPrinted;
    }

    /** The merge donor (slot 2): a physical trim in that slot wins, else the right-click ghost selection. */
    private ItemStack donorStack() {
        ItemStack phys = menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem();
        if (phys.getItem() instanceof GlintTrimItem) return phys;
        return selectedDonor;
    }

    /** The donor's colors (empty if there's no donor / it's a colorless design). */
    private int[] donorColors() {
        ItemStack d = donorStack();
        return d.getItem() instanceof GlintTrimItem ? GlintTrimItem.getColors(d) : new int[0];
    }

    /** Whether the current donor is owned, physical trims and the printed library always are; a left-grid
     *  design donor must be in the stored set. No donor counts as fine. */
    private boolean donorOwned() {
        if (menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem().getItem() instanceof GlintTrimItem) return true;
        if (selectedDonor.isEmpty() || selectedDonorPrinted) return true;
        String name = trimDesignName(selectedDonor);
        return name != null && GlintStoredSyncPacket.CLIENT_STORED.contains(name);
    }

    /** Fold the donor's colors into a built trim (mirrors {@link GlintTrimItem#mergeColors}); the trim
     *  keeps its own design. */
    private ItemStack mergeDonor(ItemStack base) {
        if (!(base.getItem() instanceof GlintTrimItem) || donorColors().length == 0) return base;
        return GlintTrimItem.mergeColors(base, donorStack());
    }

    // Per-render-pass memo for the two preview stacks. activeTrim()/previewSource() rebuild ItemStacks
    // (copy + component encode) and are each called many times across one draw cascade. Build state is
    // immutable for the span of a single extractContents draw pass, every mutation happens in an input
    // handler, between frames, so the result is cached for the pass and recomputed fresh next pass. The
    // memo is only armed across the draw cascade (extractContents), so input-time callers compute live.
    private boolean frameMemo = false;
    private ItemStack frameActiveTrim, framePreviewSource, frameActiveIcon;
    // canPrint() is a pure function of the same (build + menu-slot) state and runs ~3× per draw pass
    // (syncButtons + the Print button's label/face suppliers). Memoized across the same window.
    private Boolean frameCanPrint;
    // Per-layer strip-icon cache. layerIcon() builds an ItemStack and runs a full CustomGlint.write encode;
    // the strip redraws it for every chip every frame. Layers are immutable records added/removed as whole
    // units (never mutated in place), so an instance-keyed cache yields the same icon until the layer set
    // changes, when fresh Layer instances miss and rebuild. Screen-scoped, GC'd with the screen.
    private final java.util.Map<CustomGlint.Layer, ItemStack> layerIconCache = new java.util.IdentityHashMap<>();

    /** The single ACTIVE layer being edited: the main slot (shown as-is), else a selection with the
     *  modifier build state applied (speed / scale / opacity) and the donor's colors merged in. */
    private ItemStack activeTrim() {
        if (frameMemo && frameActiveTrim != null) return frameActiveTrim;
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        ItemStack result;
        if (!main.isEmpty()) {
            result = mergeDonor(main);
        } else {
            ItemStack sel = selectedPrinted.isEmpty() ? selectionPreview() : selectedPrinted;
            result = mergeDonor(applyMods(sel));
        }
        if (frameMemo) frameActiveTrim = result;
        return result;
    }

    /** The full multi-layer trim previewed/printed: lowerLayers, then the active layer, then upperLayers. */
    private ItemStack previewSource() {
        if (frameMemo && framePreviewSource != null) return framePreviewSource;
        ItemStack result = computePreviewSource();
        if (frameMemo) framePreviewSource = result;
        return result;
    }

    private ItemStack computePreviewSource() {
        ItemStack active = activeTrim();
        if (lowerLayers.isEmpty() && upperLayers.isEmpty()) return active; // single-layer fast path
        List<CustomGlint.Layer> all = new ArrayList<>(lowerLayers);
        boolean activeValid = active.getItem() instanceof GlintTrimItem;
        if (activeValid) {
            CustomGlint.Data ad = CustomGlint.read(active);
            if (ad != null) Collections.addAll(all, ad.layers());
        }
        all.addAll(upperLayers);
        if (all.isEmpty()) return active;
        ItemStack carrier = activeValid ? active.copy() : new ItemStack(ModItems.GLINT_TRIM.get());
        if (!activeValid) GlintTrimItem.setPattern(carrier, all.get(0).design()); // base icon from first layer
        CustomGlint.write(carrier, all.toArray(new CustomGlint.Layer[0]));
        return carrier;
    }

    /** Snapshot the active layer's current build as a {@link CustomGlint.Layer}, or null if it has no
     *  design. An uncolored (no dye chosen) active layer is still captured, with empty colors, so
     *  switching layers preserves it as the player-facing "empty" placeholder instead of dropping it. */
    private CustomGlint.Layer captureActive() {
        ItemStack a = activeTrim();
        if (!(a.getItem() instanceof GlintTrimItem)) return null;
        Identifier design = GlintTrimItem.getPattern(a);
        int[] colors = GlintTrimItem.getColors(a);
        if (design == null) return null;
        boolean sim = false;
        CustomGlint.Data d = CustomGlint.read(a);
        if (d != null && d.layers().length > 0) sim = d.layers()[0].simultaneous();
        return new CustomGlint.Layer(design, colors, GlintTrimItem.getSpeed(a), modInterpolate,
                GlintTrimItem.getScale(a), sim, GlintTrimItem.getScrollDir(a), GlintTrimItem.getScrollOffset(a));
    }

    /** Load a committed layer back into the editable controls (it becomes the active layer). */
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

    /** A single-dye shard (pure colour). */
    private static List<Integer> newShard(int dye) {
        List<Integer> s = new ArrayList<>();
        s.add(dye);
        return s;
    }

    /** Add a shard for a stored colour (up to 8): a dye shard when the rgb matches a dye, else a custom-hex
     *  shard so custom (rainbow) colours survive a load instead of being dropped. */
    private void addColorShard(int rgb) {
        if (colorShards.size() >= 8) return;
        for (int i = 0; i < 16; i++) if (dyeRgb(i) == rgb) { colorShards.add(newShard(i)); return; }
        List<Integer> custom = new ArrayList<>();
        custom.add(CUSTOM_FLAG | rgb);
        colorShards.add(custom);
    }

    /** Load a painted trim into the editor for preview/editing: its glow/name + active-layer mods, and,
     *  for a multi-layer trim, split its layers across the strip (first layer active, the rest as upper
     *  layers) so every shard shows its own design + transforms. A single-layer trim stays the selection. */
    private void loadFromTrim(ItemStack trim) {
        selectedPrinted = trim;
        selectedMain = null;
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
        lowerLayers.clear();
        upperLayers.clear();
        loadModsFrom(trim); // glow / name / custom-name + the (single-layer) active mods
        CustomGlint.Data d = CustomGlint.read(trim);
        if (d != null && d.layers().length > 1) {
            CustomGlint.Layer[] ls = d.layers();
            for (int i = 1; i < ls.length; i++) upperLayers.add(ls[i]);
            loadControlsFromLayer(ls[0]); // first layer becomes the active one (keeps glow/name above)
        }
    }

    /** The left-grid / design name for a layer's design identifier (matches {@link #trimDesignName}). */
    private static String gridNameFor(Identifier p) {
        if (p.equals(CustomGlint.VANILLA)) return "vanilla";
        String name = GlintTrimItem.extractPatternName(p);
        return p.getNamespace().equals("customglint") ? name : p.getNamespace() + ":" + name;
    }

    private int totalLayers() {
        return lowerLayers.size() + upperLayers.size() + (captureActive() != null ? 1 : 0);
    }

    private int layerTearCount() {
        return menu.slots.get(GlintTableMenu.SLOT_LAYER_TEAR).getItem().getCount();
    }

    /** A committed layer is valid when it has 1..8 colors and its design is one the player owns. */
    private boolean layerValid(CustomGlint.Layer l) {
        if (l.colors().length == 0 || l.colors().length > 8) return false;
        return GlintStoredSyncPacket.CLIENT_STORED.contains(gridNameFor(l.design()));
    }

    /** The trim whose exact data the print must reproduce, a placed physical trim, else a selected
     *  printed-library trim. A left-grid design pick is a fresh build with no inherent layer/simultaneous
     *  state, so it returns EMPTY (nothing to reproduce). */
    private ItemStack reproSource() {
        ItemStack main = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (main.getItem() instanceof GlintTrimItem) return main;
        return selectedPrinted.isEmpty() ? ItemStack.EMPTY : selectedPrinted;
    }

    /** A copy of the base trim with the current speed / scale / opacity build state baked in. */
    private ItemStack applyMods(ItemStack base) {
        if (!(base.getItem() instanceof GlintTrimItem)) return base;
        ItemStack s = base.copy();
        GlintTrimItem.setSpeed(s, modSpeed);
        GlintTrimItem.setScale(s, modScale);
        GlintTrimItem.setScrollDir(s, modScrollDir);
        GlintTrimItem.setScrollOffset(s, modScrollOffset);
        GlintTrimItem.setColors(s, buildColors()); // colors come from the dye bar (with opacity applied)
        GlintTrimItem.setGlowing(s, modGlow);
        CustomGlint.setGlowing(s, modGlow);
        // Glow color: manual uses the glow dye (or a custom hex when a rainbow dye is in the slot); auto
        // clears overrides so it falls back to layer 0.
        int gc = modGlow && !glowAuto ? slotColor(GlintTableMenu.SLOT_GLOW_DYE, glowHex) : -1;
        if (gc >= 0) CustomGlint.setGlowColors(s, new int[]{0xFF000000 | gc});
        else CustomGlint.clearGlowColors(s);
        // Custom name + name color from the name dye (or a custom hex when a rainbow dye is in the slot).
        if (modNamed && !trimName.isEmpty()) {
            int nc = slotColor(GlintTableMenu.SLOT_NAME_DYE, nameHex);
            int rgb = nc >= 0 ? nc : 0xFFFFFF;
            s.set(DataComponents.CUSTOM_NAME, Component.literal(trimName).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
        }
        // Final glint Data write carrying the chosen interpolation; simultaneous only when a tear is in the
        // active slot (otherwise the layer is sequential).
        Identifier pat = GlintTrimItem.getPattern(s);
        if (pat != null) {
            boolean sim = menu.slots.get(activeTearSlot()).hasItem() && tearSimultaneous;
            CustomGlint.write(s, pat, GlintTrimItem.getColors(s), GlintTrimItem.getSpeed(s), modInterpolate,
                    GlintTrimItem.getScale(s), sim, GlintTrimItem.getScrollDir(s), GlintTrimItem.getScrollOffset(s));
        }
        return s;
    }

    /** Glass-count → glint alpha. 0 glass = fully opaque (255); each +1 fades toward {@link #ALPHA_MIN}. */
    private int modAlpha() {
        return Math.round(255f - modOpacity * (255f - ALPHA_MIN) / 8f);
    }

    /** The layer's colors, from the dye slots the player has *selected* (up to 8). Empty when nothing is
     *  chosen, a fresh layer is player-facing "empty" (no glint shown, can't print), not auto-white. The
     *  player adds white the same as any other colour if they want it. */
    private int[] buildColors() {
        int alpha = modAlpha();
        List<Integer> cols = new ArrayList<>();
        for (List<Integer> shard : colorShards) {
            int rgb = mixRgb(shard);
            if (rgb < 0) continue; // unset / rainbow-pending, no color yet
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

    /** A shard's RGB: a custom hex (rainbow dye) returns that colour; a rainbow shard with no hex yet, or an
     *  empty shard, returns -1; otherwise the equal-weight average of its dyes' colours (one dye = that
     *  colour, several = a mix). */
    private static int mixRgb(List<Integer> shard) {
        if (shard.isEmpty()) return -1;
        if (shard.size() == 1) {
            int v = shard.get(0);
            if (v == RAINBOW) return -1;                 // rainbow chosen, no hex entered yet
            if ((v & CUSTOM_FLAG) != 0) return v & 0xFFFFFF; // custom hex colour
        }
        int r = 0, g = 0, b = 0, n = 0;
        for (int d : shard) {
            if (d < 0 || d >= 16) continue;              // skip non-dye markers
            int rgb = dyeRgb(d); r += (rgb >> 16) & 0xFF; g += (rgb >> 8) & 0xFF; b += rgb & 0xFF; n++;
        }
        if (n == 0) return -1;
        return ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    /** A shard whose colour comes from the rainbow dye (a custom hex, or the rainbow marker awaiting one). */
    private static boolean isCustomShard(List<Integer> shard) {
        if (shard.size() != 1) return false;
        int v = shard.get(0);
        return v == RAINBOW || (v & CUSTOM_FLAG) != 0;
    }

    private boolean hasShiftDown() {
        var w = this.minecraft.getWindow();
        return InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /** The mod-palette RGB of dye index {@code idx} (= dye ordinal), matching the dye recipes and the trim
     *  tooltip's colour-name table, NOT {@code DyeColor.getTextColor()}, whose values differ for several
     *  dyes (e.g. lime) and would tooltip as a hex code instead of the colour name. */
    private static int dyeRgb(int idx) {
        return GlintTrimItem.DYE_COLORS[idx] & 0xFFFFFF;
    }

    /** Seed the modifier build state from a trim's current values (so re-printing reproduces it). */
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
            // Rebuild the color shards from the trim's colors: a colour matching a dye becomes that dye
            // shard, anything else (a mix or rainbow custom hex) becomes a custom-hex shard.
            for (int col : c) addColorShard(col & 0xFFFFFF);
            modGlow = GlintTrimItem.isGlowing(trim);
            modNamed = trim.has(DataComponents.CUSTOM_NAME);
            Component nm = trim.get(DataComponents.CUSTOM_NAME);
            trimName = (modNamed && nm != null) ? nm.getString() : "";
            if (nameBox != null) nameBox.setValue(trimName);
            glowAuto = CustomGlint.getGlowColors(trim).length == 0; // override colors => manual
            CustomGlint.Data d = CustomGlint.read(trim);
            if (d != null && d.layers().length > 0) {
                tearSimultaneous = d.layers()[0].simultaneous();
                modInterpolate = d.layers()[0].interpolate();
            }
            activeSourceSim = tearSimultaneous; // this layer's source mode (for the sequential-revert charge)
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

    /** Step a speed/scale value up: 0.10 increments below 1×, 0.5 increments above; capped at 8. */
    private static float stepUp(float v) {
        float nv = v < 1.0f ? v + 0.10f : v + 0.5f;
        return Math.min(8.0f, Math.round(nv * 100f) / 100f);
    }

    /** Step a speed/scale value down: 0.5 decrements above 1×, 0.10 below; floored at 0.10. */
    private static float stepDown(float v) {
        float nv = v <= 1.0f ? v - 0.10f : v - 0.5f;
        return Math.max(0.10f, Math.round(nv * 100f) / 100f);
    }

    private static String fmtVal(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format("%.2f", v).replaceAll("0+$", "");
    }


    // ── Render ──────────────────────────────────────────────────────────────

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mx, int my, float dt) {
        // When a finished trim is placed in the main slot, pull all its values into the controls.
        ItemStack curMain = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!ItemStack.matches(curMain, lastMain)) {
            lastMain = curMain.copy();
            if (curMain.getItem() instanceof GlintTrimItem) loadModsFrom(curMain);
        }
        boolean mainTrim = curMain.getItem() instanceof GlintTrimItem;

        // Always keep one color shard present: a fresh layer starts with a single unset shard (no colour)
        // so there's a visible starting point. Removing the last shard re-seeds an unset one. The lone/final
        // shard is always selected (it can't be deselected) so a dye click always lands somewhere.
        if (colorShards.isEmpty()) colorShards.add(new ArrayList<>());
        if (colorShards.size() == 1) selectedColorIdx = 0;

        // Glow / Name require their material, but only force-off while building from a selection
        // (a placed trim carries its own glow/name, so its loaded values shouldn't be wiped).
        if (!mainTrim) {
            if (!menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE).hasItem()) modGlow = false;
            if (!menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem()) modNamed = false;
        }

        // Drive the conditional dye slots + name field from the current toggle state.
        menu.showNameDye = modNamed;
        menu.showGlowDye = modGlow && !glowAuto && !glowTrimMain();
        if (nameBox != null) nameBox.setVisible(modNamed);

        // Hex entry box: positioned flush to the right of whatever it edits (shard, or glow/name slot);
        // auto-closes if its target is gone (shard removed, or the slot no longer holds a rainbow dye).
        if (hexBox != null) {
            boolean show = hexOpen;
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

        // Arm the preview-stack memo for the pure draw cascade below (state is settled after the prologue
        // above). finally-disarmed so a thrown draw can't leave a stale cache visible to input handlers.
        frameActiveTrim = null; framePreviewSource = null; frameActiveIcon = null; frameCanPrint = null; frameMemo = true;
        try {
            // Refresh button enabled/label state inside the memo window so its canPrint()/activeTrim()
            // cascade shares the same per-frame preview-stack cache the draw calls below reuse (it used to
            // run before the memo was armed and rebuilt the preview stacks from scratch).
            syncButtons();
            drawBackground(g);
            super.extractContents(g, mx, my, dt); // labels + real slot contents (+ name box) on top of the panel
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
            // Drawn last so it overlays the modifier buttons (interp / true-false) it can sit on top of.
            if (hexBox != null) hexBox.extractRenderState(g, mx, my, dt);
        } finally {
            frameMemo = false;
        }
    }

    /** Draw the window title + inventory label in the skin's header color (vanilla hardcodes dark gray). */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
        g.text(font, title, titleLabelX, titleLabelY, LABEL_HDR, false);
        g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_HDR, false);
    }

    /** Vanilla draws tooltips for real slots; add hover tooltips for the two scrollable grids (printed
     *  trims + the design palette), which are custom-drawn, not real slots. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor g, int mx, int my) {
        super.extractTooltip(g, mx, my);
        if (!menu.getCarried().isEmpty()) return; // don't tooltip while a stack is on the cursor
        if (mx >= leftPos + SKIN_BTN_X && mx < leftPos + SKIN_BTN_X + SKIN_BTN_W
                && my >= topPos + SKIN_BTN_Y && my < topPos + SKIN_BTN_Y + SKIN_BTN_H) {
            g.setTooltipForNextFrame(font, Component.translatable("screen.customglint.glint_table.skin_tooltip"), mx, my);
            return;
        }
        if (mx >= leftPos + SKIN_BTN_X - SND_BTN_W - 2 && mx < leftPos + SKIN_BTN_X - 2
                && my >= topPos + SKIN_BTN_Y && my < topPos + SKIN_BTN_Y + SKIN_BTN_H) {
            g.setTooltipForNextFrame(font, Component.translatable("screen.customglint.glint_table.sound",
                    Component.translatable(GlintClientConfig.glintTableSound()
                            ? "screen.customglint.glint_table.on" : "screen.customglint.glint_table.off")), mx, my);
            return;
        }
        if (mx >= leftPos + PRINT_X && mx < leftPos + PRINT_X + PRINT_W
                && my >= topPos + PRINT_Y && my < topPos + PRINT_Y + PRINT_H) {
            g.setTooltipForNextFrame(font, printTooltip(), Optional.empty(), mx, my);
            return;
        }
        int ri = gridIndexAt(mx, my, leftPos + RGRID_X, topPos + GRID_Y, printScroll, GlintPrintedSyncPacket.CLIENT_PRINTED.size());
        if (ri >= 0) { g.setTooltipForNextFrame(font, GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri), mx, my); return; }
        int li = gridIndexAt(mx, my, leftPos + LGRID_X, topPos + GRID_Y, gridScroll, trims.size());
        if (li >= 0) {
            ItemStack stack = trimCache.get(trims.get(li));
            if (stack != null) g.setTooltipForNextFrame(font, stack, mx, my);
        }
    }

    private void drawBackground(GuiGraphicsExtractor g) {
        int x = leftPos, y = topPos;

        // Skin background: window, grid recesses, preview box and the fixed slot wells.
        skin.windowPanel(g, x, y, imageWidth, imageHeight);

        // The name and glow dye wells show only while their slot is active.
        drawConditionalWell(g, GlintTableMenu.SLOT_NAME_DYE);
        drawConditionalWell(g, GlintTableMenu.SLOT_GLOW_DYE);

        // Ghost hints in table slots that have a meaningful "expected item".
        for (int i = 0; i < GlintTableMenu.TABLE_SIZE; i++) {
            if (!menu.slots.get(i).isActive()) continue;
            ItemStack ghost = ghostFor(i);
            if (!ghost.isEmpty()) drawGhostItem(g, menu.slots.get(i), ghost);
        }
    }

    /** Draws the well for a slot that is only present in some modes, while it is active. */
    private void drawConditionalWell(GuiGraphicsExtractor g, int containerSlot) {
        Slot s = menu.slots.get(containerSlot);
        if (s.isActive()) slotWell(g, leftPos + s.x - 1, topPos + s.y - 1);
    }

    /** A dye-palette ghost that cycles through the 16 vanilla dyes (~every 1.5s), offset by {@code phase}. */
    private static ItemStack cyclingDyeGhost(int phase) {
        int n = GlintTableMenu.DYE_ITEMS.length;
        int idx = (int) (((Util.getMillis() / 1500) + phase) % n);
        return new ItemStack(GlintTableMenu.DYE_ITEMS[idx]);
    }

    /** The faint "what goes here" hint item for a table slot, or EMPTY for none. */
    private ItemStack ghostFor(int containerSlot) {
        if (containerSlot >= GlintTableMenu.SLOT_DYE_START
                && containerSlot < GlintTableMenu.SLOT_DYE_START + 16) {
            return new ItemStack(GlintTableMenu.DYE_ITEMS[containerSlot - GlintTableMenu.SLOT_DYE_START]);
        }
        return switch (containerSlot) {
            case GlintTableMenu.SLOT_SLIME     -> new ItemStack(Items.SLIME_BALL);
            case GlintTableMenu.SLOT_REDSTONE  -> new ItemStack(Items.REDSTONE);
            case GlintTableMenu.SLOT_GLASS     -> new ItemStack(Items.GLASS);
            case GlintTableMenu.SLOT_GLOWSTONE -> new ItemStack(Items.GLOWSTONE_DUST);
            case GlintTableMenu.SLOT_NAMETAG   -> new ItemStack(Items.NAME_TAG);
            // SLOT_TRIM_B (slot 2) has no ghost hint, it only shows a right-clicked donor (drawDonorPreview).
            case GlintTableMenu.SLOT_TEAR      -> ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance();
            case GlintTableMenu.SLOT_TEAR_SEQ  -> ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance();
            case GlintTableMenu.SLOT_LAYER_TEAR -> ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance();
            case GlintTableMenu.SLOT_RAINBOW_DYE -> ModItems.RAINBOW_DYE.get().getDefaultInstance();
            // Any dye works in these slots, so cycle the ghost through the palette instead of a static white
            // dye. The two slots run out of phase so they don't show the same color at once.
            case GlintTableMenu.SLOT_NAME_DYE -> cyclingDyeGhost(0);
            case GlintTableMenu.SLOT_GLOW_DYE -> cyclingDyeGhost(8);
            default -> ItemStack.EMPTY;
        };
    }


    private void drawGhostItem(GuiGraphicsExtractor g, Slot slot, ItemStack ghost) {
        if (slot.hasItem()) return;
        int x = leftPos + slot.x, y = topPos + slot.y;
        g.item(ghost, x, y);
        g.fill(x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    /** The grid name of a trim stack (matches a PATTERNS entry), or null if it has no design. */
    private static String trimDesignName(ItemStack stack) {
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.STORAGE_KEY;
        Identifier p = GlintTrimItem.getPattern(stack);
        if (p == null) return null;
        if (p.equals(CustomGlint.VANILLA)) return "vanilla";
        String name = GlintTrimItem.extractPatternName(p);
        return p.getNamespace().equals("customglint") ? name : p.getNamespace() + ":" + name;
    }

    /** Design highlighted as "main": the trim physically in the main slot wins, else the grid click. */
    private String highlightedMain() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem());
        return n != null ? n : selectedMain;
    }

    /** Design highlighted as "donor" in the LEFT grid: a trim physically in the merge slot wins, else a
     *  left-grid (design) ghost donor. A printed-library ghost donor is ringed in the right grid instead. */
    private String highlightedDonor() {
        String n = trimDesignName(menu.slots.get(GlintTableMenu.SLOT_TRIM_B).getItem());
        if (n != null) return n;
        return (!selectedDonor.isEmpty() && !selectedDonorPrinted) ? trimDesignName(selectedDonor) : null;
    }

    /** Left grid (1): the empty-trim design palette; un-ghosts stored designs, rings the active ones. */
    private void drawTrimGrid(GuiGraphicsExtractor g, int mx, int my) {
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
                g.item(trimCache.getOrDefault(name, ItemStack.EMPTY), cx, cy);
                if (!GlintStoredSyncPacket.CLIENT_STORED.contains(name)) g.fill(cx, cy, cx + 16, cy + 16, DIM_GHOST);
                if (name.equals(mainName))       border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                else if (name.equals(donorName)) border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);

                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) g.fill(cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, total, gridScroll);
    }

    /** The visible/usable slot count for the printed library: starts at 42, grows by rows to a 128 cap. */
    private int printedCapacity() {
        int size = GlintPrintedSyncPacket.CLIENT_PRINTED.size();
        int cells = ((size / GRID_COLS) + 2) * GRID_COLS; // enough rows for everything + one spare row
        return Math.max(GRID_ROWS * GRID_COLS, Math.min(128, cells));
    }

    /** Right grid (2): the player's printed (painted) trim library. */
    private void drawPrintedGrid(GuiGraphicsExtractor g, int mx, int my) {
        int gx = leftPos + RGRID_X, gy = topPos + GRID_Y;
        List<ItemStack> list = GlintPrintedSyncPacket.CLIENT_PRINTED;
        int cap = printedCapacity();

        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = (printScroll + row) * GRID_COLS + col;
                if (idx >= cap) continue; // beyond the grown capacity: no slot here
                int cx = gx + col * CELL, cy = gy + row * CELL;
                slotWell(g, cx - 1, cy - 1);

                if (idx < list.size()) {
                    ItemStack s = list.get(idx);
                    g.item(s, cx, cy);
                    if (!selectedPrinted.isEmpty() && ItemStack.isSameItemSameComponents(s, selectedPrinted))
                        border(g, cx - 1, cy - 1, 18, 18, RING_MAIN);
                    else if (selectedDonorPrinted && ItemStack.isSameItemSameComponents(s, selectedDonor))
                        border(g, cx - 1, cy - 1, 18, 18, RING_DONOR);
                }
                if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) g.fill(cx, cy, cx + 16, cy + 16, HOVER_TINT);
            }
        }
        drawScrollbar(g, gx, gy, cap, printScroll);
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int gx, int gy, int total, int scroll) {
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

    /** True if (mx,my) is on a grid's 4px scrollbar track. gx/gy are the grid's top-left. */
    private boolean onScrollbar(double mx, double my, int gx, int gy, int total) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        if (rows - GRID_ROWS <= 0) return false; // nothing to scroll
        int trackX = gx + GRID_COLS * CELL + 1, trackH = GRID_ROWS * CELL;
        return mx >= trackX && mx < trackX + 4 && my >= gy && my < gy + trackH;
    }

    /** Map a mouse Y on the track to a scroll row (thumb-centred, clamped), matching drawScrollbar's geometry. */
    private int scrollFromMouse(double my, int gy, int total) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        if (maxRow <= 0) return 0;
        int trackH = GRID_ROWS * CELL;
        int thumbH = Math.max(8, trackH * GRID_ROWS / rows);
        float f = (float) ((my - gy - thumbH / 2.0) / (trackH - thumbH));
        return Math.round(Math.max(0f, Math.min(1f, f)) * maxRow);
    }

    /** Selected-but-not-placed preview ghosted into the empty main slot (8). Drawn solid when the
     *  selection is owned (a printed-library trim, or a stored design); dimmed only when unowned. */
    private void drawMainPreview(GuiGraphicsExtractor g) {
        Slot main = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        if (main.hasItem()) return;
        boolean printed = !printedSelection().isEmpty();
        boolean decomposed = !lowerLayers.isEmpty() || !upperLayers.isEmpty();
        // Single-layer printed → the printed trim; a multi-layer printed (split across the layer strip) →
        // the full reconstructed trim, so the WHOLE clicked trim shows in the main slot, not just layer 1.
        ItemStack sel = printed ? printedSelection() : decomposed ? previewSource() : selectionPreview();
        if (sel.isEmpty()) return;
        int x = leftPos + main.x, y = topPos + main.y;
        g.item(sel, x, y);
        boolean owned = printed || decomposed || (selectedMain != null && GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain));
        if (!owned) g.fill(x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    /** Outline the two trim slots: slot 1 (main) green, slot 2 (the right-click donor / layer slot) gold. */
    private void drawMainSlotRings(GuiGraphicsExtractor g) {
        Slot s1 = menu.slots.get(GlintTableMenu.SLOT_TRIM);
        Slot s2 = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        border(g, leftPos + s1.x - 1, topPos + s1.y - 1, 18, 18, RING_MAIN);
        border(g, leftPos + s2.x - 1, topPos + s2.y - 1, 18, 18, RING_DONOR);
    }

    /** Right-click-selected donor ghosted into the empty merge slot (9); dimmed when unowned. */
    private void drawDonorPreview(GuiGraphicsExtractor g) {
        Slot s = menu.slots.get(GlintTableMenu.SLOT_TRIM_B);
        if (s.hasItem() || selectedDonor.isEmpty()) return;
        int x = leftPos + s.x, y = topPos + s.y;
        g.item(selectedDonor, x, y);
        if (!donorOwned()) g.fill(x, y, x + 16, y + 16, DIM_PREVIEW);
    }

    // ── Layer indicator strip ───────────────────────────────────────────────────

    /** A layer chip: x is screen-relative-to-window; kind 0=lower,1=active,2=upper,3=add; index into list. */
    private record Chip(int x, int kind, int index) {}

    /** The chip layout in render order: lowerLayers, the active layer (if valid), upperLayers, then [+]. */
    private List<Chip> layerChips() {
        List<Chip> chips = new ArrayList<>();
        int i = 0;
        for (int k = 0; k < lowerLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 0, k));
        if (activeTrim().getItem() instanceof GlintTrimItem) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 1, 0));
        for (int k = 0; k < upperLayers.size(); k++) chips.add(new Chip(LAYER_STRIP_X + i++ * LAYER_CELL, 2, k));
        if (i < MAX_LAYERS) chips.add(new Chip(LAYER_STRIP_X + i * LAYER_CELL, 3, 0)); // [+] only while under the cap
        return chips;
    }

    /** An icon stack for a committed layer, carrying its full transforms (design, colors, speed, scale,
     *  scroll, simultaneous) so the chip animates exactly like the layer does, not just design + colors. */
    private ItemStack layerIcon(CustomGlint.Layer l) {
        ItemStack cached = layerIconCache.get(l);
        if (cached != null) return cached;
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, l.design());          // base sprite + CustomModelData
        CustomGlint.write(s, new CustomGlint.Layer[]{ l }); // exact glint Data (overwrites setPattern's default)
        layerIconCache.put(l, s);
        return s;
    }

    /** Glint-only chip icon for the ACTIVE layer. {@link #captureActive()} mints a fresh {@link CustomGlint.Layer}
     *  every call, so feeding it to the identity-keyed {@link #layerIconCache} would add one never-reused entry
     *  per frame, a slow leak while the table stays open. Build it once per draw pass via the frame memo and
     *  keep it out of layerIconCache (whose entries are the stable lower/upper-layer instances that DO reuse). */
    private ItemStack activeLayerIcon() {
        if (frameMemo && frameActiveIcon != null) return frameActiveIcon;
        CustomGlint.Layer active = captureActive();
        ItemStack result;
        if (active == null) {
            result = activeTrim(); // no design yet, fall back to the live active stack (already frame-memoized)
        } else {
            result = new ItemStack(ModItems.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, active.design());          // base sprite + CustomModelData
            CustomGlint.write(result, new CustomGlint.Layer[]{ active }); // glint-only (no glow halo)
        }
        if (frameMemo) frameActiveIcon = result;
        return result;
    }

    /** True when the layer strip should be visible: a layer tear is loaded, or layers already exist. */
    private boolean layerStripVisible() {
        return layerTearCount() > 0 || !lowerLayers.isEmpty() || !upperLayers.isEmpty();
    }

    /** Draw the layer chips above the preview: a design icon per layer (active ringed) plus a [+] add. */
    private void drawLayerStrip(GuiGraphicsExtractor g, int mx, int my) {
        if (!layerStripVisible()) return;
        for (Chip c : layerChips()) {
            int x = leftPos + c.x(), y = topPos + LAYER_STRIP_Y;
            if (c.kind() == 3) { // add chip
                boolean ok = canAddLayer();
                boolean hover = ok && mx >= x && mx < x + LAYER_ICON && my >= y && my < y + LAYER_ICON;
                raisedPanel(g, x, y, LAYER_ICON, LAYER_ICON, hover ? BTN_HOVER : (ok ? GUI_FACE : BTN_DISABLED));
                centered(g, "+", x + LAYER_ICON / 2, y + 2, ok ? LABEL_HDR : COST_BAD);
                continue;
            }
            g.fill(x, y, x + LAYER_ICON, y + LAYER_ICON, SLOT_DARK); // chip backing (exact cell)
            // Shards show only each layer's DESIGN glint, never the glow halo, the active shard is built
            // from its captured layer (glow-free) just like the committed ones, not the glowing activeTrim().
            ItemStack icon = c.kind() == 0 ? layerIcon(lowerLayers.get(c.index()))
                    : c.kind() == 2 ? layerIcon(upperLayers.get(c.index()))
                    : activeLayerIcon();
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(LAYER_ICON / 16f, LAYER_ICON / 16f);
            g.item(icon, 0, 0);
            pose.popMatrix();
            shardBevel(g, x, y, LAYER_ICON); // menu-style sunken frame
            if (c.kind() == 1) border(g, x, y, LAYER_ICON, LAYER_ICON, RING_MAIN); // active layer
            if (mx >= x && mx < x + LAYER_ICON && my >= y && my < y + LAYER_ICON)
                g.fill(x, y, x + LAYER_ICON, y + LAYER_ICON, HOVER_TINT);
        }
    }

    // ── Color-shard strip (active layer's colors, mirrors the layer strip but below the preview) ──────


    /** The color shards (one per entry in {@link #colorShards}) plus a trailing "+" box, mirroring the layer
     *  strip. Each shard shows its blended colour; the selected shard ({@link #selectedColorIdx}) is ringed;
     *  an empty (unset) shard shows a neutral placeholder. */
    private void drawColorStrip(GuiGraphicsExtractor g, int mx, int my) {
        int n = Math.min(colorShards.size(), 8);
        for (int k = 0; k < n; k++) {
            int x = leftPos + COLOR_STRIP_X + k * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            List<Integer> shard = colorShards.get(k);
            int rgb = mixRgb(shard);
            if (rgb < 0 && isCustomShard(shard)) {       // rainbow chosen, no hex yet → show the rainbow dye icon
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, SLOT_DARK);
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(x, y);
                pose.scale(COLOR_ICON / 16f, COLOR_ICON / 16f);
                g.item(ModItems.RAINBOW_DYE.get().getDefaultInstance(), 0, 0);
                pose.popMatrix();
            } else {
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, rgb < 0 ? COLOR_UNSET : 0xFF000000 | rgb);
            }
            shardBevel(g, x, y, COLOR_ICON);                                              // menu-style sunken frame
            if (k == selectedColorIdx) border(g, x, y, COLOR_ICON, COLOR_ICON, RING_MAIN);
            if (mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON)
                g.fill(x, y, x + COLOR_ICON, y + COLOR_ICON, HOVER_TINT);
        }
        // "+" add-color box after the shards, hidden while the hex box is open so it doesn't paint over it
        // (the hex EditBox is a widget drawn earlier, in super.extractContents).
        if (n < 8 && !hexOpen) {
            int x = leftPos + COLOR_STRIP_X + n * COLOR_CELL, y = topPos + COLOR_STRIP_Y;
            boolean hover = mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON;
            raisedPanel(g, x, y, COLOR_ICON, COLOR_ICON, hover ? BTN_HOVER : GUI_FACE);
            centered(g, "+", x + COLOR_ICON / 2, y + 2, LABEL_HDR);
        }
    }

    /** Color-strip clicks: left-click a shard selects it (the dye bar then highlights/recolors it),
     *  right-click removes it, and the trailing "+" adds a new (unset) shard. Returns true if hit. */
    private boolean colorStripClick(double mx, double my, int button) {
        int y = topPos + COLOR_STRIP_Y;
        int n = Math.min(colorShards.size(), 8);
        for (int k = 0; k < n; k++) {
            int x = leftPos + COLOR_STRIP_X + k * COLOR_CELL;
            if (mx < x || mx >= x + COLOR_ICON || my < y || my >= y + COLOR_ICON) continue;
            if (button == 1) {                       // right-click removes this shard
                colorShards.remove(k);
                if (selectedColorIdx == k) selectedColorIdx = -1;
                else if (selectedColorIdx > k) selectedColorIdx--;
                closeHex();
            } else if (selectedColorIdx == k) {      // re-click the selected shard
                // A rainbow/custom shard toggles the hex entry box; a normal shard does nothing (no unselect).
                if (isCustomShard(colorShards.get(k))) { if (hexOpen) closeHex(); else openHex(k); }
            } else {                                 // left-click selects this shard, never unselects
                selectedColorIdx = k;
                closeHex();
            }
            return true;
        }
        if (n < 8 && !hexOpen) {                      // "+" box: add a new unset shard, selected (hidden while editing hex)
            int x = leftPos + COLOR_STRIP_X + n * COLOR_CELL;
            if (mx >= x && mx < x + COLOR_ICON && my >= y && my < y + COLOR_ICON) {
                if (button == 0) { colorShards.add(new ArrayList<>()); selectedColorIdx = n; closeHex(); }
                return true;
            }
        }
        return false;
    }

    /** Open the hex box for a rainbow/custom shard, seeded with its current hex (empty if none yet). */
    private void openHex(int k) {
        hexMode = HEX_SHARD;
        hexShardIdx = k;
        int v = colorShards.get(k).get(0);
        openHexWith((v & CUSTOM_FLAG) != 0 ? String.format("%06X", v & 0xFFFFFF) : "");
    }

    /** Open the hex box for the custom glow ({@link #HEX_GLOW}) or name ({@link #HEX_NAME}) colour. */
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

    /** Apply the hex box's text (a valid 6-digit hex) to whatever it's editing: a shard's custom colour, or
     *  the custom glow / name colour. */
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

    /** Whether the rainbow dye occupies a given color slot (glow / name). */
    private boolean rainbowInSlot(int slotConst) {
        return menu.slots.get(slotConst).getItem().getItem() == ModItems.RAINBOW_DYE.get();
    }

    private boolean canAddLayer() {
        CustomGlint.Layer[] dl = donorLayers();
        int extras = lowerLayers.size() + upperLayers.size();
        return dl.length > 0
                && donorOwned()
                && totalLayers() + dl.length <= MAX_LAYERS
                && layerTearCount() >= extras + dl.length;
    }

    /** Every layer of the trim staged in slot 2 (the donor / layer slot), or empty if it has no design. A
     *  multi-layer donor contributes ALL its layers (a 3-layer donor adds 3 layers), one tear per layer. */
    private CustomGlint.Layer[] donorLayers() {
        ItemStack d = donorStack();
        if (!(d.getItem() instanceof GlintTrimItem)) return new CustomGlint.Layer[0];
        CustomGlint.Data dd = CustomGlint.read(d);
        if (dd != null && dd.layers().length > 0) return dd.layers();
        Identifier design = GlintTrimItem.getPattern(d);
        if (design == null) return new CustomGlint.Layer[0];
        return new CustomGlint.Layer[]{ new CustomGlint.Layer(design, GlintTrimItem.getColors(d),
                GlintTrimItem.getSpeed(d), true, GlintTrimItem.getScale(d), false,
                GlintTrimItem.getScrollDir(d), GlintTrimItem.getScrollOffset(d)) };
    }

    /** Promote the slot-2 trim's full layer stack on top of the active main (one layer tear per added
     *  layer), then clear the slot-2 selection. The active layer in slot 1 is left untouched. */
    private void addLayer() {
        CustomGlint.Layer[] dl = donorLayers();
        int extras = lowerLayers.size() + upperLayers.size();
        if (dl.length == 0 || totalLayers() + dl.length > MAX_LAYERS || layerTearCount() < extras + dl.length) return;
        Collections.addAll(upperLayers, dl);
        selectedDonor = ItemStack.EMPTY;
        selectedDonorPrinted = false;
    }

    /** Pull a committed layer back into the controls for editing, keeping render order intact. */
    private void editLayer(int kind, int k) {
        CustomGlint.Layer cur = captureActive();
        if (kind == 0) { // lower[k] → active
            CustomGlint.Layer clicked = lowerLayers.get(k);
            List<CustomGlint.Layer> head = new ArrayList<>(lowerLayers.subList(0, k));
            List<CustomGlint.Layer> tail = new ArrayList<>(lowerLayers.subList(k + 1, lowerLayers.size()));
            List<CustomGlint.Layer> newUpper = new ArrayList<>(tail);
            if (cur != null) newUpper.add(cur);
            newUpper.addAll(upperLayers);
            lowerLayers.clear(); lowerLayers.addAll(head);
            upperLayers.clear(); upperLayers.addAll(newUpper);
            loadControlsFromLayer(clicked);
        } else if (kind == 2) { // upper[k] → active
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

    /** Center preview slot (3): the current trim rendered large. */
    private void drawPreview(GuiGraphicsExtractor g) {
        int bx = leftPos + PREVIEW_X, by = topPos + PREVIEW_Y;
        ItemStack src = previewSource();
        if (src.isEmpty()) return;

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(bx + PREVIEW_W / 2f, by + PREVIEW_H / 2f);
        // Integer zoom only, a fractional zoom sub-pixel-seams the trim's edge/body layers and the glow halo.
        float scale = (Math.min(PREVIEW_W, PREVIEW_H) - 2) / 16; // int division floors to a whole multiple
        pose.scale(scale, scale);
        g.item(src, -8, -8);
        pose.popMatrix();
    }

    private DyeColor dyeIn(int slotConst) {
        return menu.slots.get(slotConst).getItem().get(DataComponents.DYE);
    }

    /** RGB of a glow/name colour slot: a custom hex when a rainbow dye sits in it ({@code customHex}, -1 if
     *  none yet), else the dye's palette colour, or -1 when the slot is empty. */
    private int slotColor(int slotConst, int customHex) {
        if (rainbowInSlot(slotConst)) return customHex;
        DyeColor d = dyeIn(slotConst);
        return d != null ? dyeRgb(d.ordinal()) : -1;
    }

    /** Green ring on every dye that makes up the selected shard (one for a pure colour, several for a mix;
     *  nothing if none selected or the shard is still unset). */
    private void drawDyeSelection(GuiGraphicsExtractor g) {
        if (selectedColorIdx < 0 || selectedColorIdx >= colorShards.size()) return;
        for (int idx : colorShards.get(selectedColorIdx)) {
            Slot s = (idx == RAINBOW || (idx & CUSTOM_FLAG) != 0)
                    ? menu.slots.get(GlintTableMenu.SLOT_RAINBOW_DYE)   // custom/rainbow → ring the rainbow slot
                    : menu.slots.get(GlintTableMenu.SLOT_DYE_START + idx);
            border(g, leftPos + s.x - 1, topPos + s.y - 1, 18, 18, RING_MAIN);
        }
    }

    /** True when the trim being built is a Glow Trim, its glint-glow controls don't apply. */
    private boolean glowTrimMain() {
        return previewSource().getItem() instanceof GlowTrimItem;
    }

    /** When naming is off, draws a non-clickable "Not Available" panel where the name text box would sit.
     *  (The name box widget shows when naming is on; the glow-mode button's label/colour are driven by
     *  {@code glowModeLabel}/{@code glowModeColor} on the widget itself, not here.) */
    private void drawNameGlowSection(GuiGraphicsExtractor g) {
        if (!modNamed) {
            // Cosmetic (non-clickable) panel in place of the text box, same length as it. The glow-mode
            // button beside it is a widget.
            int nx = leftPos + NAME_BOX_X, ny = topPos + NAME_BOX_Y;
            raisedPanel(g, nx, ny, NAME_BOX_W, 12, GUI_FACE);
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(nx + NAME_BOX_W / 2f, ny + 3f);
            pose.scale(0.85f, 0.85f);
            centered(g, Component.translatable("screen.customglint.glint_table.not_available").getString(), 0, 0, COST_BAD);
            pose.popMatrix();
        }
    }

    /** Whether speed / scale are tuned off their 1× default, the flat cost gate for redstone / slime. */
    private boolean speedTuned() { return Math.abs(modSpeed - 1.0f) > 0.001f; }
    private boolean scaleTuned() { return Math.abs(modScale - 1.0f) > 0.001f; }

    /** The whole trim's flat material cost {redstone, slime, glass}: one per layer (active + committed) that
     *  tunes speed/scale off 1× or sets any opacity. Counts every layer so the cost is the total for the
     *  finished trim, not whichever layer happens to be selected. Mirrors {@link GlintTableMenu#print}. */
    private int[] layerCosts() {
        int red = speedTuned() ? 1 : 0, slime = scaleTuned() ? 1 : 0, glass = modOpacity > 0 ? 1 : 0;
        for (CustomGlint.Layer l : lowerLayers) { red += layerTunedSpeed(l); slime += layerTunedScale(l); glass += layerTranslucent(l); }
        for (CustomGlint.Layer l : upperLayers) { red += layerTunedSpeed(l); slime += layerTunedScale(l); glass += layerTranslucent(l); }
        return new int[]{red, slime, glass};
    }
    private static int layerTunedSpeed(CustomGlint.Layer l) { return Math.abs(l.speed() - 1.0f) > 0.001f ? 1 : 0; }
    private static int layerTunedScale(CustomGlint.Layer l) { return Math.abs(l.patternScale() - 1.0f) > 0.001f ? 1 : 0; }
    private static int layerTranslucent(CustomGlint.Layer l) { int[] c = l.colors(); return (c.length > 0 && ((c[0] >>> 24) & 0xFF) < 255) ? 1 : 0; }

    /** Committed (extra) layers that are simultaneous, each is already baked simultaneous, so each requires
     *  its own simultaneous tear regardless of which layer is currently selected. */
    private int committedSimLayers() {
        int n = 0;
        for (CustomGlint.Layer l : lowerLayers) if (l.simultaneous()) n++;
        for (CustomGlint.Layer l : upperLayers) if (l.simultaneous()) n++;
        return n;
    }

    /** Total simultaneous tears the whole trim consumes (mirrors GlintTableMenu#print): one per simultaneous
     *  committed layer, plus the active layer when its toggle is on and a tear is left over. */
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
        // The active layer (built from the controls) must be valid, the server builds it from these fields.
        ItemStack src = activeTrim();
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem) || GlintTrimItem.getPattern(src) == null) return false;
        // One layer tear per extra layer beyond the first, and never past the cap.
        if (totalLayers() > MAX_LAYERS) return false;
        if (layerTearCount() < totalLayers() - 1) return false;

        // Every committed layer must itself be valid + owned (the server rejects the print otherwise).
        for (CustomGlint.Layer l : lowerLayers) if (!layerValid(l)) return false;
        for (CustomGlint.Layer l : upperLayers) if (!layerValid(l)) return false;

        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        boolean fromBase = base.getItem() instanceof GlintTrimItem;

        // Can only print a design you own, a placed physical trim and the printed library are always
        // yours; a left-grid main selection must be in the stored (un-dimmed) set, and so must the donor.
        if (!fromBase && selectedPrinted.isEmpty()
                && (selectedMain == null || !GlintStoredSyncPacket.CLIENT_STORED.contains(selectedMain))) return false;
        if (!donorOwned()) return false;

        // Needs at least one color: the placed trim's existing colors, or at least one selected dye.
        int baseColorCount = fromBase ? GlintTrimItem.getColors(base).length : 0;
        if (baseColorCount == 0 && countSelectedDyes() == 0) return false;
        // The merged total (main colors + the donor's merged-in colors) can't exceed 8 layers.
        int mainCount = fromBase ? baseColorCount : countSelectedDyes();
        if (mainCount + donorColors().length > 8) return false;

        // The table builds a single layer: a trim being reproduced that has more than one glint layer
        // can't be rebuilt here, and a simultaneous multi-color layer needs a tear in the slot.
        ItemStack repro = reproSource();
        if (!repro.isEmpty()) {
            CustomGlint.Data d = CustomGlint.read(repro);
            if (d != null && d.layers().length > 1) return false;
            // Keeping a simultaneous multi-color layer simultaneous needs its tear present.
            if (d != null && d.layers().length == 1
                    && d.layers()[0].simultaneous() && d.layers()[0].colors().length >= 2
                    && tearSimultaneous && !menu.slots.get(GlintTableMenu.SLOT_TEAR).hasItem()) return false;
        }
        // Every simultaneous committed layer needs its own simultaneous tear in the slot.
        if (menu.slots.get(GlintTableMenu.SLOT_TEAR).getItem().getCount() < committedSimLayers()) return false;
        // Reverting a simultaneous source layer back to sequential needs a sequential tear.
        if (activeSourceSim && !tearSimultaneous && !menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).hasItem()) return false;

        boolean baseGlowing = fromBase && CustomGlint.isGlowing(base);
        boolean baseHasGlowColors = fromBase && CustomGlint.getGlowColors(base).length > 0;
        boolean baseNamed = fromBase && base.has(DataComponents.CUSTOM_NAME);

        // Flat cost: one material per tuned layer across the whole trim (mirrors GlintTableMenu#print).
        int[] cost = layerCosts();
        if (menu.slots.get(GlintTableMenu.SLOT_REDSTONE).getItem().getCount() < cost[0]) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_SLIME).getItem().getCount() < cost[1]) return false;
        if (menu.slots.get(GlintTableMenu.SLOT_GLASS).getItem().getCount() < cost[2]) return false;
        if (modGlow && !baseGlowing && !menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE).hasItem()) return false;
        if (modNamed && !baseNamed && !menu.slots.get(GlintTableMenu.SLOT_NAMETAG).hasItem()) return false;
        if (modGlow && !glowAuto && slotColor(GlintTableMenu.SLOT_GLOW_DYE, glowHex) < 0 && !baseHasGlowColors) return false;
        return true;
    }

    /** Every reason the print is blocked, in plain language (mirrors {@link #canPrint}); empty when printable.
     *  Shown on the disabled Print button so the missing piece is obvious instead of a guessing game. */
    private List<Component> printIssues() {
        List<Component> out = new ArrayList<>();
        ItemStack src = activeTrim();
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem) || GlintTrimItem.getPattern(src) == null) {
            out.add(Component.translatable("screen.customglint.glint_table.issue.pick_design"));
            return out; // everything else depends on a chosen design
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
        boolean baseNamed = fromBase && base.has(DataComponents.CUSTOM_NAME);
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

    /** The localized display name of an item, for material/requirement lines (auto-tracks the item's lang). */
    private static Component itemName(Item item) {
        return new ItemStack(item).getHoverName();
    }


    /** Hover breakdown for the Print button: every material the build will consume, each green when the
     *  slot holds enough and red when it's short, plus a header that flags an incomplete build. */
    private List<Component> printTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.customglint.glint_table.print").withStyle(ChatFormatting.WHITE));

        // When blocked, spell out exactly what's missing instead of a vague "incomplete".
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
        // Simultaneous tears: one per simultaneous layer in the whole trim (committed + active).
        int simTears = simTearCost();
        if (simTears > 0) {
            reqLine(lines, itemName(ModItems.GLINT_TEAR_SIMULTANEOUS.get()), simTears, GlintTableMenu.SLOT_TEAR);
            any = true;
        }
        // Sequential tear only when reverting a simultaneous source layer back to sequential.
        if (activeSourceSim && !tearSimultaneous) {
            reqLine(lines, itemName(ModItems.GLINT_TEAR_SEQUENTIAL.get()), 1, GlintTableMenu.SLOT_TEAR_SEQ);
            any = true;
        }
        if (!any) lines.add(Component.translatable("screen.customglint.glint_table.nothing").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** One material line for {@link #printTooltip}: name + amount, green when the slot holds enough. */
    private void reqLine(List<Component> lines, Component name, int need, int slotConst) {
        int have = menu.slots.get(slotConst).getItem().getCount();
        lines.add(Component.translatable("screen.customglint.glint_table.consume_line", name, need)
                .withStyle(have >= need ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    // ── Beveled widgets ───────────────────────────────────────────────────────

    /**
     * A skinned button backed by the widget system. The label, text colour and base face are pulled live
     * each frame from suppliers (so a button can show changing state without being recreated); drawing uses
     * the active skin's bevel. Left-click only by default; the skin button opts into right-click too.
     */
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
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
            int face = (active && isHovered()) ? BTN_HOVER : faceColor.getAsInt();
            raisedPanel(g, getX(), getY(), getWidth(), getHeight(), face);
            centered(g, label.get(), getX() + getWidth() / 2, getY() + textDy, textColor.getAsInt());
        }

        @Override
        protected boolean isValidClickButton(MouseButtonInfo info) {
            return info.button() == 0 || (rightToo && info.button() == 1);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onPress.accept(event.button());
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            if (GlintClientConfig.glintTableSound()) super.playDownSound(soundManager);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, Component.literal(label.get()));
        }
    }

    /** Create the table's buttons as widgets. Positions are fixed (slot-relative); labels/colours update live. */
    private void addButtons() {
        Slot glow = menu.slots.get(GlintTableMenu.SLOT_GLOWSTONE);
        Slot nameS = menu.slots.get(GlintTableMenu.SLOT_NAMETAG);
        Slot tear = menu.slots.get(GlintTableMenu.SLOT_TEAR);

        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X, topPos + SKIN_BTN_Y, SKIN_BTN_W, SKIN_BTN_H, 2, true,
                () -> Component.translatable("screen.customglint.skin." + skin.name.toLowerCase(Locale.ROOT)).getString(),
                () -> LABEL_HDR, () -> GUI_FACE, b -> cycleSkin(b == 1 ? -1 : 1)));

        addRenderableWidget(new BevelButton(leftPos + SKIN_BTN_X - SND_BTN_W - 2, topPos + SKIN_BTN_Y, SND_BTN_W, SKIN_BTN_H, 2, false,
                () -> "♪", () -> GlintClientConfig.glintTableSound() ? LABEL_HDR : COST_BAD, () -> GUI_FACE,
                b -> GlintClientConfig.setGlintTableSound(!GlintClientConfig.glintTableSound())));

        printBtn = addRenderableWidget(new BevelButton(leftPos + PRINT_X, topPos + PRINT_Y, PRINT_W, PRINT_H, 3, false,
                () -> Component.translatable("screen.customglint.glint_table.print").getString(), () -> canPrint() ? LABEL_HDR : COST_BAD, () -> canPrint() ? GUI_FACE : BTN_DISABLED,
                b -> { if (canPrint()) onPrint(); }));

        addRenderableWidget(new BevelButton(leftPos + INTERP_X, topPos + INTERP_Y, INTERP_W, INTERP_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.interpolation", boolLabel(modInterpolate)).getString(), () -> modInterpolate ? COST_OK : LABEL_HDR,
                () -> GUI_FACE, b -> modInterpolate = !modInterpolate));

        addRenderableWidget(new BevelButton(leftPos + SCROLL_X, topPos + SCROLL_Y, SCROLL_W, SCROLL_H, 2, false,
                () -> Component.translatable("screen.customglint.glint_table.scroll", GlintTrimItem.scrollLabel(modScrollDir)).getString(), () -> LABEL_HDR, () -> GUI_FACE,
                b -> modScrollDir = (modScrollDir + 1) % 9));

        addRenderableWidget(new BevelButton(tearToggleCx() - 15, topPos + tear.y + 26, 30, 11, 2, false,
                () -> Component.translatable(tearSimultaneous ? "screen.customglint.glint_table.sim" : "screen.customglint.glint_table.seq").getString(), () -> LABEL_HDR, () -> GUI_FACE,
                b -> tearSimultaneous = !tearSimultaneous));

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

        // −/+ steppers for speed / opacity / scale (the value + label between them are drawn by the screen).
        addStepperPair(GlintTableMenu.SLOT_REDSTONE, () -> modSpeed = stepDown(modSpeed), () -> modSpeed = stepUp(modSpeed));
        addStepperPair(GlintTableMenu.SLOT_GLASS, () -> modOpacity = Math.max(0, modOpacity - 1), () -> modOpacity = Math.min(8, modOpacity + 1));
        addStepperPair(GlintTableMenu.SLOT_SLIME, () -> modScale = stepDown(modScale), () -> modScale = stepUp(modScale));

        int ox = leftPos + SCROLL_X + SCROLL_W / 2, oy = topPos + SCROLL_OFF_Y;
        offsetMinus = stepper(ox - 15, oy, "-", () -> modScrollOffset = Math.max(0.0f, Math.round((modScrollOffset - 0.05f) * 20) / 20.0f));
        offsetPlus  = stepper(ox + 6,  oy, "+", () -> modScrollOffset = Math.min(1.0f, Math.round((modScrollOffset + 0.05f) * 20) / 20.0f));
    }

    /** Add a −/+ stepper pair under a modifier slot, at the same offsets the value text is centred on. */
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

    /** Refresh per-frame clickability (active) for the buttons whose enabled state depends on the build. */
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

    /** Localized "True"/"False" for the boolean toggle buttons. */
    private static Component boolLabel(boolean value) {
        return Component.translatable(value ? "screen.customglint.glint_table.true" : "screen.customglint.glint_table.false");
    }

    /** Localized "N/A" shown when a toggle is unavailable. */
    private static String naLabel() {
        return Component.translatable("screen.customglint.glint_table.na").getString();
    }

    /** Resolved text for a {@code screen.customglint.glint_table.<suffix>} caption key. */
    private static String label(String suffix) {
        return Component.translatable("screen.customglint.glint_table." + suffix).getString();
    }

    private int glowModeColor() {
        return (glowTrimMain() || !modGlow) ? COST_BAD : LABEL_HDR;
    }

    /** Flat (no drop-shadow) centered text, like the vanilla container labels. */
    private void centered(GuiGraphicsExtractor g, String str, int cx, int y, int color) {
        g.text(font, str, cx - font.width(str) / 2, y, color, false);
    }

    /** 1px rectangle outline. */
    private void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // The panel/bevel primitives delegate to the active skin so a skin can restyle them (gradients, tiling,
    // rivets) without changing any call site here. The default skin draws the original flat bevel.
    private void raisedPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int face) {
        skin.raised(g, x, y, w, h, face);
    }

    private void shardBevel(GuiGraphicsExtractor g, int x, int y, int s) {
        skin.shardBevel(g, x, y, s);
    }

    private void slotWell(GuiGraphicsExtractor g, int sx, int sy) {
        skin.slotWell(g, sx, sy);
    }

    private String modValue(int slotConst) {
        return switch (slotConst) {
            case GlintTableMenu.SLOT_REDSTONE -> fmtVal(modSpeed) + "×";           // speed
            case GlintTableMenu.SLOT_GLASS    -> Math.round(modOpacity * 100f / 8f) + "%"; // translucency
            case GlintTableMenu.SLOT_SLIME    -> fmtVal(modScale) + "×";           // scale
            default -> "";
        };
    }

    /** Label + value + [-]/[+] under the speed / opacity / scale slots, plus plain labels under the
     *  glow / name / tear slots (always shown). */
    private void drawModifierControls(GuiGraphicsExtractor g) {
        drawModControl(g, GlintTableMenu.SLOT_REDSTONE, label("speed"));
        drawModControl(g, GlintTableMenu.SLOT_GLASS,    label("opacity"));
        drawModControl(g, GlintTableMenu.SLOT_SLIME,    label("scale"));
        drawControlLabel(g, GlintTableMenu.SLOT_GLOWSTONE, label("glow"));
        drawControlLabel(g, GlintTableMenu.SLOT_NAMETAG,   label("name_label"));
        drawTearLabel(g);
        // The static-offset value sits between its −/+ stepper widgets, shown only while on Static.
        if (modScrollDir == CustomGlint.SCROLL_STATIC) {
            fitValue(g, String.format("%.2f", modScrollOffset), leftPos + SCROLL_X + SCROLL_W / 2, topPos + SCROLL_OFF_Y + 1);
        }
    }

    /** Draw a stepper value centered at (cx, y), shrunk so it always fits the ~12px gap between the −/+
     *  buttons (capped at 0.8×) instead of bleeding into them on wide values like "100%" / "0.85". */
    private void fitValue(GuiGraphicsExtractor g, String val, int cx, int y) {
        int w = font.width(val);
        float vs = w > 0 ? Math.min(0.8f, 11f / w) : 0.8f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, y);
        pose.scale(vs, vs);
        centered(g, val, 0, 0, LABEL_HDR);
        pose.popMatrix();
    }

    /** The tear slot matching the toggled mode (its tear is the one previewed / consumed). */
    private int activeTearSlot() {
        return tearSimultaneous ? GlintTableMenu.SLOT_TEAR : GlintTableMenu.SLOT_TEAR_SEQ;
    }

    /** Center-x of the shared tear toggle: the midpoint between the two side-by-side tear slots. */
    private int tearToggleCx() {
        return (leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).x + 8
                + leftPos + menu.slots.get(GlintTableMenu.SLOT_TEAR_SEQ).x + 8) / 2;
    }

    /** A glow/name toggle is usable when its material is in the slot, OR the placed trim already carries
     *  that property (it already paid for it, so re-printing is free, mirrors the server's print()). */
    private boolean toggleAvailable(int slotConst) {
        if (menu.slots.get(slotConst).hasItem()) return true;
        ItemStack base = menu.slots.get(GlintTableMenu.SLOT_TRIM).getItem();
        if (!(base.getItem() instanceof GlintTrimItem)) return false;
        if (slotConst == GlintTableMenu.SLOT_GLOWSTONE) return CustomGlint.isGlowing(base);
        if (slotConst == GlintTableMenu.SLOT_NAMETAG)   return base.has(DataComponents.CUSTOM_NAME);
        return false;
    }

    /** The caption above a control slot (Glow / Name); the True/False button itself is a widget. */
    private void drawControlLabel(GuiGraphicsExtractor g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    /** The "Type" caption + the dimming of whichever tear slot isn't the toggled one; the Sim/Seq button
     *  itself is a widget. */
    private void drawTearLabel(GuiGraphicsExtractor g) {
        int cx = tearToggleCx(), top = topPos + menu.slots.get(GlintTableMenu.SLOT_TEAR).y;
        smallLabel(g, label("type"), cx, top + 19, LABEL_HDR);
        Slot off = menu.slots.get(tearSimultaneous ? GlintTableMenu.SLOT_TEAR_SEQ : GlintTableMenu.SLOT_TEAR);
        int dx = leftPos + off.x - 1, dy = topPos + off.y - 1;
        g.fill(dx, dy, dx + 18, dy + 18, DIM_GHOST);
    }

    /** Label + value under a modifier slot; the −/+ stepper buttons themselves are widgets. */
    private void drawModControl(GuiGraphicsExtractor g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        int cx = leftPos + s.x + 8, top = topPos + s.y;
        smallLabel(g, label, cx, top + 19, LABEL_HDR);
        fitValue(g, modValue(slotConst), cx, top + 27); // centered between the −/+ buttons, shrunk to fit
    }

    private void drawSectionLabels(GuiGraphicsExtractor g) {
        int x = leftPos, y = topPos;
        g.text(font, label("empty_trims"), x + LGRID_X, y + GRID_Y - 11, LABEL_HDR, false);
        g.text(font, label("printed"),     x + RGRID_X, y + GRID_Y - 11, LABEL_HDR, false);

        // Labels beneath the three top-row trim slots (main / layer-tear / layered).
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM,       label("main"));
        topSlotLabel(g, GlintTableMenu.SLOT_LAYER_TEAR, label("layer"));
        topSlotLabel(g, GlintTableMenu.SLOT_TRIM_B,     label("merge"));
    }

    private void topSlotLabel(GuiGraphicsExtractor g, int slotConst, String label) {
        Slot s = menu.slots.get(slotConst);
        smallLabel(g, label, leftPos + s.x + 8, topPos + s.y + 19, LABEL_HDR);
    }

    /** A 0.7× centered label, used for every slot caption so they share one size. */
    private void smallLabel(GuiGraphicsExtractor g, String label, int cx, int y, int color) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, y);
        pose.scale(0.7f, 0.7f);
        centered(g, label, 0, 0, color);
        pose.popMatrix();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private static boolean isTrim(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem || stack.getItem() instanceof GlowTrimItem;
    }

    /** True when (mx, my) is over the cell area of either scrollable grid. */
    private boolean overEitherGrid(double mx, double my) {
        return overGrid(mx, my, LGRID_X) || overGrid(mx, my, RGRID_X);
    }

    private boolean overGrid(double mx, double my, int gx) {
        int x0 = leftPos + gx, y0 = topPos + GRID_Y;
        return mx >= x0 && mx < x0 + GRID_COLS * CELL && my >= y0 && my < y0 + GRID_ROWS * CELL;
    }

    /** Whether (mx, my) is over the 16×16 item area of a table slot. */
    private boolean overSlot(double mx, double my, int slotConst) {
        Slot s = menu.slots.get(slotConst);
        int sx = leftPos + s.x, sy = topPos + s.y;
        return mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16;
    }

    /** The grid index under (mx, my) for a grid at (gx, gy), or -1. */
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int x = leftPos, y = topPos;

        // A click inside the open hex box goes to the box (and is consumed) so it never falls through to a
        // modifier button drawn beneath it.
        if (hexOpen && hexBox != null && hexBox.isVisible()
                && mx >= hexBox.getX() && mx < hexBox.getX() + hexBox.getWidth()
                && my >= hexBox.getY() && my < hexBox.getY() + hexBox.getHeight()) {
            return hexBox.mouseClicked(event, doubleClick);
        }

        // Right-click the main slot clears the whole working trim; right-click the merge slot clears it. Any
        // physical trim in the slot is shift-moved back to the inventory first so it isn't lost.
        if (event.button() == 1 && menu.getCarried().isEmpty()) {
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

        // Drag-in deposit: with a trim held on the cursor, clicking either scrollable grid deposits it into
        // the library (the grids aren't real slots, so this is the click-drag equivalent of shift-clicking).
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && isTrim(carried) && overEitherGrid(mx, my)) {
            ClientPacketDistributor.sendToServer(GlintDepositPacket.INSTANCE);
            return true;
        }

        // Rainbow dye in the glow / name colour slot → right-click toggles its custom-hex picker (instead of
        // the vanilla slot pickup), so you can type any hex for the glow ring / item name colour. Right-clicking
        // the same slot again closes the box (mirrors the custom-shard toggle).
        if (event.button() == 1) {
            if (menu.showGlowDye && rainbowInSlot(GlintTableMenu.SLOT_GLOW_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_GLOW_DYE)) {
                if (hexOpen && hexMode == HEX_GLOW) closeHex(); else openHexColor(HEX_GLOW);
                return true;
            }
            if (menu.showNameDye && rainbowInSlot(GlintTableMenu.SLOT_NAME_DYE) && overSlot(mx, my, GlintTableMenu.SLOT_NAME_DYE)) {
                if (hexOpen && hexMode == HEX_NAME) closeHex(); else openHexColor(HEX_NAME);
                return true;
            }
        }

        // Layer strip: chips (left-click = edit a committed layer / + = add layer; right-click = remove)
        if (layerStripVisible()) {
            int cy = y + LAYER_STRIP_Y;
            for (Chip c : layerChips()) {
                int cx = x + c.x();
                if (mx < cx || mx >= cx + LAYER_ICON || my < cy || my >= cy + LAYER_ICON) continue;
                if (c.kind() == 3) { if (event.button() == 0) addLayer(); }
                else if (c.kind() == 1) { /* active chip, already editing */ }
                else if (event.button() == 1) { // remove
                    if (c.kind() == 0) lowerLayers.remove(c.index()); else upperLayers.remove(c.index());
                } else editLayer(c.kind(), c.index());
                return true;
            }
        }

        // Color-shard strip: right-click a shard removes that color instance.
        if (colorStripClick(mx, my, event.button())) return true;

        // Dye bar: right-click a dye sets the SELECTED color shard's colour; shift-right-click MIXES the dye
        // into the shard (several dyes average into a custom colour not in the dye table). Fully consumed so
        // vanilla never grabs/moves the dye stack; left-click is still vanilla pickup.
        if (event.button() == 1) {
            boolean shardSel = selectedColorIdx >= 0 && selectedColorIdx < colorShards.size();
            for (int i = 0; i < 16; i++) {
                Slot s = menu.slots.get(GlintTableMenu.SLOT_DYE_START + i);
                int sx = x + s.x, sy = y + s.y;
                if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) {
                    if (s.hasItem() && menu.getCarried().isEmpty() && shardSel) {
                        List<Integer> shard = colorShards.get(selectedColorIdx);
                        if (isCustomShard(shard)) { shard.clear(); shard.add(i); closeHex(); } // leave rainbow mode
                        else if (hasShiftDown()) { if (!shard.contains(i) && shard.size() < 8) shard.add(i); } // mix in
                        else { shard.clear(); shard.add(i); }                                   // set as the sole colour
                    }
                    return true; // swallow the right-click regardless, no vanilla pickup/place here
                }
            }
            // Rainbow dye → put the selected shard into custom-hex (rainbow) mode; click the shard to type a hex.
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

        // Grid scrollbars, left-click or drag the thumb to scroll (matches the creative inventory).
        if (event.button() == 0) {
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

        // Left grid (1), left-click picks the main design; right-click sets it as the merge donor (slot 2).
        int li = gridIndexAt(mx, my, x + LGRID_X, y + GRID_Y, gridScroll, trims.size());
        if (li >= 0) {
            String name = trims.get(li);
            // Shift-left-click pulls a free blank copy of an owned design into the inventory.
            if (event.button() == 0 && hasShiftDown()) {
                if (GlintStoredSyncPacket.CLIENT_STORED.contains(name))
                    ClientPacketDistributor.sendToServer(new GlintGiveDesignPacket(name));
                return true;
            }
            if (event.button() == 1) {
                boolean same = !selectedDonorPrinted && name.equals(trimDesignName(selectedDonor));
                selectedDonor = same ? ItemStack.EMPTY : trimCache.getOrDefault(name, ItemStack.EMPTY).copy();
                selectedDonorPrinted = false;
            } else { // swap the active layer's design only, colors and the other build state carry over
                selectedMain = name; selectedPrinted = ItemStack.EMPTY;
            }
            return true;
        }

        // Right grid (2), left-click puts a printed trim in the main slot (the base, ghosted in slot 1);
        // right-click puts it in the merge slot (the donor, ghosted in slot 2). Both are preview selections,
        // not physical items (re-print to materialise a copy).
        int ri = gridIndexAt(mx, my, x + RGRID_X, y + GRID_Y, printScroll, GlintPrintedSyncPacket.CLIENT_PRINTED.size());
        if (ri >= 0) {
            // Shift-left-click pulls the trim out of the library into the inventory (no-op if it's full).
            if (event.button() == 0 && hasShiftDown()) {
                ClientPacketDistributor.sendToServer(new GlintWithdrawPacket(ri));
                return true;
            }
            ItemStack picked = GlintPrintedSyncPacket.CLIENT_PRINTED.get(ri).copy();
            if (event.button() == 1) {
                boolean same = selectedDonorPrinted && ItemStack.isSameItemSameComponents(picked, selectedDonor);
                selectedDonor = same ? ItemStack.EMPTY : picked;
                selectedDonorPrinted = !same;
            } else {
                loadFromTrim(picked);
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    /** Send the current build to the server to consume materials/dyes and print the finished trim. The
     *  active layer rides the fields; the committed layers ride along as below/above tags. */
    private void onPrint() {
        ItemStack src = activeTrim();
        if (src.isEmpty() || !(src.getItem() instanceof GlintTrimItem)) return;
        Identifier design = GlintTrimItem.getPattern(src);
        if (design == null) return;
        // Each shard with a real colour: dye indices (one = pure, several = mix), or a single CUSTOM_FLAG|rgb
        // for a rainbow custom hex. Skip empty / rainbow-pending shards (no colour yet).
        List<int[]> shards = new ArrayList<>();
        for (List<Integer> shard : colorShards) {
            if (mixRgb(shard) >= 0) shards.add(shard.stream().mapToInt(Integer::intValue).toArray());
        }
        int[][] shardDyes = shards.toArray(new int[0][]);
        CustomGlint.Layer[] below = lowerLayers.toArray(new CustomGlint.Layer[0]);
        CustomGlint.Layer[] above = upperLayers.toArray(new CustomGlint.Layer[0]);
        ClientPacketDistributor.sendToServer(new GlintPrintPacket(
                design.toString(), modSpeed, modScale, modOpacity, modGlow, glowAuto, modNamed, trimName, tearSimultaneous,
                modScrollDir, modScrollOffset, modInterpolate, glowHex, nameHex, shardDyes, donorColors(), below, above, activeSourceSim));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter closes whichever text box is being edited (mirrors the right-click-to-close toggle on the slots).
        if (event.key() == 257 || event.key() == 335) { // Return / numpad Enter
            if (hexOpen) { closeHex(); setFocused(null); return true; }
            if (nameBox != null && nameBox.isFocused()) { nameBox.setFocused(false); setFocused(null); return true; }
        }
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && event.key() != 256) {
            nameBox.keyPressed(event);
            return true; // swallow typing (incl. the inventory key) while editing the name
        }
        // Same for the hex box, its A–F digits include the default inventory key (E), which would otherwise
        // close the screen instead of entering the digit.
        if (hexBox != null && hexBox.isVisible() && hexBox.isFocused() && event.key() != 256) {
            hexBox.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (nameBox != null && nameBox.isVisible() && nameBox.isFocused() && nameBox.charTyped(event)) return true;
        if (hexBox != null && hexBox.isVisible() && hexBox.isFocused() && hexBox.charTyped(event)) return true;
        return super.charTyped(event);
    }

    private boolean inGrid(double mx, double my, int gx, int gy) {
        return mx >= gx && mx < gx + GRID_COLS * CELL && my >= gy && my < gy + GRID_ROWS * CELL;
    }

    private int scrolled(int cur, int total, double dir) {
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        int maxRow = Math.max(0, rows - GRID_ROWS);
        return Math.max(0, Math.min(maxRow, cur - (int) Math.signum(dir)));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingGrid == 0) { gridScroll = scrollFromMouse(event.y(), topPos + GRID_Y, trims.size()); return true; }
        if (draggingGrid == 1) { printScroll = scrollFromMouse(event.y(), topPos + GRID_Y, printedCapacity()); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingGrid = -1;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int x = leftPos, y = topPos;
        if (inGrid(mx, my, x + LGRID_X, y + GRID_Y)) {
            gridScroll = scrolled(gridScroll, trims.size(), scrollY);
            return true;
        }
        if (inGrid(mx, my, x + RGRID_X, y + GRID_Y)) {
            printScroll = scrolled(printScroll, printedCapacity(), scrollY);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
