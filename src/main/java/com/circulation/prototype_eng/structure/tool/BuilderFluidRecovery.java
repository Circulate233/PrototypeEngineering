package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEFluidKey;
import ae2.items.misc.GenericResourcePackageItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;

import java.util.Objects;

/**
 * 只交付已从世界抽取的流体，保留类型、数量和 NBT。
 */
final class BuilderFluidRecovery {
    private BuilderFluidRecovery() {
    }

    static void deliver(EntityPlayerMP player, FluidStack fluid, boolean useAe) {
        var key = Objects.requireNonNull(AEFluidKey.of(fluid), "Drained fluid must have a positive AE key");
        int remaining = fluid.amount;
        var ae = useAe ? ConstructionMEAccess.find(player) : null;
        if (ae != null) remaining -= (int) ae.insert(key, remaining);
        if (remaining == 0) return;
        var inventory = new PlayerInvWrapper(player.inventory);
        for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
            remaining = fill(player, inventory, slot, fluid, remaining);
            var stack = inventory.getStackInSlot(slot);
            var nested = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (nested instanceof IItemHandlerModifiable writable && nested != inventory)
                for (int inner = 0; inner < writable.getSlots() && remaining > 0; inner++)
                    remaining = fill(player, writable, inner, fluid, remaining);
        }
        if (remaining > 0)
            BuilderDropRecovery.give(player, GenericResourcePackageItem.wrap(key, remaining));
        player.inventory.markDirty();
    }

    private static int fill(EntityPlayer player, IItemHandlerModifiable inventory, int slot, FluidStack fluid, int remaining) {
        int containers = inventory.getStackInSlot(slot).getCount();
        for (int attempt = 0; attempt < containers && remaining > 0; attempt++) {
            var original = inventory.getStackInSlot(slot);
            if (original.isEmpty()) break;
            var before = original.copy();
            var copy = original.copy();
            copy.setCount(1);
            var handler = FluidUtil.getFluidHandler(copy);
            if (handler == null) break;
            var offered = fluid.copy();
            offered.amount = remaining;
            int accepted = handler.fill(offered, true);
            if (accepted <= 0) break;
            if (accepted > remaining) throw new IllegalStateException("Fluid container accepted more than offered");
            if (!ItemStack.areItemStacksEqual(inventory.getStackInSlot(slot), before)) break;
            var filled = handler.getContainer();
            if (original.getCount() == 1) {
                inventory.setStackInSlot(slot, filled);
                remaining -= accepted;
                break;
            }
            before.shrink(1);
            inventory.setStackInSlot(slot, before);
            remaining -= accepted;
            BuilderDropRecovery.give(player, filled);
        }
        return remaining;
    }
}
