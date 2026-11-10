package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemStack;

import javax.annotation.ParametersAreNonnullByDefault;

/** Standalone zero-slot client viewer; never registered as a server GUI or machine container. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class StructurePreviewContainer extends AEBaseContainer {
    public StructurePreviewContainer(InventoryPlayer inventory) {
        super(inventory, null);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return player == getPlayer();
    }

    @Override
    public ItemStack slotClick(int slot, int button, ClickType click, EntityPlayer player) {
        return ItemStack.EMPTY;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) { /* No cursor/inventory/drop ownership. */ }
}
