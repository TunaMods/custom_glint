package net.tunamods.customglint.module.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Common frame palette + panel drawing shared by the Glint Table and wand editor skins. A skin is a
 * background PNG plus the colors the screen draws over it; the window frame and recesses live in the PNG,
 * the palette colors everything the code draws so the same layout reads on a light or dark theme.
 */
abstract class SkinBase {

    final String name;

    // Frame / bevel palette copied into the screen when a skin is applied.
    int guiFace, guiLight, guiShadow, guiBorder;

    /** Background texture: the window frame and recesses. */
    ResourceLocation bgTexture;

    SkinBase(String name) {
        this.name = name;
    }

    void texture(ResourceLocation t) {
        this.bgTexture = t;
    }

    /** Blit the background PNG over the full panel (authored at the panel size). */
    void windowPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.blit(bgTexture, x, y, 0f, 0f, w, h, w, h);
    }

    /** A raised button / small panel with the given face color. */
    void raised(GuiGraphics g, int x, int y, int w, int h, int face) {
        g.fill(x, y, x + w, y + h, guiBorder);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, guiLight);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, guiLight);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, guiShadow);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, guiShadow);
    }
}
