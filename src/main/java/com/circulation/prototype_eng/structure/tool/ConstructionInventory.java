package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * Mutable copies used only by a single construction preflight; never consumes the live inventory.
 */
final class ConstructionInventory {
    private final ObjectArrayList<ItemStack> stacks = new ObjectArrayList<>();
    private final BooleanArrayList writable = new BooleanArrayList();
    private final IntArrayList extractable = new IntArrayList();

    void capture(IItemHandler inventory, int slot) {
        var stack = inventory.getStackInSlot(slot);
        stacks.add(stack.copy());
        writable.add(inventory instanceof IItemHandlerModifiable);
        var take = stack.isEmpty() ? ItemStack.EMPTY : inventory.extractItem(slot, stack.getCount(), true);
        extractable.add(ItemStack.areItemsEqual(stack, take) && ItemStack.areItemStackTagsEqual(stack, take)
            ? Math.min(stack.getCount(), take.getCount()) : 0);
    }

    final class Request {
        private final GenericStack resource;
        private long remaining;
        private int slot;
        private int container;
        private int count;

        Request(GenericStack resource) {
            this.resource = resource;
            remaining = resource.amount();
        }

        boolean step() {
            if (slot == stacks.size() || remaining <= 0) return false;
            var stack = stacks.get(slot);
            if (container == 0) count = stack.getCount();
            if (resource.what() instanceof AEItemKey item && item.matches(stack)) {
                int taken = (int) Math.min(remaining, Math.min(stack.getCount(), extractable.getInt(slot)));
                stack.shrink(taken);
                extractable.set(slot, extractable.getInt(slot) - taken);
                remaining -= taken;
            } else if (resource.what() instanceof AEFluidKey fluid && writable.getBoolean(slot) && !stack.isEmpty()) {
                var one = count == 1 ? stack : stack.copy();
                one.setCount(1);
                var handler = FluidUtil.getFluidHandler(one);
                if (handler != null) {
                    int requested = (int) Math.min(Integer.MAX_VALUE, remaining);
                    var drained = handler.drain(fluid.toStack(requested), true);
                    if (drained != null && fluid.equals(AEFluidKey.of(drained)) && drained.amount > 0) {
                        if (drained.amount > requested) throw new IllegalStateException("Construction container returned too much fluid");
                        remaining -= drained.amount;
                        var returned = handler.getContainer();
                        if (count == 1) {
                            stacks.set(slot, returned);
                            extractable.set(slot, returned.getCount());
                        } else {
                            stack.shrink(1);
                            extractable.set(slot, Math.min(stack.getCount(), extractable.getInt(slot)));
                            stacks.add(returned);
                            writable.add(true);
                            extractable.add(returned.getCount());
                            if (++container < count && remaining > 0) return true;
                        }
                    }
                }
            }
            slot++;
            container = 0;
            return true;
        }

        long taken() {
            return resource.amount() - remaining;
        }
    }
}
