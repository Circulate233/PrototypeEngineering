package com.circulation.prototype_eng.block;

import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/** Uses the machine's localization contract without manufacturing another translation key. */
public final class MachineControllerItem extends ItemBlock {
    private final MachineControllerBlock controller;

    public MachineControllerItem(MachineControllerBlock controller) {
        super(controller);
        this.controller = controller;
    }

    @Override public @NotNull String getItemStackDisplayName(@NotNull ItemStack stack) {
        return controller.type().displayName().text().getUnformattedText();
    }
}
