package net.tunamods.customglint.module.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.tunamods.customglint.module.item.GlintBagItem;
import net.tunamods.customglint.module.menu.GlintBagMenu;

/** Client screen for the Glint Bag. Reuses vanilla's 6-row chest background. Hovered-slot tooltips are drawn
 *  by the base screen's {@code extractTooltip}, so this only needs to paint the background. */
public class GlintBagScreen extends AbstractContainerScreen<GlintBagMenu> {

    private static final Identifier TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

    public GlintBagScreen(GlintBagMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 114 + GlintBagItem.ROWS * 18); // 176×222 for 6 rows
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = this.leftPos;
        int y = this.topPos;
        int gridHeight = GlintBagItem.ROWS * 18 + 17;
        // generic_54.png is 256×256: the grid rows sit at the top, the player-inventory strip at v=126.
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0f, 0f, this.imageWidth, gridHeight, 256, 256);
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y + gridHeight, 0f, 126f, this.imageWidth, 96, 256, 256);
        super.extractContents(g, mouseX, mouseY, partialTick); // labels + slot contents on top of the panel
    }
}
