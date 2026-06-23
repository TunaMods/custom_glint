package net.tunamods.customglint.module.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.GlintClientConfig;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.network.GlintApplyPacket;
import net.tunamods.customglint.module.network.GiveGlintTrimPacket;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class GlintEditorScreen extends Screen {

    // ── Layout constants ────────────────────────────────────────────────────
    private static final int PANEL_W    = 300;
    private static final int PANEL_H    = 270;
    private static final int PREVIEW_SZ = 80;

    // ── Active skin ───────────────────────────────────────────────────────────
    // The window frame, divider and preview recess are baked into the skin PNG; the palette colours every
    // element the screen draws on top (buttons, swatches, labels). Persisted separately from the table skin.
    private GlintWandSkin skin = GlintWandSkin.DEFAULT;

    /** Step the skin by {@code dir} (+1 next / -1 previous, wrapping) and persist the choice. */
    private void cycleSkin(int dir) {
        int n = GlintWandSkin.ALL.length;
        int idx = Math.floorMod(GlintWandSkin.indexOf(skin) + dir, n);
        skin = GlintWandSkin.ALL[idx];
        GlintClientConfig.setGlintWandSkin(idx);
    }

    @Override
    public void removed() {
        GlintClientConfig.flush(); // persist any skin/sound toggles once, off the per-click path
        super.removed();
    }

    private static Identifier designRL(String name) {
        return CustomGlint.designFromName(name);
    }

    private static String designShortName(Identifier rl) {
        if (rl.equals(CustomGlint.VANILLA)) return "vanilla";
        String p = rl.getPath();
        String base = p.startsWith("textures/glint/") ? p.substring("textures/glint/".length()) : p;
        String name = base.endsWith(".png") ? base.substring(0, base.length() - 4) : base;
        return rl.getNamespace().equals("customglint") ? name : rl.getNamespace() + ":" + name;
    }

    // Item display names resolve a Component + format a String; the picker draws them every frame for every
    // visible row and the filter walks them on each search. Items are registry singletons, so the resolved
    // name is stable, cache it. Icon ItemStacks are cached the same way to skip the per-row allocation.
    private static final Map<Item, String> ITEM_NAME_CACHE = new IdentityHashMap<>();
    private static final Map<Item, ItemStack> ITEM_ICON_CACHE = new IdentityHashMap<>();

    private static String itemName(Item item) {
        return ITEM_NAME_CACHE.computeIfAbsent(item, it -> new ItemStack(it).getHoverName().getString());
    }

    private static ItemStack itemIcon(Item item) {
        return ITEM_ICON_CACHE.computeIfAbsent(item, ItemStack::new);
    }

    // ── State ───────────────────────────────────────────────────────────────
    private final InteractionHand wandHand;

    // per-layer state
    private final List<String>        layerDesigns      = new ArrayList<>();
    private final List<List<Integer>> layerColors       = new ArrayList<>();
    private final List<Float>         layerSpeeds       = new ArrayList<>();
    private final List<Boolean>       layerInterpolates = new ArrayList<>();
    private final List<Float>         layerScales       = new ArrayList<>();
    private final List<Boolean>       layerSimultaneous = new ArrayList<>();
    private final List<Integer>       layerScrollDirs   = new ArrayList<>();
    private final List<Float>         layerScrollOffsets = new ArrayList<>();
    private int selectedLayer = 0;

    private int editingColorIdx = 0;

    // glow state
    private boolean glowEnabled = false;
    private final List<Integer> glowOverrideColors = new ArrayList<>();
    private boolean editingGlowColor = false;
    private int editingGlowColorIdx = 0;

    // trim name and color
    private String trimName = "";
    private int trimNameColor = 0xFFFFFFFF;

    private int editR = 0x88, editG = 0x44, editB = 0xEE, editA = 0xFF;

    private Item      previewItem  = Items.NETHERITE_SWORD;
    private ItemStack previewStack = ItemStack.EMPTY;

    // ── Item-picker overlay ─────────────────────────────────────────────────
    private boolean    showPicker    = false;
    private List<Item> allItems      = null;
    private List<Item> filteredItems = new ArrayList<>();
    private int        pickerScroll  = 0;
    private static final int VISIBLE_ROWS = 8, ROW_H = 18;

    // ── Design-picker overlay ───────────────────────────────────────────────
    private boolean      showDesignPicker = false;
    private List<String> filteredDesigns  = new ArrayList<>();
    private int          designScroll     = 0;
    private EditBox      designSearchBox;
    private static final int DESIGN_ROWS = 10, DESIGN_ROW_H = 13;

    // ── Glint import overlay ──────────────────────────────────────────────────
    private boolean      showImportPicker = false;
    /** Full scanned list; {@link #availableGlints} is the search-filtered view rendered in the picker. */
    private List<String> allGlints        = new ArrayList<>();
    private List<String> availableGlints  = new ArrayList<>();
    private int          importScroll     = 0;
    private EditBox      importSearchBox;
    private static final int IMPORT_ROWS = 10, IMPORT_ROW_H = 13;

    // ── Widget refs ─────────────────────────────────────────────────────────
    private EditBox hexBox, rBox, gBox, bBox, aBox;
    private EditBox searchBox;
    private EditBox trimNameBox, nameHexBox;
    private EditBox glowHexBox;
    private BevelButton skinBtn, soundBtn;

    private int px, py;

    // ── Construction ────────────────────────────────────────────────────────

    public GlintEditorScreen(InteractionHand hand) {
        super(Component.translatable("screen.customglint.glint_editor.title"));
        this.wandHand = hand;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ItemStack wandStack = mc.player.getItemInHand(hand);
            CustomGlint.Data d = CustomGlint.read(wandStack);
            if (d != null) {
                for (CustomGlint.Layer layer : d.layers()) {
                    layerDesigns.add(designShortName(layer.design()));
                    List<Integer> lc = new ArrayList<>();
                    for (int c : layer.colors()) lc.add(c);
                    layerColors.add(lc);
                    layerSpeeds.add(layer.speed());
                    layerInterpolates.add(layer.interpolate());
                    layerScales.add(layer.patternScale());
                    layerSimultaneous.add(layer.simultaneous());
                    layerScrollDirs.add(layer.scrollDir());
                    layerScrollOffsets.add(layer.scrollOffset());
                }
            }
            glowEnabled = CustomGlint.isGlowing(wandStack);
            for (int c : CustomGlint.getGlowColors(wandStack)) glowOverrideColors.add(c);
        }
        if (layerDesigns.isEmpty()) {
            layerDesigns.add("sparkle");
            List<Integer> lc = new ArrayList<>();
            lc.add(0xFF8844EE);
            layerColors.add(lc);
            layerSpeeds.add(1.0f);
            layerInterpolates.add(true);
            layerScales.add(1.0f);
            layerSimultaneous.add(true);
            layerScrollDirs.add(CustomGlint.SCROLL_E);
            layerScrollOffsets.add(0.0f);
        }
        loadEditRGB();
    }

    // ── Color helpers ────────────────────────────────────────────────────────

    private List<Integer> currentColors() {
        return layerColors.get(selectedLayer);
    }

    private void loadEditRGB() {
        int c;
        if (editingGlowColor && editingGlowColorIdx < glowOverrideColors.size()) {
            c = glowOverrideColors.get(editingGlowColorIdx);
        } else {
            List<Integer> colors = currentColors();
            c = editingColorIdx < colors.size() ? colors.get(editingColorIdx) : 0xFF8844EE;
        }
        editA = (c >> 24) & 0xFF;
        editR = (c >> 16) & 0xFF;
        editG = (c >>  8) & 0xFF;
        editB =  c        & 0xFF;
    }

    private void saveEditRGB() {
        if (editingGlowColor && editingGlowColorIdx < glowOverrideColors.size()) {
            glowOverrideColors.set(editingGlowColorIdx, (editA << 24) | (editR << 16) | (editG << 8) | editB);
        } else {
            List<Integer> colors = currentColors();
            if (editingColorIdx < colors.size())
                colors.set(editingColorIdx, (editA << 24) | (editR << 16) | (editG << 8) | editB);
        }
    }

    private void syncHexFromRGB() {
        if (hexBox == null) return;
        hexBox.setResponder(null);
        hexBox.setValue(String.format("%06X", (editR << 16) | (editG << 8) | editB));
        hexBox.setResponder(this::onHexChanged);
    }

    private void syncGlowHexBox() {
        if (glowHexBox == null || editingGlowColorIdx >= glowOverrideColors.size()) return;
        glowHexBox.setResponder(null);
        glowHexBox.setValue(String.format("%06X", glowOverrideColors.get(editingGlowColorIdx) & 0xFFFFFF));
        glowHexBox.setResponder(s -> {
            if (s.length() == 6 && editingGlowColorIdx < glowOverrideColors.size()) {
                try {
                    int rgb = Integer.parseInt(s, 16);
                    int alpha = (glowOverrideColors.get(editingGlowColorIdx) >> 24) & 0xFF;
                    glowOverrideColors.set(editingGlowColorIdx, (alpha << 24) | rgb);
                    refreshPreview();
                } catch (NumberFormatException ignored) {}
            }
        });
    }

    private void syncChannelBoxes() {
        if (rBox != null) { rBox.setResponder(null); rBox.setValue(String.valueOf(editR)); rBox.setResponder(this::onRChanged); }
        if (gBox != null) { gBox.setResponder(null); gBox.setValue(String.valueOf(editG)); gBox.setResponder(this::onGChanged); }
        if (bBox != null) { bBox.setResponder(null); bBox.setValue(String.valueOf(editB)); bBox.setResponder(this::onBChanged); }
        if (aBox != null) { aBox.setResponder(null); aBox.setValue(String.valueOf(editA)); aBox.setResponder(this::onAChanged); }
    }

    // ── EditBox responders ───────────────────────────────────────────────────

    private void onHexChanged(String s) {
        if (s.length() != 6) return;
        try {
            int rgb = Integer.parseInt(s, 16);
            editR = (rgb >> 16) & 0xFF;
            editG = (rgb >>  8) & 0xFF;
            editB =  rgb        & 0xFF;
            saveEditRGB();
            syncChannelBoxes();
            refreshPreview();
        } catch (NumberFormatException ignored) {}
    }

    private void onRChanged(String s) {
        try {
            int v = Integer.parseInt(s);
            int c = Math.max(0, Math.min(255, v));
            editR = c; saveEditRGB(); syncHexFromRGB(); refreshPreview();
            if (c != v) syncChannelBoxes();
        } catch (NumberFormatException ignored) {}
    }

    private void onGChanged(String s) {
        try {
            int v = Integer.parseInt(s);
            int c = Math.max(0, Math.min(255, v));
            editG = c; saveEditRGB(); syncHexFromRGB(); refreshPreview();
            if (c != v) syncChannelBoxes();
        } catch (NumberFormatException ignored) {}
    }

    private void onBChanged(String s) {
        try {
            int v = Integer.parseInt(s);
            int c = Math.max(0, Math.min(255, v));
            editB = c; saveEditRGB(); syncHexFromRGB(); refreshPreview();
            if (c != v) syncChannelBoxes();
        } catch (NumberFormatException ignored) {}
    }

    private void onAChanged(String s) {
        try {
            int v = Integer.parseInt(s);
            int c = Math.max(0, Math.min(255, v));
            editA = c; saveEditRGB(); refreshPreview();
            if (c != v) syncChannelBoxes();
        } catch (NumberFormatException ignored) {}
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    /** Snapshot the editor's per-layer state into a {@link CustomGlint.Layer} array (preview + packets). */
    private CustomGlint.Layer[] buildLayers() {
        CustomGlint.Layer[] layers = new CustomGlint.Layer[layerDesigns.size()];
        for (int i = 0; i < layers.length; i++) {
            int[] arr = layerColors.get(i).stream().mapToInt(Integer::intValue).toArray();
            layers[i] = new CustomGlint.Layer(designRL(layerDesigns.get(i)), arr,
                    layerSpeeds.get(i), layerInterpolates.get(i), layerScales.get(i), layerSimultaneous.get(i),
                    layerScrollDirs.get(i), layerScrollOffsets.get(i));
        }
        return layers;
    }

    private void refreshPreview() {
        previewStack = new ItemStack(previewItem);
        CustomGlint.Layer[] layers = buildLayers();
        CustomGlint.write(previewStack, layers);
        CustomGlint.clearGlowColors(previewStack);
        CustomGlint.setGlowing(previewStack, glowEnabled);
        if (glowEnabled && !glowOverrideColors.isEmpty()) {
            int[] gc = glowOverrideColors.stream().mapToInt(Integer::intValue).toArray();
            CustomGlint.setGlowColors(previewStack, gc);
        }
    }

    // ── Design picker helpers ────────────────────────────────────────────────

    private void filterDesigns(String query) {
        String lq = query.toLowerCase();
        filteredDesigns = lq.isEmpty() ? new ArrayList<>(GlintTrimItem.PATTERNS) : GlintTrimItem.PATTERNS.stream()
                .filter(d -> d.toLowerCase().contains(lq)) // lowercase the pattern too, matching the item/glint filters
                .collect(Collectors.toList());
        designScroll = Math.max(0, Math.min(designScroll, Math.max(0, filteredDesigns.size() - DESIGN_ROWS)));
    }

    private static final int DPW = 190, DPH = DESIGN_ROWS * DESIGN_ROW_H + 20;
    private static final int IPW = 190, IPH = IMPORT_ROWS * IMPORT_ROW_H + 20;

    private int dpX() { return px + 98; }
    private int dpY() { return py + 38; }
    private int ipX() { return px + 98; }
    private int ipY() { return py + 54; }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        px = (width  - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;
        skin = GlintWandSkin.byIndex(GlintClientConfig.glintWandSkin());
        // Warm all skin background textures now (idempotent) so cycling skins doesn't cold-load a PNG mid-click.
        GlintWandSkin.preloadTextures();

        // Skin cycle (left-click next, right-click previous) + button-sound toggle, bottom of the left column.
        skinBtn = addRenderableWidget(new BevelButton(px + 8, py + 232, 62, 14, 3, true,
                () -> Component.translatable("screen.customglint.skin." + skin.name.toLowerCase(Locale.ROOT)).getString(),
                () -> skin.labelHdr, () -> skin.guiFace, b -> cycleSkin(b == 1 ? -1 : 1)));
        soundBtn = addRenderableWidget(new BevelButton(px + 72, py + 232, 14, 14, 3, false,
                () -> "♪", () -> GlintClientConfig.glintTableSound() ? skin.costOk : skin.costBad,
                () -> skin.guiFace, b -> GlintClientConfig.setGlintTableSound(!GlintClientConfig.glintTableSound())));

        // Design picker trigger button, full right-column width, shows current design
        bevel(px + 100, py + 22, PANEL_W - 104, 14,
                () -> Component.translatable("screen.customglint.glint_editor.design_button",
                        layerDesigns.get(selectedLayer)).getString(),
                () -> {
                    filterDesigns(designSearchBox != null ? designSearchBox.getValue() : "");
                    designScroll = Math.max(0, filteredDesigns.indexOf(layerDesigns.get(selectedLayer)));
                    showDesignPicker = true;
                    if (designSearchBox != null) designSearchBox.setFocused(true);
                });

        // Design search box, managed manually (not added to renderables)
        designSearchBox = new EditBox(font, 0, 0, DPW - 4, 12, Component.translatable("screen.customglint.glint_editor.search_designs"));
        designSearchBox.setMaxLength(30);
        designSearchBox.setResponder(s -> { designScroll = 0; filterDesigns(s); });
        filterDesigns("");

        // Remove last color [−]
        bevel(px + 100 + currentColors().size() * 18, py + 50, 14, 14, () -> "−", () -> {
            List<Integer> colors = currentColors();
            if (colors.size() > 1) {
                colors.remove(colors.size() - 1);
                if (editingColorIdx >= colors.size()) editingColorIdx = colors.size() - 1;
                loadEditRGB();
                rebuildWidgets();
            }
        });

        // Add color [+]
        bevel(px + 100 + currentColors().size() * 18 + 16, py + 50, 14, 14, () -> "+", () -> {
            List<Integer> colors = currentColors();
            if (colors.size() < 8) {
                colors.add(0xFF8844EE);
                editingColorIdx = colors.size() - 1;
                loadEditRGB();
                rebuildWidgets();
            }
        });

        // Hex EditBox
        hexBox = addRenderableWidget(new EditBox(font, px + 136, py + 68, 58, 12, Component.translatable("screen.customglint.glint_editor.hex_field")));
        hexBox.setMaxLength(6);
        hexBox.setValue(String.format("%06X", (editR << 16) | (editG << 8) | editB));
        hexBox.setResponder(this::onHexChanged);

        // R EditBox
        rBox = addRenderableWidget(new EditBox(font, px + 116, py + 84, 36, 12, Component.literal("R")));
        rBox.setMaxLength(3);
        rBox.setValue(String.valueOf(editR));
        rBox.setResponder(this::onRChanged);

        // G EditBox
        gBox = addRenderableWidget(new EditBox(font, px + 116, py + 100, 36, 12, Component.literal("G")));
        gBox.setMaxLength(3);
        gBox.setValue(String.valueOf(editG));
        gBox.setResponder(this::onGChanged);

        // B EditBox
        bBox = addRenderableWidget(new EditBox(font, px + 116, py + 116, 36, 12, Component.literal("B")));
        bBox.setMaxLength(3);
        bBox.setValue(String.valueOf(editB));
        bBox.setResponder(this::onBChanged);

        // A (opacity) EditBox
        aBox = addRenderableWidget(new EditBox(font, px + 116, py + 132, 36, 12, Component.literal("A")));
        aBox.setMaxLength(3);
        aBox.setValue(String.valueOf(editA));
        aBox.setResponder(this::onAChanged);

        // Speed [−] / [+], 0.10×..8.0× (0.10 steps below 1×, 0.5 above), matching the Glint Table.
        bevel(px + 148, py + 152, 14, 14, () -> "−", () -> {
            layerSpeeds.set(selectedLayer, stepDown(layerSpeeds.get(selectedLayer)));
            refreshPreview();
        });
        bevel(px + 196, py + 152, 14, 14, () -> "+", () -> {
            layerSpeeds.set(selectedLayer, stepUp(layerSpeeds.get(selectedLayer)));
            refreshPreview();
        });

        // Pattern Scale [−] / [+], same 0.10×..8.0× range as speed.
        bevel(px + 148, py + 168, 14, 14, () -> "−", () -> {
            layerScales.set(selectedLayer, stepDown(layerScales.get(selectedLayer)));
            refreshPreview();
        });
        bevel(px + 196, py + 168, 14, 14, () -> "+", () -> {
            layerScales.set(selectedLayer, stepUp(layerScales.get(selectedLayer)));
            refreshPreview();
        });

        // Smooth toggle
        bevel(px + 100, py + 186, 90, 14,
                () -> Component.translatable("screen.customglint.glint_editor.smooth",
                        Component.translatable(layerInterpolates.get(selectedLayer)
                                ? "screen.customglint.glint_editor.on" : "screen.customglint.glint_editor.off")).getString(),
                () -> layerInterpolates.get(selectedLayer) ? skin.costOk : skin.labelHdr,
                () -> { layerInterpolates.set(selectedLayer, !layerInterpolates.get(selectedLayer)); refreshPreview(); });

        // Simultaneous toggle
        bevel(px + 196, py + 186, 96, 14,
                () -> Component.translatable("screen.customglint.glint_editor.mode",
                        Component.translatable(layerSimultaneous.get(selectedLayer)
                                ? "screen.customglint.glint_editor.mode_simultaneous" : "screen.customglint.glint_editor.mode_cycle")).getString(),
                () -> { layerSimultaneous.set(selectedLayer, !layerSimultaneous.get(selectedLayer)); refreshPreview(); });

        // Scroll direction, cycles the 8 compass presets + Static. Rebuilds widgets so the static-offset
        // stepper appears/disappears with the mode.
        int sd = layerScrollDirs.get(selectedLayer);
        bevel(px + 100, py + 202, 90, 14,
                () -> Component.translatable("screen.customglint.glint_editor.scroll",
                        GlintTrimItem.scrollLabel(layerScrollDirs.get(selectedLayer))).getString(),
                () -> { layerScrollDirs.set(selectedLayer, (layerScrollDirs.get(selectedLayer) + 1) % 9); refreshPreview(); rebuildWidgets(); });

        // Static UV offset stepper, only shown (and only meaningful) when the layer is STATIC.
        if (sd == CustomGlint.SCROLL_STATIC) {
            bevel(px + 196, py + 202, 14, 14, () -> "−", () -> {
                layerScrollOffsets.set(selectedLayer, Math.max(0.0f, Math.round((layerScrollOffsets.get(selectedLayer) - 0.05f) * 20) / 20.0f));
                refreshPreview();
            });
            bevel(px + 244, py + 202, 14, 14, () -> "+", () -> {
                layerScrollOffsets.set(selectedLayer, Math.min(1.0f, Math.round((layerScrollOffsets.get(selectedLayer) + 0.05f) * 20) / 20.0f));
                refreshPreview();
            });
        }

        // Import glint from config
        bevel(px + 8, py + 100, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.import").getString(), () -> {
            showImportPicker = !showImportPicker;
            importScroll = 0;
            if (showImportPicker) {
                scanGlintConfigs();
                if (importSearchBox != null) importSearchBox.setFocused(true);
            }
        });

        // Change preview item
        bevel(px + 8, py + 116, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.change_item").getString(), () -> {
            if (allItems == null) allItems = BuiltInRegistries.ITEM.stream()
                    .filter(item -> { Identifier k = BuiltInRegistries.ITEM.getKey(item); return k == null || !k.getNamespace().equals("customglint"); })
                    .collect(Collectors.toList());
            filterItems(searchBox != null ? searchBox.getValue() : "");
            pickerScroll = 0;
            showPicker = true;
            searchBox.setFocused(true);
        });

        // Give new item with glint
        bevel(px + 8, py + 132, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.give_item").getString(), () -> {
            CustomGlint.Layer[] layers = buildLayers();
            int[] gc = glowOverrideColors.stream().mapToInt(Integer::intValue).toArray();
            String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(previewItem));
            ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, false, layers, itemId, glowEnabled, gc, trimName, trimNameColor));
        });

        // Give Glint Trim with current settings
        bevel(px + 8, py + 148, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.give_trim").getString(), () -> {
            CustomGlint.Layer[] layers = buildLayers();
            int[] gc = glowOverrideColors.stream().mapToInt(Integer::intValue).toArray();
            ClientPacketDistributor.sendToServer(new GiveGlintTrimPacket(layers, glowEnabled, gc, trimName, trimNameColor));
        });

        // Apply glint to item already in the other hand
        bevel(px + 8, py + 164, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.apply_hand").getString(), () -> {
            CustomGlint.Layer[] layers = buildLayers();
            int[] gc = glowOverrideColors.stream().mapToInt(Integer::intValue).toArray();
            ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, false, layers, "", glowEnabled, gc, trimName, trimNameColor));
        });

        // Remove glint from item in the other hand
        bevel(px + 8, py + 180, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.remove").getString(),
                () -> ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, true, new CustomGlint.Layer[0], "", false, new int[0])));

        // Glow ON/OFF toggle
        bevel(px + 8, py + 196, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.glow",
                        Component.translatable(glowEnabled ? "screen.customglint.glint_editor.on" : "screen.customglint.glint_editor.off")).getString(),
                () -> glowEnabled ? skin.costOk : skin.labelHdr,
                () -> { glowEnabled = !glowEnabled; refreshPreview(); });

        // Custom Name toggle button
        bevel(px + 8, py + 212, 80, 14,
                () -> Component.translatable("screen.customglint.glint_editor.name",
                        Component.translatable(trimName.isEmpty() ? "screen.customglint.glint_editor.off" : "screen.customglint.glint_editor.on")).getString(),
                () -> trimName.isEmpty() ? skin.labelHdr : skin.costOk, () -> {
            if (!trimName.isEmpty()) {
                trimName = "";
                if (trimNameBox != null) trimNameBox.setVisible(false);
                if (nameHexBox != null) nameHexBox.setVisible(false);
            } else {
                trimName = "Custom";
                if (trimNameBox != null) {
                    trimNameBox.setValue(trimName);
                    trimNameBox.setVisible(true);
                }
                if (nameHexBox != null) nameHexBox.setVisible(true);
            }
        });

        // Name text field (positioned below glow info, aligned with Smooth button area)
        trimNameBox = addRenderableWidget(new EditBox(font, px + 100, py + 254, 90, 12, Component.translatable("screen.customglint.glint_editor.trim_name")));
        trimNameBox.setMaxLength(32);
        trimNameBox.setResponder(s -> trimName = s);
        trimNameBox.setVisible(!trimName.isEmpty());

        // Name color hex box
        nameHexBox = addRenderableWidget(new EditBox(font, px + 193, py + 254, 50, 12, Component.translatable("screen.customglint.glint_editor.name_color")));
        nameHexBox.setMaxLength(6);
        nameHexBox.setValue(String.format("%06X", (trimNameColor >>> 8) & 0xFFFFFF));
        nameHexBox.setResponder(s -> {
            if (s.length() == 6) {
                try {
                    int rgb = Integer.parseInt(s, 16);
                    trimNameColor = (rgb << 8) | 0xFF;
                } catch (NumberFormatException ignored) {}
            }
        });
        nameHexBox.setVisible(!trimName.isEmpty());

        // ── Glow color section (right column, py+218, pushed below the Scroll row) ─────────────

        if (glowOverrideColors.isEmpty()) {
            // Auto mode, offer switch to custom
            bevel(px + 162, py + 218, 82, 14,
                    () -> Component.translatable("screen.customglint.glint_editor.auto_to_custom").getString(), () -> {
                List<Integer> l0 = layerColors.get(0);
                glowOverrideColors.add(l0.isEmpty() ? 0xFF8844EE : l0.get(0));
                editingGlowColor = true;
                editingGlowColorIdx = 0;
                loadEditRGB();
                syncChannelBoxes();
                syncHexFromRGB();
                rebuildWidgets();
            });
        } else {
            // Custom mode, offer switch back to auto
            bevel(px + 162, py + 218, 82, 14,
                    () -> Component.translatable("screen.customglint.glint_editor.custom_to_auto").getString(), () -> {
                glowOverrideColors.clear();
                editingGlowColor = false;
                rebuildWidgets();
            });

            // Glow color hex input, fixed to the right of the toggle button
            glowHexBox = addRenderableWidget(new EditBox(font, px + 248, py + 220, 46, 12, Component.translatable("screen.customglint.glint_editor.glow_hex")));
            glowHexBox.setMaxLength(6);
            syncGlowHexBox();

            // Remove last glow color [−]
            bevel(px + 100 + glowOverrideColors.size() * 18, py + 234, 14, 14, () -> "−", () -> {
                if (glowOverrideColors.size() > 1) {
                    glowOverrideColors.remove(glowOverrideColors.size() - 1);
                    if (editingGlowColorIdx >= glowOverrideColors.size()) {
                        editingGlowColorIdx = glowOverrideColors.size() - 1;
                    }
                    if (editingGlowColor) { loadEditRGB(); syncChannelBoxes(); syncHexFromRGB(); }
                    rebuildWidgets();
                }
            });

            // Add glow color [+]
            bevel(px + 100 + glowOverrideColors.size() * 18 + 16, py + 234, 14, 14, () -> "+", () -> {
                if (glowOverrideColors.size() < 8) {
                    glowOverrideColors.add(0xFFFFFFFF);
                    editingGlowColorIdx = glowOverrideColors.size() - 1;
                    editingGlowColor = true;
                    loadEditRGB();
                    syncChannelBoxes();
                    syncHexFromRGB();
                    rebuildWidgets();
                }
            });
        }

        // Item picker search box, managed manually
        searchBox = new EditBox(font, 0, 0, 180, 12, Component.translatable("screen.customglint.glint_editor.search_items"));
        searchBox.setMaxLength(40);
        searchBox.setResponder(s -> { pickerScroll = 0; filterItems(s); });


        refreshPreview();
    }

    // ── Item picker ───────────────────────────────────────────────────────────

    private void filterItems(String query) {
        if (allItems == null) return;
        String lq = query.toLowerCase();
        filteredItems = lq.isEmpty() ? new ArrayList<>(allItems) : allItems.stream().filter(item -> {
            Identifier rl = BuiltInRegistries.ITEM.getKey(item);
            return (rl != null && rl.toString().contains(lq))
                    || itemName(item).toLowerCase().contains(lq);
        }).collect(Collectors.toList());
        pickerScroll = Math.max(0, Math.min(pickerScroll, Math.max(0, filteredItems.size() - VISIBLE_ROWS)));
    }

    private void syncWandState() {
        CustomGlint.Layer[] layers = new CustomGlint.Layer[layerDesigns.size()];
        for (int i = 0; i < layers.length; i++) {
            int[] arr = layerColors.get(i).stream().mapToInt(Integer::intValue).toArray();
            layers[i] = new CustomGlint.Layer(designRL(layerDesigns.get(i)), arr,
                    layerSpeeds.get(i), layerInterpolates.get(i), layerScales.get(i), layerSimultaneous.get(i),
                    layerScrollDirs.get(i), layerScrollOffsets.get(i));
        }
        int[] gc = glowOverrideColors.stream().mapToInt(Integer::intValue).toArray();
        ClientPacketDistributor.sendToServer(new GlintApplyPacket(wandHand, false, layers, "", glowEnabled, gc, trimName, trimNameColor, true));
    }

    private void scanGlintConfigs() {
        allGlints.clear();
        try {
            Path configDir = Paths.get("config/customglint/trims").toAbsolutePath();
            if (Files.exists(configDir)) {
                // try-with-resources: Files.list holds an open directory handle that must be closed, else
                // each open of the Import picker leaks one OS file descriptor.
                try (var stream = Files.list(configDir)) {
                    stream.filter(p -> p.toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString().replace(".json", ""))
                        .sorted()
                        .forEach(allGlints::add);
                }
            }
        } catch (Exception e) {
            // Silently fail if config dir doesn't exist
        }
        // Apply the current search query (empty → all) to populate the rendered list.
        filterGlints(importSearchBox != null ? importSearchBox.getValue() : "");
    }

    private void loadGlintFromConfig(String name) {
        try {
            Path file = Paths.get("config/customglint/trims", name + ".json").toAbsolutePath();
            String json = new String(Files.readAllBytes(file));
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            // Parse the (untrusted) trim file into temp lists first, then commit to the live fields only after
            // a fully successful parse. A malformed file, bad hex in "colors", a missing "design"/"colors"
            // key, throws mid-loop; doing the work on temps means the catch below leaves the eight live
            // parallel lists untouched (still equal-length) instead of half-cleared, so the render path's
            // layerXxx.get(selectedLayer) can't index past a shortened list on the next frame.
            List<String>        tDesigns    = new ArrayList<>();
            List<List<Integer>> tColors     = new ArrayList<>();
            List<Float>         tSpeeds     = new ArrayList<>();
            List<Boolean>       tInterp     = new ArrayList<>();
            List<Float>         tScales     = new ArrayList<>();
            List<Boolean>       tSim        = new ArrayList<>();
            List<Integer>       tScrollDirs = new ArrayList<>();
            List<Float>         tScrollOffs = new ArrayList<>();

            if (obj.has("layers")) {
                JsonArray layers = obj.getAsJsonArray("layers");
                for (int i = 0; i < Math.min(layers.size(), 8); i++) {
                    JsonObject layer = layers.get(i).getAsJsonObject();
                    String design = layer.get("design").getAsString();
                    tDesigns.add(designShortName(Identifier.parse(design)));

                    List<Integer> colors = new ArrayList<>();
                    for (JsonElement e : layer.getAsJsonArray("colors")) {
                        String colorStr = e.getAsString();
                        colors.add((int) Long.parseLong(colorStr.replace("0x", ""), 16));
                    }
                    tColors.add(colors);
                    tSpeeds.add(layer.has("speed") ? layer.get("speed").getAsFloat() : 1.0f);
                    tInterp.add(layer.has("interpolate") ? layer.get("interpolate").getAsBoolean() : true);
                    tScales.add(layer.has("patternScale") ? layer.get("patternScale").getAsFloat() : 1.0f);
                    tSim.add(layer.has("simultaneous") ? layer.get("simultaneous").getAsBoolean() : true);
                    tScrollDirs.add(layer.has("scroll") ? layer.get("scroll").getAsInt() : CustomGlint.SCROLL_E);
                    tScrollOffs.add(layer.has("offset") ? layer.get("offset").getAsFloat() : 0.0f);
                }
            }

            if (tDesigns.isEmpty()) {
                tDesigns.add("sparkle");
                List<Integer> lc = new ArrayList<>();
                lc.add(0xFF8844EE);
                tColors.add(lc);
                tSpeeds.add(1.0f);
                tInterp.add(true);
                tScales.add(1.0f);
                tSim.add(true);
                tScrollDirs.add(CustomGlint.SCROLL_E);
                tScrollOffs.add(0.0f);
            }

            // Parse succeeded, swap the temp lists into the live fields in one pass (all stay equal-length).
            layerDesigns.clear();       layerDesigns.addAll(tDesigns);
            layerColors.clear();        layerColors.addAll(tColors);
            layerSpeeds.clear();        layerSpeeds.addAll(tSpeeds);
            layerInterpolates.clear();  layerInterpolates.addAll(tInterp);
            layerScales.clear();        layerScales.addAll(tScales);
            layerSimultaneous.clear();  layerSimultaneous.addAll(tSim);
            layerScrollDirs.clear();    layerScrollDirs.addAll(tScrollDirs);
            layerScrollOffsets.clear(); layerScrollOffsets.addAll(tScrollOffs);

            selectedLayer = 0;
            // The imported layer may carry fewer colors than the one we were editing; reset so the render
            // path's colors.get(editingColorIdx) can't index past the new (shorter) list.
            editingColorIdx = 0;

            if (obj.has("glowing")) glowEnabled = obj.get("glowing").getAsBoolean();

            if (obj.has("displayName")) {
                trimName = obj.get("displayName").getAsString();
            } else {
                trimName = "";
            }
            if (obj.has("nameColor")) {
                int rgb = (int) Long.parseLong(obj.get("nameColor").getAsString().replace("0x", ""), 16) & 0xFFFFFF;
                trimNameColor = (rgb << 8) | 0xFF;
            } else {
                trimNameColor = 0xFFFFFFFF;
            }

            if (trimNameBox != null) {
                trimNameBox.setValue(trimName);
                trimNameBox.setVisible(!trimName.isEmpty());
            }
            if (nameHexBox != null) {
                nameHexBox.setValue(String.format("%06X", (trimNameColor >>> 8) & 0xFFFFFF));
                nameHexBox.setVisible(!trimName.isEmpty());
            }
            loadEditRGB();
            syncChannelBoxes();
            syncHexFromRGB();
            refreshPreview();
            showImportPicker = false;
            syncWandState();
        } catch (Exception e) {
            // Silently fail
        }
    }

    // ── Text helpers (26.1 GuiGraphicsExtractor.text skips alpha==0 colors) ────

    // Flat text (no drop shadow), matching the Glint Table. A shadow muddies dark text on the light Default
    // skin; flat reads crisp on every theme.
    private void label(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
        g.text(this.font, c, x, y, 0xFF000000 | color, false);
    }

    private void label(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x, y, 0xFF000000 | color, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x - this.font.width(s) / 2, y, 0xFF000000 | color, false);
    }

    // ── Beveled buttons (skinned widgets) ──────────────────────────────────────

    /**
     * A skinned button backed by the widget system, mirroring the Glint Table's. The label, text colour and
     * base face are pulled live each frame from suppliers, so a toggle button shows changing state without
     * being recreated. Left-click only by default; the skin button opts into right-click.
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
            int face = !active ? skin.btnDisabled : (isHovered() ? skin.btnHover : faceColor.getAsInt());
            skin.raised(g, getX(), getY(), getWidth(), getHeight(), face);
            centered(g, label.get(), getX() + getWidth() / 2, getY() + textDy, active ? textColor.getAsInt() : skin.labelDim);
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

    /** A skinned button with the default header text colour. */
    private BevelButton bevel(int x, int y, int w, int h, Supplier<String> label, Runnable onPress) {
        return addRenderableWidget(new BevelButton(x, y, w, h, (h - 8) / 2, false,
                label, () -> skin.labelHdr, () -> skin.guiFace, b -> onPress.run()));
    }

    /** A skinned button with a live text colour (so toggles can show on/off state). */
    private BevelButton bevel(int x, int y, int w, int h, Supplier<String> label, IntSupplier textColor, Runnable onPress) {
        return addRenderableWidget(new BevelButton(x, y, w, h, (h - 8) / 2, false,
                label, textColor, () -> skin.guiFace, b -> onPress.run()));
    }

    // ── Speed / scale stepping (0.10×..8.0×, matching the Glint Table) ──────────

    /** Step a speed/scale value up: 0.10 increments below 1×, 0.5 above; capped at 8. */
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

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        // Skinned panel background, frame, divider and preview recess are baked into the skin PNG.
        skin.windowPanel(g, px, py, PANEL_W, PANEL_H);

        // Left labels
        label(g, Component.translatable("screen.customglint.glint_editor.preview"), px + 8, py + 8, skin.accent);

        // Preview box interior (the recess outline is part of the PNG; the item draws over it below).
        int bx = px + 8, by = py + 18;

        // Layer tabs
        int tabRowY = py + 6;
        for (int i = 0; i < layerDesigns.size(); i++) {
            int tx = px + 100 + i * 22;
            boolean sel = (i == selectedLayer);
            skin.raised(g, tx - 1, tabRowY - 1, 22, 16, sel ? skin.tabActive : skin.tabIdle);
            centered(g, String.valueOf(i + 1), tx + 10, tabRowY + 3, skin.labelHdr);
            if (sel && layerDesigns.size() > 1) {
                g.fill(tx + 13, tabRowY, tx + 20, tabRowY + 8, skin.costBad);
                label(g, "x", tx + 14, tabRowY + 1, 0xFFFFFF);
            }
        }
        if (layerDesigns.size() < 8) {
            int plusX = px + 100 + layerDesigns.size() * 22;
            skin.raised(g, plusX - 1, tabRowY - 1, 22, 16, skin.guiFace);
            centered(g, "+", plusX + 10, tabRowY + 3, skin.costOk);
        }

        label(g, Component.translatable("screen.customglint.glint_editor.colors"), px + 100, py + 40, skin.accent);
        label(g, Component.translatable("screen.customglint.glint_editor.hex"), px + 100, py + 70, skin.labelDim);
        label(g, "R:", px + 100, py + 86, skin.chR);
        label(g, "G:", px + 100, py + 102, skin.chG);
        label(g, "B:", px + 100, py + 118, skin.chB);
        label(g, "A:", px + 100, py + 134, skin.labelDim);
        label(g, Component.translatable("screen.customglint.glint_editor.speed"), px + 100, py + 154, skin.labelDim);
        label(g, Component.translatable("screen.customglint.glint_editor.scale"), px + 100, py + 170, skin.labelDim);
        label(g, Component.translatable("screen.customglint.glint_editor.glow_color"), px + 100, py + 220, glowEnabled ? skin.accent : skin.labelDim);
        if (glowOverrideColors.isEmpty()) {
            label(g, Component.translatable("screen.customglint.glint_editor.glow_auto"), px + 100, py + 238, glowEnabled ? skin.labelDim : skin.btnDisabled);
        }

        // Widgets (buttons + added edit boxes). Background is drawn by the framework
        // before extractRenderState, so no renderBackground call here.
        super.extractRenderState(g, mx, my, dt);

        // Item preview
        if (!previewStack.isEmpty()) {
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(bx + PREVIEW_SZ / 2f, by + PREVIEW_SZ / 2f);
            pose.scale(5.0f, 5.0f);
            g.item(previewStack, -8, -8);
            pose.popMatrix();
        }

        // Color swatches
        List<Integer> colors = currentColors();
        for (int i = 0; i < colors.size(); i++) {
            int sx = px + 100 + i * 18;
            int sy = py + 50;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17,
                   i == editingColorIdx ? skin.ring : skin.guiShadow);
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF000000 | (colors.get(i) & 0xFFFFFF));
        }

        int previewColor = (editingGlowColor && editingGlowColorIdx < glowOverrideColors.size())
                ? glowOverrideColors.get(editingGlowColorIdx)
                : (colors.isEmpty() ? 0 : colors.get(Math.min(editingColorIdx, colors.size() - 1)));
        g.fill(px + 120, py + 68, px + 132, py + 80, 0xFF000000 | (previewColor & 0xFFFFFF));

        // Glow override swatches
        if (!glowOverrideColors.isEmpty()) {
            for (int i = 0; i < glowOverrideColors.size(); i++) {
                int sx = px + 100 + i * 18;
                int sy = py + 234;
                g.fill(sx - 1, sy - 1, sx + 17, sy + 17,
                        (editingGlowColor && i == editingGlowColorIdx) ? skin.ring : skin.guiShadow);
                g.fill(sx, sy, sx + 16, sy + 16, 0xFF000000 | (glowOverrideColors.get(i) & 0xFFFFFF));
            }
        }

        centered(g, fmtVal(layerSpeeds.get(selectedLayer)) + "×", px + 175, py + 154, skin.labelHdr);
        centered(g, fmtVal(layerScales.get(selectedLayer)) + "×", px + 175, py + 170, skin.labelHdr);
        if (layerScrollDirs.get(selectedLayer) == CustomGlint.SCROLL_STATIC) {
            centered(g, String.format("%.2f", layerScrollOffsets.get(selectedLayer)), px + 227, py + 204, skin.labelHdr);
        }

        // Design picker overlay
        if (showDesignPicker) {
            g.nextStratum();
            renderDesignPicker(g, mx, my, dt);
        }

        // Import picker overlay
        if (showImportPicker) {
            g.nextStratum();
            renderImportPicker(g, mx, my, dt);
        }

        // Item picker overlay
        if (showPicker) {
            g.nextStratum();
            renderPicker(g, mx, my, dt);
        }
    }

    // ── Design picker rendering ───────────────────────────────────────────────

    private void renderDesignPicker(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int ox = dpX(), oy = dpY();

        g.fill(ox - 1, oy - 1, ox + DPW + 1, oy + DPH + 1, 0xFF666666);
        g.fill(ox, oy, ox + DPW, oy + DPH, 0xEE111111);

        designSearchBox.setX(ox + 2);
        designSearchBox.setY(oy + 3);
        designSearchBox.setWidth(DPW - 4);
        designSearchBox.extractRenderState(g, mx, my, dt);

        int listY = oy + 20;
        int sbX   = ox + DPW - 5;
        String active = layerDesigns.get(selectedLayer);

        for (int i = 0; i < DESIGN_ROWS && designScroll + i < filteredDesigns.size(); i++) {
            String d = filteredDesigns.get(designScroll + i);
            int ry = listY + i * DESIGN_ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + DESIGN_ROW_H;
            if (d.equals(active)) g.fill(ox, ry, sbX, ry + DESIGN_ROW_H, 0x6044AA44);
            if (hovered)          g.fill(ox, ry, sbX, ry + DESIGN_ROW_H, 0x40FFFFFF);
            label(g, d, ox + 4, ry + 3, 0xDDDDDD);
        }

        if (filteredDesigns.size() > DESIGN_ROWS) {
            int trackH = DESIGN_ROWS * DESIGN_ROW_H;
            g.fill(sbX, listY, sbX + 4, listY + trackH, 0xFF2A2A2A);
            int thumbH = Math.max(8, trackH * DESIGN_ROWS / filteredDesigns.size());
            int thumbY = listY + (int)((trackH - thumbH) * (float) designScroll
                    / (filteredDesigns.size() - DESIGN_ROWS));
            g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFF888888);
        }
    }

    private void renderImportPicker(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int ox = ipX(), oy = ipY();

        g.fill(ox - 1, oy - 1, ox + IPW + 1, oy + IPH + 1, 0xFF666666);
        g.fill(ox, oy, ox + IPW, oy + IPH, 0xEE111111);

        if (importSearchBox == null) {
            importSearchBox = new EditBox(font, ox + 2, oy + 3, IPW - 4, 12, Component.translatable("screen.customglint.glint_editor.search_glints"));
            importSearchBox.setMaxLength(40);
            importSearchBox.setResponder(s -> filterGlints(s));
        }
        importSearchBox.setX(ox + 2);
        importSearchBox.setY(oy + 3);
        importSearchBox.setWidth(IPW - 4);
        importSearchBox.extractRenderState(g, mx, my, dt);

        int listY = oy + 20;
        int sbX   = ox + IPW - 5;

        for (int i = 0; i < IMPORT_ROWS && importScroll + i < availableGlints.size(); i++) {
            String glint = availableGlints.get(importScroll + i);
            int ry = listY + i * IMPORT_ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + IMPORT_ROW_H;
            if (hovered) g.fill(ox, ry, sbX, ry + IMPORT_ROW_H, 0x40FFFFFF);
            label(g, glint, ox + 4, ry + 2, 0xDDDDDD);
        }

        if (availableGlints.size() > IMPORT_ROWS) {
            int trackH = IMPORT_ROWS * IMPORT_ROW_H;
            g.fill(sbX, listY, sbX + 4, listY + trackH, 0xFF2A2A2A);
            int thumbH = Math.max(8, trackH * IMPORT_ROWS / availableGlints.size());
            int thumbY = listY + (int)((trackH - thumbH) * (float) importScroll
                    / (availableGlints.size() - IMPORT_ROWS));
            g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFF888888);
        }
    }

    private void filterGlints(String query) {
        String lq = query == null ? "" : query.toLowerCase();
        availableGlints = lq.isEmpty() ? new ArrayList<>(allGlints) : allGlints.stream()
                .filter(d -> d.toLowerCase().contains(lq))
                .collect(Collectors.toList());
        importScroll = Math.max(0, Math.min(importScroll, Math.max(0, availableGlints.size() - IMPORT_ROWS)));
    }

    // ── Item picker rendering ─────────────────────────────────────────────────

    private static final int OW = 200, OH = VISIBLE_ROWS * ROW_H + 20;

    private int pickerOX() { return Math.max(2, Math.min(width - OW - 2, px + 8)); }
    private int pickerOY() { return Math.max(2, Math.min(height - OH - 2, py + 159)); }

    private void renderPicker(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int ox = pickerOX(), oy = pickerOY();

        g.fill(ox - 1, oy - 1, ox + OW + 1, oy + OH + 1, 0xFF666666);
        g.fill(ox, oy, ox + OW, oy + OH, 0xEE111111);

        searchBox.setX(ox + 2);
        searchBox.setY(oy + 3);
        searchBox.setWidth(OW - 4);
        searchBox.extractRenderState(g, mx, my, dt);

        int listY = oy + 20;
        int sbX   = ox + OW - 6;

        for (int i = 0; i < VISIBLE_ROWS && pickerScroll + i < filteredItems.size(); i++) {
            Item item = filteredItems.get(pickerScroll + i);
            int ry = listY + i * ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + ROW_H;
            if (hovered) g.fill(ox, ry, sbX, ry + ROW_H, 0x40FFFFFF);
            g.item(itemIcon(item), ox + 2, ry + 1);
            label(g, font.plainSubstrByWidth(itemName(item), OW - 30), ox + 20, ry + 5, 0xDDDDDD);
        }

        if (filteredItems.size() > VISIBLE_ROWS) {
            int trackH = VISIBLE_ROWS * ROW_H;
            g.fill(sbX, listY, sbX + 4, listY + trackH, 0xFF2A2A2A);
            int thumbH = Math.max(10, trackH * VISIBLE_ROWS / filteredItems.size());
            int thumbY = listY + (int)((trackH - thumbH) * (float) pickerScroll
                    / (filteredItems.size() - VISIBLE_ROWS));
            g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFF888888);
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();

        // Design picker
        if (showDesignPicker) {
            int ox = dpX(), oy = dpY();

            if (mx >= ox + 2 && mx < ox + DPW - 2 && my >= oy + 3 && my < oy + 17)
                designSearchBox.mouseClicked(event, doubleClick);

            if (mx < ox || mx >= ox + DPW || my < oy || my >= oy + DPH) {
                showDesignPicker = false;
                return true;
            }

            int listY = oy + 20;
            if (my >= listY && mx < ox + DPW - 5) {
                int row = (int)(my - listY) / DESIGN_ROW_H;
                int idx = designScroll + row;
                if (row < DESIGN_ROWS && idx < filteredDesigns.size()) {
                    layerDesigns.set(selectedLayer, filteredDesigns.get(idx));
                    showDesignPicker = false;
                    refreshPreview();
                    Minecraft.getInstance().schedule(this::rebuildWidgets);
                }
            }
            return true;
        }

        if (showImportPicker) {
            int ox = ipX(), oy = ipY();

            if (mx >= ox + 2 && mx < ox + IPW - 2 && my >= oy + 3 && my < oy + 17) {
                if (importSearchBox != null) importSearchBox.mouseClicked(event, doubleClick);
            }

            if (mx < ox || mx >= ox + IPW || my < oy || my >= oy + IPH) {
                showImportPicker = false;
                return true;
            }

            int listY = oy + 20;
            if (my >= listY && mx < ox + IPW - 5) {
                int row = (int)(my - listY) / IMPORT_ROW_H;
                int idx = importScroll + row;
                if (row < IMPORT_ROWS && idx < availableGlints.size()) {
                    loadGlintFromConfig(availableGlints.get(idx));
                    rebuildWidgets();
                }
            }
            return true;
        }

        // Item picker
        if (showPicker) {
            int ox = pickerOX(), oy = pickerOY();

            if (mx >= ox + 2 && mx < ox + OW - 2 && my >= oy + 3 && my < oy + 17)
                searchBox.mouseClicked(event, doubleClick);

            if (mx < ox || mx >= ox + OW || my < oy || my >= oy + OH) {
                showPicker = false;
                return true;
            }

            int listY = oy + 20;
            if (my >= listY && mx < ox + OW - 6) {
                int row = (int)(my - listY) / ROW_H;
                int idx = pickerScroll + row;
                if (row < VISIBLE_ROWS && idx < filteredItems.size()) {
                    previewItem = filteredItems.get(idx);
                    showPicker = false;
                    refreshPreview();
                    syncWandState();
                }
            }
            return true;
        }

        // Layer tab clicks
        int tabRowY = py + 6;
        if (my >= tabRowY && my < tabRowY + 14) {
            for (int i = 0; i < layerDesigns.size(); i++) {
                int tx = px + 100 + i * 22;
                if (mx >= tx && mx < tx + 20) {
                    if (i == selectedLayer && layerDesigns.size() > 1 && mx >= tx + 13) {
                        layerDesigns.remove(i);
                        layerColors.remove(i);
                        layerSpeeds.remove(i);
                        layerInterpolates.remove(i);
                        layerScales.remove(i);
                        layerSimultaneous.remove(i);
                        layerScrollDirs.remove(i);
                        layerScrollOffsets.remove(i);
                        if (selectedLayer >= layerDesigns.size()) selectedLayer = layerDesigns.size() - 1;
                        editingColorIdx = 0;
                        loadEditRGB();
                        Minecraft.getInstance().schedule(this::rebuildWidgets);
                    } else if (i != selectedLayer) {
                        selectedLayer = i;
                        editingColorIdx = 0;
                        loadEditRGB();
                        Minecraft.getInstance().schedule(this::rebuildWidgets);
                    }
                    return true;
                }
            }
            if (layerDesigns.size() < 8) {
                int plusX = px + 100 + layerDesigns.size() * 22;
                if (mx >= plusX && mx < plusX + 20) {
                    layerDesigns.add(layerDesigns.get(selectedLayer));
                    List<Integer> lc = new ArrayList<>();
                    lc.add(0xFF8844EE);
                    layerColors.add(lc);
                    layerSpeeds.add(layerSpeeds.get(selectedLayer));
                    layerInterpolates.add(layerInterpolates.get(selectedLayer));
                    layerScales.add(layerScales.get(selectedLayer));
                    layerSimultaneous.add(layerSimultaneous.get(selectedLayer));
                    layerScrollDirs.add(layerScrollDirs.get(selectedLayer));
                    layerScrollOffsets.add(layerScrollOffsets.get(selectedLayer));
                    selectedLayer = layerDesigns.size() - 1;
                    editingColorIdx = 0;
                    loadEditRGB();
                    Minecraft.getInstance().schedule(this::rebuildWidgets);
                    return true;
                }
            }
        }

        // Layer swatch clicks
        if (my >= py + 50 && my < py + 66) {
            List<Integer> colors = currentColors();
            for (int i = 0; i < colors.size(); i++) {
                int sx = px + 100 + i * 18;
                if (mx >= sx && mx < sx + 16) {
                    editingGlowColor = false;
                    editingColorIdx = i;
                    loadEditRGB();
                    syncChannelBoxes();
                    syncHexFromRGB();
                    return true;
                }
            }
        }

        // Glow override swatch clicks
        if (!glowOverrideColors.isEmpty() && my >= py + 218 && my < py + 234) {
            for (int i = 0; i < glowOverrideColors.size(); i++) {
                int sx = px + 100 + i * 18;
                if (mx >= sx && mx < sx + 16) {
                    editingGlowColor = true;
                    editingGlowColorIdx = i;
                    loadEditRGB();
                    syncChannelBoxes();
                    syncHexFromRGB();
                    syncGlowHexBox();
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (showDesignPicker) {
            if (designSearchBox.keyPressed(event)) return true;
            if (event.key() == 256) { showDesignPicker = false; return true; }
            return true;
        }
        if (showPicker) {
            if (searchBox.keyPressed(event)) return true;
            if (event.key() == 256) { showPicker = false; return true; }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (showDesignPicker) return designSearchBox.charTyped(event);
        if (showPicker) return searchBox.charTyped(event);
        return super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double delta) {
        if (showDesignPicker) {
            int ox = dpX(), oy = dpY();
            if (mx >= ox && mx < ox + DPW && my >= oy && my < oy + DPH) {
                int maxScroll = Math.max(0, filteredDesigns.size() - DESIGN_ROWS);
                designScroll = Math.max(0, Math.min(maxScroll, designScroll - (int) Math.signum(delta)));
                return true;
            }
        }
        if (showImportPicker) {
            int ox = ipX(), oy = ipY();
            if (mx >= ox && mx < ox + IPW && my >= oy && my < oy + IPH) {
                int maxScroll = Math.max(0, availableGlints.size() - IMPORT_ROWS);
                importScroll = Math.max(0, Math.min(maxScroll, importScroll - (int) Math.signum(delta)));
                return true;
            }
        }
        if (showPicker) {
            int ox = pickerOX(), oy = pickerOY();
            if (mx >= ox && mx < ox + OW && my >= oy && my < oy + OH) {
                int maxScroll = Math.max(0, filteredItems.size() - VISIBLE_ROWS);
                pickerScroll = Math.max(0, Math.min(maxScroll, pickerScroll - (int) Math.signum(delta)));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, scrollX, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
