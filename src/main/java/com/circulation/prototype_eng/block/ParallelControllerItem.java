package com.circulation.prototype_eng.block;

import net.minecraft.item.ItemStack;
import net.minecraft.util.text.translation.I18n;
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
    @SuppressWarnings("deprecation")
    public @NotNull String getItemStackDisplayName(@NotNull ItemStack stack) {
        return I18n.translateToLocal(controller.type().displayName().getTranslationKey());
    }
}
