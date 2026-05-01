package net.tunamods.customglint.module.gui;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.network.GlintApplyPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class GlintEditorScreen extends Screen {

    // ── Layout constants ────────────────────────────────────────────────────
    private static final int PANEL_W    = 300;
    private static final int PANEL_H    = 308;
    private static final int PREVIEW_SZ = 80;

    private static final String[] DESIGNS = {
        "checker",  "crosshatch", "diamonds", "dots",
        "fire",     "grid",       "hexagon",  "pulse",
        "ripple",   "scales",     "sparkle",  "stars",
        "stripes",  "swirl",      "wave",     "zigzag",
        "crystal",  "ember",      "vein",     "vanilla",
        "solid",    "skulls"
    };

    private static ResourceLocation designRL(String name) {
        if ("vanilla".equals(name)) return CustomGlint.VANILLA;
        return new ResourceLocation("customglint", "textures/glint/" + name + ".png");
    }

    private static String designShortName(ResourceLocation rl) {
        if (rl.equals(CustomGlint.VANILLA)) return "vanilla";
        String p = rl.getPath();
        int s = p.lastIndexOf('/') + 1;
        int e = p.endsWith(".png") ? p.length() - 4 : p.length();
        return p.substring(s, e);
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
    private int selectedLayer = 0;

    private int editingColorIdx = 0;

    private int editR = 0x88, editG = 0x44, editB = 0xEE, editA = 0xFF;

    private Item      previewItem  = Items.NETHERITE_SWORD;
    private ItemStack previewStack = ItemStack.EMPTY;

    // ── Item-picker overlay ─────────────────────────────────────────────────
    private boolean    showPicker    = false;
    private List<Item> allItems      = null;
    private List<Item> filteredItems = new ArrayList<>();
    private int        pickerScroll  = 0;
    private static final int VISIBLE_ROWS = 8, ROW_H = 18;

    // ── Widget refs ─────────────────────────────────────────────────────────
    private EditBox        hexBox, rBox, gBox, bBox, aBox;
    private EditBox        searchBox;
    private final Button[] designBtns = new Button[DESIGNS.length];

    private int px, py;

    // ── Construction ────────────────────────────────────────────────────────

    public GlintEditorScreen(InteractionHand hand) {
        super(Component.literal("Glint Editor"));
        this.wandHand = hand;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            CustomGlint.Data d = CustomGlint.read(mc.player.getItemInHand(hand));
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
                }
            }
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
        }
        loadEditRGB();
    }

    // ── Color helpers ────────────────────────────────────────────────────────

    private List<Integer> currentColors() {
        return layerColors.get(selectedLayer);
    }

    private void loadEditRGB() {
        List<Integer> colors = currentColors();
        int c = editingColorIdx < colors.size() ? colors.get(editingColorIdx) : 0xFF8844EE;
        editA = (c >> 24) & 0xFF;
        editR = (c >> 16) & 0xFF;
        editG = (c >>  8) & 0xFF;
        editB =  c        & 0xFF;
    }

    private void saveEditRGB() {
        List<Integer> colors = currentColors();
        if (editingColorIdx < colors.size())
            colors.set(editingColorIdx, (editA << 24) | (editR << 16) | (editG << 8) | editB);
    }

    private void syncHexFromRGB() {
        if (hexBox == null) return;
        hexBox.setResponder(null);
        hexBox.setValue(String.format("%06X", (editR << 16) | (editG << 8) | editB));
        hexBox.setResponder(this::onHexChanged);
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

    private void refreshPreview() {
        previewStack = new ItemStack(previewItem);
        CustomGlint.Layer[] layers = new CustomGlint.Layer[layerDesigns.size()];
        for (int i = 0; i < layers.length; i++) {
            int[] arr = layerColors.get(i).stream().mapToInt(Integer::intValue).toArray();
            layers[i] = new CustomGlint.Layer(designRL(layerDesigns.get(i)), arr,
                    layerSpeeds.get(i), layerInterpolates.get(i), layerScales.get(i), layerSimultaneous.get(i));
        }
        CustomGlint.write(previewStack, layers);
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        px = (width  - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;

        // Layer navigation: ◄ ► + − buttons in the header row
        addRenderableWidget(Button.builder(Component.literal("◄"), b -> {
            if (selectedLayer > 0) {
                selectedLayer--;
                editingColorIdx = 0;
                loadEditRGB();
                rebuildWidgets();
            }
        }).bounds(px + 152, py + 6, 14, 14).build());

        addRenderableWidget(Button.builder(Component.literal("►"), b -> {
            if (selectedLayer < layerDesigns.size() - 1) {
                selectedLayer++;
                editingColorIdx = 0;
                loadEditRGB();
                rebuildWidgets();
            }
        }).bounds(px + 168, py + 6, 14, 14).build());

        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            layerDesigns.add(layerDesigns.get(selectedLayer));
            List<Integer> lc = new ArrayList<>();
            lc.add(0xFF8844EE);
            layerColors.add(lc);
            layerSpeeds.add(layerSpeeds.get(selectedLayer));
            layerInterpolates.add(layerInterpolates.get(selectedLayer));
            layerScales.add(layerScales.get(selectedLayer));
            layerSimultaneous.add(layerSimultaneous.get(selectedLayer));
            selectedLayer = layerDesigns.size() - 1;
            editingColorIdx = 0;
            loadEditRGB();
            rebuildWidgets();
        }).bounds(px + 186, py + 6, 14, 14).build());

        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            if (layerDesigns.size() > 1) {
                layerDesigns.remove(selectedLayer);
                layerColors.remove(selectedLayer);
                layerSpeeds.remove(selectedLayer);
                layerInterpolates.remove(selectedLayer);
                layerScales.remove(selectedLayer);
                layerSimultaneous.remove(selectedLayer);
                if (selectedLayer >= layerDesigns.size()) selectedLayer = layerDesigns.size() - 1;
                editingColorIdx = 0;
                loadEditRGB();
                rebuildWidgets();
            }
        }).bounds(px + 202, py + 6, 14, 14).build());

        // Design buttons — 4 columns, as many rows as needed
        for (int i = 0; i < DESIGNS.length; i++) {
            final String d = DESIGNS[i];
            int col = i % 4, row = i / 4;
            designBtns[i] = addRenderableWidget(
                Button.builder(Component.literal(d), b -> {
                    layerDesigns.set(selectedLayer, d);
                    refreshPreview();
                }).bounds(px + 100 + col * 48, py + 22 + row * 16, 46, 14).build()
            );
        }

        // Remove last color [−]
        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            List<Integer> colors = currentColors();
            if (colors.size() > 1) {
                colors.remove(colors.size() - 1);
                if (editingColorIdx >= colors.size()) editingColorIdx = colors.size() - 1;
                loadEditRGB();
                rebuildWidgets();
            }
        }).bounds(px + 100 + currentColors().size() * 18, py + 150, 14, 14).build());

        // Add color [+]
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            List<Integer> colors = currentColors();
            if (colors.size() < 8) {
                colors.add(0xFF8844EE);
                editingColorIdx = colors.size() - 1;
                loadEditRGB();
                rebuildWidgets();
            }
        }).bounds(px + 100 + currentColors().size() * 18 + 16, py + 150, 14, 14).build());

        // Hex EditBox
        hexBox = addRenderableWidget(new EditBox(font, px + 136, py + 168, 58, 12, Component.literal("Hex")));
        hexBox.setMaxLength(6);
        hexBox.setValue(String.format("%06X", (editR << 16) | (editG << 8) | editB));
        hexBox.setResponder(this::onHexChanged);

        // R EditBox
        rBox = addRenderableWidget(new EditBox(font, px + 116, py + 184, 36, 12, Component.literal("R")));
        rBox.setMaxLength(3);
        rBox.setValue(String.valueOf(editR));
        rBox.setResponder(this::onRChanged);

        // G EditBox
        gBox = addRenderableWidget(new EditBox(font, px + 116, py + 200, 36, 12, Component.literal("G")));
        gBox.setMaxLength(3);
        gBox.setValue(String.valueOf(editG));
        gBox.setResponder(this::onGChanged);

        // B EditBox
        bBox = addRenderableWidget(new EditBox(font, px + 116, py + 216, 36, 12, Component.literal("B")));
        bBox.setMaxLength(3);
        bBox.setValue(String.valueOf(editB));
        bBox.setResponder(this::onBChanged);

        // A (opacity) EditBox
        aBox = addRenderableWidget(new EditBox(font, px + 116, py + 232, 36, 12, Component.literal("A")));
        aBox.setMaxLength(3);
        aBox.setValue(String.valueOf(editA));
        aBox.setResponder(this::onAChanged);

        // Speed [−]
        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            layerSpeeds.set(selectedLayer, Math.max(0.25f, Math.round((layerSpeeds.get(selectedLayer) - 0.25f) * 4) / 4.0f));
            refreshPreview();
        }).bounds(px + 148, py + 252, 14, 14).build());

        // Speed [+]
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            layerSpeeds.set(selectedLayer, Math.min(8.0f, Math.round((layerSpeeds.get(selectedLayer) + 0.25f) * 4) / 4.0f));
            refreshPreview();
        }).bounds(px + 196, py + 252, 14, 14).build());

        // Pattern Scale [−]
        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            layerScales.set(selectedLayer, Math.max(0.25f, Math.round((layerScales.get(selectedLayer) - 0.25f) * 4) / 4.0f));
            refreshPreview();
        }).bounds(px + 148, py + 268, 14, 14).build());

        // Pattern Scale [+]
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            layerScales.set(selectedLayer, Math.min(4.0f, Math.round((layerScales.get(selectedLayer) + 0.25f) * 4) / 4.0f));
            refreshPreview();
        }).bounds(px + 196, py + 268, 14, 14).build());

        // Smooth toggle
        addRenderableWidget(Button.builder(
                Component.literal("Smooth: " + (layerInterpolates.get(selectedLayer) ? "ON" : "OFF")), b -> {
            boolean interp = !layerInterpolates.get(selectedLayer);
            layerInterpolates.set(selectedLayer, interp);
            b.setMessage(Component.literal("Smooth: " + (interp ? "ON" : "OFF")));
            refreshPreview();
        }).bounds(px + 100, py + 286, 90, 14).build());

        // Simultaneous toggle
        addRenderableWidget(Button.builder(
                Component.literal(layerSimultaneous.get(selectedLayer) ? "Mode: Simultaneous" : "Mode: Cycle"), b -> {
            boolean sim = !layerSimultaneous.get(selectedLayer);
            layerSimultaneous.set(selectedLayer, sim);
            b.setMessage(Component.literal(sim ? "Mode: Simultaneous" : "Mode: Cycle"));
            refreshPreview();
        }).bounds(px + 196, py + 286, 96, 14).build());

        // Change preview item
        addRenderableWidget(Button.builder(Component.literal("Change Item ▼"), b -> {
            if (allItems == null) allItems = ForgeRegistries.ITEMS.getValues().stream()
                    .filter(item -> { ResourceLocation k = ForgeRegistries.ITEMS.getKey(item); return k == null || !k.getNamespace().equals("customglint"); })
                    .collect(Collectors.toList());
            filterItems(searchBox != null ? searchBox.getValue() : "");
            pickerScroll = 0;
            showPicker = true;
            searchBox.setFocused(true);
        }).bounds(px + 8, py + 112, 80, 14).build());

        // Give new item with glint
        addRenderableWidget(Button.builder(Component.literal("Get Item"), b -> {
            CustomGlint.Layer[] layers = new CustomGlint.Layer[layerDesigns.size()];
            for (int i = 0; i < layers.length; i++) {
                int[] arr = layerColors.get(i).stream().mapToInt(Integer::intValue).toArray();
                layers[i] = new CustomGlint.Layer(designRL(layerDesigns.get(i)), arr,
                        layerSpeeds.get(i), layerInterpolates.get(i), layerScales.get(i), layerSimultaneous.get(i));
            }
            String itemId = String.valueOf(ForgeRegistries.ITEMS.getKey(previewItem));
            ModNetworking.CHANNEL.sendToServer(new GlintApplyPacket(wandHand, false, layers, itemId));
        }).bounds(px + 8, py + 128, 80, 14).build());

        // Apply glint to item already in the other hand
        addRenderableWidget(Button.builder(Component.literal("Apply to Hand"), b -> {
            CustomGlint.Layer[] layers = new CustomGlint.Layer[layerDesigns.size()];
            for (int i = 0; i < layers.length; i++) {
                int[] arr = layerColors.get(i).stream().mapToInt(Integer::intValue).toArray();
                layers[i] = new CustomGlint.Layer(designRL(layerDesigns.get(i)), arr,
                        layerSpeeds.get(i), layerInterpolates.get(i), layerScales.get(i), layerSimultaneous.get(i));
            }
            ModNetworking.CHANNEL.sendToServer(new GlintApplyPacket(wandHand, false, layers, ""));
        }).bounds(px + 8, py + 144, 80, 14).build());

        // Remove glint from item in the other hand
        addRenderableWidget(Button.builder(Component.literal("Remove Glint"), b -> {
            ModNetworking.CHANNEL.sendToServer(new GlintApplyPacket(wandHand, true, new CustomGlint.Layer[0], ""));
        }).bounds(px + 8, py + 160, 80, 14).build());

        // Item picker search box — managed manually
        searchBox = new EditBox(font, 0, 0, 180, 12, Component.literal("Search items..."));
        searchBox.setMaxLength(40);
        searchBox.setResponder(s -> { pickerScroll = 0; filterItems(s); });

        refreshPreview();
    }

    // ── Item picker ───────────────────────────────────────────────────────────

    private void filterItems(String query) {
        if (allItems == null) return;
        String lq = query.toLowerCase();
        filteredItems = lq.isEmpty() ? new ArrayList<>(allItems) : allItems.stream().filter(item -> {
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(item);
            return (rl != null && rl.toString().contains(lq))
                    || item.getDescription().getString().toLowerCase().contains(lq);
        }).collect(Collectors.toList());
        pickerScroll = Math.max(0, Math.min(pickerScroll, Math.max(0, filteredItems.size() - VISIBLE_ROWS)));
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        renderBackground(g);

        // Panel background
        g.fill(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, 0xFF555555);
        g.fill(px, py, px + PANEL_W, py + PANEL_H, 0xEE1A1A1A);

        // Column divider
        g.fill(px + 97, py + 4, px + 98, py + PANEL_H - 4, 0xFF333333);

        // Left labels
        g.drawString(font, "Preview", px + 8, py + 8, 0xFFFFAA);

        // Preview box
        int bx = px + 8, by = py + 18;
        g.fill(bx - 1, by - 1, bx + PREVIEW_SZ + 1, by + PREVIEW_SZ + 1, 0xFF444444);
        g.fill(bx, by, bx + PREVIEW_SZ, by + PREVIEW_SZ, 0xFF222222);

        g.drawString(font, "Item:", px + 8, py + 102, 0xAAAAAA);

        // Right column header: current layer indicator + nav buttons
        g.drawString(font, "Layer " + (selectedLayer + 1) + "/" + layerDesigns.size(), px + 100, py + 8, 0xFFFFAA);

        g.drawString(font, "Colors:", px + 100, py + 140, 0xFFFFAA);
        g.drawString(font, "Hex:", px + 100, py + 170, 0xAAAAAA);
        g.drawString(font, "R:", px + 100, py + 186, 0xFF6666);
        g.drawString(font, "G:", px + 100, py + 202, 0x66FF66);
        g.drawString(font, "B:", px + 100, py + 218, 0x6666FF);
        g.drawString(font, "A:", px + 100, py + 234, 0xAAAAAA);
        g.drawString(font, "Speed:",  px + 100, py + 254, 0xAAAAAA);
        g.drawString(font, "Scale:",  px + 100, py + 270, 0xAAAAAA);

        // Design selection highlight (behind buttons)
        for (int i = 0; i < DESIGNS.length; i++) {
            if (DESIGNS[i].equals(layerDesigns.get(selectedLayer)) && designBtns[i] != null) {
                Button b = designBtns[i];
                g.fill(b.getX() - 1, b.getY() - 1,
                       b.getX() + b.getWidth() + 1, b.getY() + b.getHeight() + 1, 0xFF44AA44);
            }
        }

        super.render(g, mx, my, dt);

        // Item preview (after super so it renders on top of the box background)
        if (!previewStack.isEmpty()) {
            var pose = g.pose();
            pose.pushPose();
            pose.translate(bx + PREVIEW_SZ / 2f, by + PREVIEW_SZ / 2f, 200);
            pose.scale(5.0f, 5.0f, 1.0f);
            g.renderItem(previewStack, -8, -8);
            pose.popPose();
        }

        // Color swatches for current layer (after super, on top of widget chrome)
        List<Integer> colors = currentColors();
        for (int i = 0; i < colors.size(); i++) {
            int sx = px + 100 + i * 18;
            int sy = py + 150;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17,
                   i == editingColorIdx ? 0xFFFFFFFF : 0xFF555555);
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF000000 | (colors.get(i) & 0xFFFFFF));
        }

        // Current-color swatch beside "Hex:" label
        g.fill(px + 120, py + 168, px + 132, py + 180,
               0xFF000000 | (colors.get(editingColorIdx) & 0xFFFFFF));

        g.drawCenteredString(font, String.format("%.2f×", layerSpeeds.get(selectedLayer)),  px + 175, py + 254, 0xFFFFFF);
        g.drawCenteredString(font, String.format("%.2f×", layerScales.get(selectedLayer)), px + 175, py + 270, 0xFFFFFF);

        // Item picker overlay — translated forward so it clips above the item preview (Z=200) and widgets
        if (showPicker) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            renderPicker(g, mx, my);
            g.pose().popPose();
        }
    }

    // ── Item picker rendering ─────────────────────────────────────────────────

    private static final int OW = 200, OH = VISIBLE_ROWS * ROW_H + 20;

    private int pickerOX() { return Math.max(2, Math.min(width - OW - 2, px + 8)); }
    private int pickerOY() { return Math.max(2, Math.min(height - OH - 2, py + 159)); }

    private void renderPicker(GuiGraphics g, int mx, int my) {
        int ox = pickerOX(), oy = pickerOY();

        g.fill(ox - 1, oy - 1, ox + OW + 1, oy + OH + 1, 0xFF666666);
        g.fill(ox, oy, ox + OW, oy + OH, 0xEE111111);

        searchBox.setX(ox + 2);
        searchBox.setY(oy + 3);
        searchBox.setWidth(OW - 4);
        searchBox.render(g, mx, my, 0);

        int listY = oy + 20;
        int sbX   = ox + OW - 6;

        for (int i = 0; i < VISIBLE_ROWS && pickerScroll + i < filteredItems.size(); i++) {
            Item item = filteredItems.get(pickerScroll + i);
            int ry = listY + i * ROW_H;
            boolean hovered = mx >= ox && mx < sbX && my >= ry && my < ry + ROW_H;
            if (hovered) g.fill(ox, ry, sbX, ry + ROW_H, 0x40FFFFFF);
            g.renderItem(new ItemStack(item), ox + 2, ry + 1);
            g.drawString(font, font.plainSubstrByWidth(item.getDescription().getString(), OW - 30),
                    ox + 20, ry + 5, 0xDDDDDD);
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
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showPicker) {
            int ox = pickerOX(), oy = pickerOY();

            if (mx >= ox + 2 && mx < ox + OW - 2 && my >= oy + 3 && my < oy + 17) {
                searchBox.mouseClicked(mx, my, btn);
            }

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
                }
            }
            return true;
        }

        // Swatch clicks
        if (my >= py + 150 && my < py + 166) {
            List<Integer> colors = currentColors();
            for (int i = 0; i < colors.size(); i++) {
                int sx = px + 100 + i * 18;
                if (mx >= sx && mx < sx + 16) {
                    editingColorIdx = i;
                    loadEditRGB();
                    syncChannelBoxes();
                    syncHexFromRGB();
                    return true;
                }
            }
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        if (showPicker) {
            if (searchBox.keyPressed(key, scancode, mods)) return true;
            if (key == 256) { showPicker = false; return true; }
            return true;
        }
        return super.keyPressed(key, scancode, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (showPicker) return searchBox.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (showPicker) {
            int ox = pickerOX(), oy = pickerOY();
            if (mx >= ox && mx < ox + OW && my >= oy && my < oy + OH) {
                int maxScroll = Math.max(0, filteredItems.size() - VISIBLE_ROWS);
                pickerScroll = Math.max(0, Math.min(maxScroll, pickerScroll - (int) Math.signum(delta)));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
