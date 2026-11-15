package com.circulation.prototype_eng.block;

import net.minecraft.item.ItemStack;
import net.minecraft.util.text.translation.I18n;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the machine's complete translation key on both physical sides.
 */
public class MachineControllerItem extends PrototypeBlockItem {
    private final MachineControllerBlock<?> controller;

    public MachineControllerItem(MachineControllerBlock<?> controller) {
        super(controller);
        this.controller = controller;
    }

    @Override
    @SuppressWarnings("deprecation")
    public @NotNull String getItemStackDisplayName(@NotNull ItemStack stack) {
        return I18n.translateToLocal(controller.type().displayName().getTranslationKey());
    }
}
