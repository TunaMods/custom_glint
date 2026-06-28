package net.tunamods.customglint.module.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.tunamods.customglint.module.menu.GlintTableMenu;
import net.tunamods.customglint.module.menu.ModAttachments;

/**
 * Menu provider for the Glint Table. The table's contents (trims, dyes, modifiers) are stored per player
 * under {@link ModAttachments#GLINT_TABLE_CONTENTS}, so every table a player opens shows the same materials
 * and in-progress build. The block holds no items of its own.
 */
public class GlintTableBlockEntity extends BlockEntity implements MenuProvider {

    private static final Component TITLE = Component.translatable("container.customglint.glint_table");

    public GlintTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GLINT_TABLE_BE.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return TITLE;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        GlintTableMenu[] menuRef = new GlintTableMenu[1];
        SimpleContainer container = new SimpleContainer(GlintTableMenu.TABLE_SIZE) {
            @Override
            public int getMaxStackSize() {
                return 64; // let the per-slot caps govern (the name-tag slot still limits itself to 1)
            }

            @Override
            public void setChanged() {
                super.setChanged();
                player.setData(ModAttachments.GLINT_TABLE_CONTENTS.get(),
                        ItemContainerContents.fromItems(getItems()));
                if (menuRef[0] != null) menuRef[0].slotsChanged(this);
            }
        };
        player.getData(ModAttachments.GLINT_TABLE_CONTENTS.get()).copyInto(container.getItems());

        menuRef[0] = new GlintTableMenu(containerId, inventory, container,
                ContainerLevelAccess.create(this.level, this.worldPosition));
        return menuRef[0];
    }
}
