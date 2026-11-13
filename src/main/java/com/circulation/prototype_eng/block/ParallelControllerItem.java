package com.circulation.prototype_eng.block;

import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Preserves the definition's complete LocalizationEnum key for external names.
 */
public final class ParallelControllerItem extends PrototypeBlockItem {
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
