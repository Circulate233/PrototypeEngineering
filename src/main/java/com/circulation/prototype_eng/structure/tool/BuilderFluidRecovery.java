package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEFluidKey;
import ae2.items.misc.GenericResourcePackageItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * Delivers fluid already extracted from the world, preserving type, amount and NBT.
 */
final class BuilderFluidRecovery {
    private BuilderFluidRecovery() {
    }

    @SuppressWarnings("DataFlowIssue")
    static void deliver(EntityPlayerMP player, FluidStack fluid, @Nullable ConstructionMEAccess ae) {
        var key = AEFluidKey.of(fluid);
        int remaining = fluid.amount;
        if (ae != null) remaining -= (int) ae.insert(key, remaining);
        if (remaining == 0) return;
        var offered = fluid.copy();
        var inventory = new PlayerInvWrapper(player.inventory);
        for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
            remaining = fill(player, inventory, slot, offered, remaining);
            var stack = inventory.getStackInSlot(slot);
            var nested = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (nested instanceof IItemHandlerModifiable writable)
                for (int inner = 0; inner < writable.getSlots() && remaining > 0; inner++)
                    remaining = fill(player, writable, inner, offered, remaining);
        }
        if (remaining > 0)
            BuilderDropRecovery.give(player, GenericResourcePackageItem.wrap(key, remaining));
        player.inventory.markDirty();
    }

    private static int fill(EntityPlayer player, IItemHandlerModifiable inventory, int slot, FluidStack fluid, int remaining) {
        int containers = inventory.getStackInSlot(slot).getCount();
        for (int attempt = 0; attempt < containers && remaining > 0; attempt++) {
            var original = inventory.getStackInSlot(slot);
            var copy = original.copy();
            copy.setCount(1);
            var handler = FluidUtil.getFluidHandler(copy);
            if (handler == null) break;
            fluid.amount = remaining;
            int accepted = handler.fill(fluid, true);
            if (accepted <= 0) break;
            var filled = handler.getContainer();
            if (original.getCount() == 1) {
                inventory.setStackInSlot(slot, filled);
                remaining -= accepted;
                break;
            }
            if (inventory.extractItem(slot, 1, false).isEmpty()) break;
            remaining -= accepted;
            BuilderDropRecovery.give(player, filled);
        }
        return remaining;
    }
}
