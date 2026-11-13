package com.circulation.prototype_eng.client.preview;

import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Bridges to the actual host's native slot and item-tooltip methods without adding server slots.
 */
public interface GuiPreviewNative {
    void prototype_eng$renderItemTooltip(ItemStack stack, int x, int y);

    interface Slots {
        void prototype_eng$renderSlot(Slot slot);
    }
}
