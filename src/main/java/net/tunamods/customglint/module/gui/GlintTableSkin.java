package net.tunamods.customglint.module.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.tunamods.customglint.common.CustomGlint;

import java.util.Locale;

/**
 * A Glint Table skin: a background PNG plus a small palette for the elements the screen draws over it
 * (buttons, the scrolling grid cells, selection rings, labels). The window, recesses and fixed slot wells
 * are part of the PNG; the palette only colours what isn't in the image. Add a skin by appending it to
 * {@link #ALL} with its palette and dropping a matching {@code glint_table_<name>.png}; the in-table button
 * cycles them and the choice persists.
 */
class GlintTableSkin {

    final String name;

    // Palette for the code-drawn overlays (copied into the screen by applySkin).
    int guiFace, guiLight, guiShadow, guiBorder;
    int slotFace, slotDark;
    int labelHdr, costOk, costBad;
    int ringMain, ringDonor;
    int dimGhost, dimPreview;
    int colorUnset, btnDisabled, hoverTint, btnHover;

    /** Background texture: the window, recesses and fixed slot wells. */
    Identifier bgTexture;

    GlintTableSkin(String name) {
        this.name = name;
    }

    void texture(Identifier t) {
        this.bgTexture = t;
    }

    /** Blit the background PNG over the full window. */
    void windowPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.blit(RenderPipelines.GUI_TEXTURED, bgTexture, x, y, 0f, 0f, w, h, w, h);
    }

    /** A raised button / small panel with the given face color. */
    void raised(GuiGraphicsExtractor g, int x, int y, int w, int h, int face) {
        g.fill(x, y, x + w, y + h, guiBorder);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, guiLight);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, guiLight);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, guiShadow);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, guiShadow);
    }

    /** A sunken 18×18 slot well at (sx, sy), the scrolling grid cells and the toggle dye wells. */
    void slotWell(GuiGraphicsExtractor g, int sx, int sy) {
        g.fill(sx, sy, sx + 18, sy + 18, guiLight);
        g.fill(sx, sy, sx + 17, sy + 17, slotDark);
        g.fill(sx + 1, sy + 1, sx + 17, sy + 17, slotFace);
    }

    /** A 1px sunken bevel over a square cell (frames the layer / color shards). */
    void shardBevel(GuiGraphicsExtractor g, int x, int y, int s) {
        g.fill(x, y, x + s, y + 1, slotDark);
        g.fill(x, y, x + 1, y + s, slotDark);
        g.fill(x, y + s - 1, x + s, y + s, guiLight);
        g.fill(x + s - 1, y, x + s, y + s, guiLight);
    }

    // ── Skins ─────────────────────────────────────────────────────────────────

    static final GlintTableSkin DEFAULT = new GlintTableSkin("Default");
    static final GlintTableSkin DARK = new GlintTableSkin("Dark");
    static final GlintTableSkin FORGE = new GlintTableSkin("Forge");

    static {
        DEFAULT.guiFace = 0xFFC6C6C6; DEFAULT.guiLight = 0xFFFFFFFF; DEFAULT.guiShadow = 0xFF555555; DEFAULT.guiBorder = 0xFF000000;
        DEFAULT.slotFace = 0xFF8B8B8B; DEFAULT.slotDark = 0xFF373737;
        DEFAULT.labelHdr = 0xFF404040; DEFAULT.costOk = 0xFF2FAA2F; DEFAULT.costBad = 0xFFC23030;
        DEFAULT.ringMain = 0xFF55FF55; DEFAULT.ringDonor = 0xFFFFAA33;
        DEFAULT.dimGhost = 0x99000000; DEFAULT.dimPreview = 0x66000000;
        DEFAULT.colorUnset = 0xFF6E6E6E; DEFAULT.btnDisabled = 0xFF373737;
        DEFAULT.hoverTint = 0x80FFFFFF; DEFAULT.btnHover = 0xFFD6D6D6;

        DARK.guiFace = 0xFF2B2B30; DARK.guiLight = 0xFF45454C; DARK.guiShadow = 0xFF161619; DARK.guiBorder = 0xFF000000;
        DARK.slotFace = 0xFF1B1B1F; DARK.slotDark = 0xFF050506;
        DARK.labelHdr = 0xFFC8C8CE; DARK.costOk = 0xFF4CC44C; DARK.costBad = 0xFFE05050;
        DARK.ringMain = 0xFF55FF55; DARK.ringDonor = 0xFFFFAA33;
        DARK.dimGhost = 0x99000000; DARK.dimPreview = 0x66000000;
        DARK.colorUnset = 0xFF3A3A40; DARK.btnDisabled = 0xFF1A1A1D;
        DARK.hoverTint = 0x55FFFFFF; DARK.btnHover = 0xFF3C3C44;

        FORGE.guiFace = 0xFF52565C; FORGE.guiLight = 0xFFA9ACB0; FORGE.guiShadow = 0xFF1F2124; FORGE.guiBorder = 0xFF0B0C0D;
        FORGE.slotFace = 0xFF232529; FORGE.slotDark = 0xFF141517;
        FORGE.labelHdr = 0xFFE2E6EC; FORGE.costOk = 0xFF66E04C; FORGE.costBad = 0xFFE0604C;
        FORGE.ringMain = 0xFFFF8A3A; FORGE.ringDonor = 0xFFFFC24A;
        FORGE.dimGhost = 0x99000000; FORGE.dimPreview = 0x66000000;
        FORGE.colorUnset = 0xFF1D1E21; FORGE.btnDisabled = 0xFF2D2F33;
        FORGE.hoverTint = 0x55FFFFFF; FORGE.btnHover = 0xFF787B80;
    }

    static final GlintTableSkin[] ALL = {
            DEFAULT, DARK, FORGE
    };

    static {
        // Background PNG per skin: assets/customglint/textures/gui/glint_table_<name>.png
        for (GlintTableSkin s : ALL) {
            s.texture(CustomGlint.res("textures/gui/glint_table_" + s.name.toLowerCase(Locale.ROOT) + ".png"));
        }
    }

    /** Warm every skin's background PNG so cycling skins in-menu doesn't trigger a first-time cold load
     *  (disk read + PNG decode + GPU upload) mid-interaction. Idempotent, getTexture caches after the
     *  first load, so this only does work the first time the table is opened in a session. */
    static void preloadTextures() {
        for (GlintTableSkin s : ALL) Minecraft.getInstance().getTextureManager().getTexture(s.bgTexture);
    }

    static GlintTableSkin byIndex(int i) {
        return ALL[Math.floorMod(i, ALL.length)];
    }

    static int indexOf(GlintTableSkin s) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == s) return i;
        return 0;
    }
}
