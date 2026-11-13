package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;

/**
 * Mutable copies used only by a single construction preflight; never consumes the live inventory.
 */
final class ConstructionInventory {
    private final ObjectArrayList<ItemStack> stacks = new ObjectArrayList<>();
    private final BooleanArrayList writable = new BooleanArrayList();
    private final IntArrayList extractable = new IntArrayList();

    ConstructionInventory(EntityPlayer player) {
        this(inventories(player));
    }

    ConstructionInventory(Iterable<IItemHandler> inventories) {
        for (var inventory : inventories)
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var stack = inventory.getStackInSlot(slot);
                stacks.add(stack.copy());
                writable.add(inventory instanceof IItemHandlerModifiable);
                var take = inventory.extractItem(slot, stack.getCount(), true);
                extractable.add(ItemStack.areItemsEqual(stack, take) && ItemStack.areItemStackTagsEqual(stack, take) ? take.getCount() : 0);
            }
    }

    static ObjectArrayList<IItemHandler> inventories(EntityPlayer player) {
        var inventories = new ObjectArrayList<IItemHandler>();
        var direct = new PlayerInvWrapper(player.inventory);
        inventories.add(direct);
        var visited = new ReferenceOpenHashSet<IItemHandler>();
        visited.add(direct);
        for (int slot = 0; slot < direct.getSlots(); slot++) {
            var stack = direct.getStackInSlot(slot);
            var nested = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (nested != null && visited.add(nested)) inventories.add(nested);
        }
        return inventories;
    }

    long take(GenericStack required) {
        long remaining = required.amount();
        for (int slot = 0; slot < stacks.size() && remaining > 0; slot++) {
            var stack = stacks.get(slot);
            if (required.what() instanceof AEItemKey item && item.matches(stack)) {
                int taken = (int) Math.min(remaining, Math.min(stack.getCount(), extractable.getInt(slot)));
                stack.shrink(taken);
                extractable.set(slot, extractable.getInt(slot) - taken);
                remaining -= taken;
            } else if (required.what() instanceof AEFluidKey fluid && writable.getBoolean(slot) && !stack.isEmpty()) {
                int count = stack.getCount();
                for (int container = 0; container < count && remaining > 0; container++) {
                    var one = count == 1 ? stacks.get(slot).copy() : stack.copy();
                    one.setCount(1);
                    var handler = FluidUtil.getFluidHandler(one);
                    if (handler == null) break;
                    var drained = handler.drain(fluid.toStack((int) Math.min(Integer.MAX_VALUE, remaining)), true);
                    if (drained == null || !fluid.equals(AEFluidKey.of(drained)) || drained.amount <= 0) break;
                    remaining -= drained.amount;
                    if (count == 1) {
                        stacks.set(slot, handler.getContainer());
                        extractable.set(slot, handler.getContainer().getCount());
                        break;
                    } else {
                        stack.shrink(1);
                        stacks.add(handler.getContainer());
                        writable.add(true);
                        extractable.add(handler.getContainer().getCount());
                    }
                }
            }
        }
        return required.amount() - remaining;
    }
}
