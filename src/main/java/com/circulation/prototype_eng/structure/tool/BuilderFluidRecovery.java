package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEFluidKey;
import ae2.items.misc.GenericResourcePackageItem;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;

import java.util.Objects;

/** Delivers only fluid already drained from the world, retaining its exact type, amount and NBT. */
final class BuilderFluidRecovery {
    private BuilderFluidRecovery() { }

    static void deliver(EntityPlayer player, BlockPos position, FluidStack fluid) {
        var key = Objects.requireNonNull(AEFluidKey.of(fluid), "Drained fluid must have a positive AE key");
        var inventory = new PlayerInvWrapper(player.inventory);
        int remaining = fluid.amount;
        for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
            remaining = fill(player, inventory, slot, fluid, remaining);
            var stack = inventory.getStackInSlot(slot);
            var nested = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (nested instanceof IItemHandlerModifiable writable && nested != inventory)
                for (int inner = 0; inner < writable.getSlots() && remaining > 0; inner++)
                    remaining = fill(player, writable, inner, fluid, remaining);
        }
        if (remaining > 0)
            Block.spawnAsEntity(player.world, position, GenericResourcePackageItem.wrap(key, remaining));
        player.inventory.markDirty();
    }

    private static int fill(EntityPlayer player, IItemHandlerModifiable inventory, int slot, FluidStack fluid, int remaining) {
        // Each pass consumes at most the original stack's container count. A single tank receives one full request.
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
            if (!filled.isEmpty() && !player.inventory.addItemStackToInventory(filled)) player.dropItem(filled, false);
        }
        return remaining;
    }
}
