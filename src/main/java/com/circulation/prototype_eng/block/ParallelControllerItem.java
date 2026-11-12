package com.circulation.prototype_eng.block;

import ae2.block.AEBaseBlockItem;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/** Preserves the definition's complete LocalizationEnum key for external names. */
public final class ParallelControllerItem extends AEBaseBlockItem {
    private final ParallelControllerBlock controller;

    public ParallelControllerItem(ParallelControllerBlock controller) {
        super(controller);
        this.controller = controller;
    }

    @Override
    public @NotNull String getItemStackDisplayName(@NotNull ItemStack stack) {
        return controller.type().displayName().getLocal();
    }
}
