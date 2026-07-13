package net.tunamods.customglint.module.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.tunamods.customglint.module.item.GlintBagItem;
import net.tunamods.customglint.module.menu.GlintBagMenu;

/** Client screen for the Glint Bag. Reuses vanilla's 6-row chest background. */
public class GlintBagScreen extends AbstractContainerScreen<GlintBagMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    public GlintBagScreen(GlintBagMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 114 + GlintBagItem.ROWS * 18; // 222 for 6 rows
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // AbstractContainerScreen.render draws the dim background + slots + labels but NOT the hovered-slot
        // tooltip in 1.21.1, so each screen must call renderTooltip itself (see GlintTableScreen).
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        int gridHeight = GlintBagItem.ROWS * 18 + 17;
        graphics.blit(TEXTURE, x, y, 0f, 0f, this.imageWidth, gridHeight, 256, 256);
        graphics.blit(TEXTURE, x, y + gridHeight, 0f, 126f, this.imageWidth, 96, 256, 256);
    }
}
