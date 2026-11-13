package com.circulation.prototype_eng.structure.tool;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.items.misc.GenericResourcePackageItem;
import com.circulation.prototype_eng.PrototypeEngineering;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

/**
 * One-position escrow: local stock first, then the currently authorized wireless network.
 */
final class ConstructionPayment {
    static final ConstructionPayment FREE = new ConstructionPayment(null, null, 0, null, new ObjectArrayList<>(), null);
    private final @Nullable GenericStack resource;
    private final @Nullable ConstructionMEAccess me;
    private final long networkAmount;
    private final @Nullable ItemTake item;
    private final ObjectArrayList<FluidEdit> fluids;
    private final @Nullable EntityPlayerMP owner;
    private long networkTaken;
    private ItemStack itemTaken = ItemStack.EMPTY;
    private int edited;

    private ConstructionPayment(@Nullable GenericStack resource, @Nullable ConstructionMEAccess me, long networkAmount,
                                @Nullable ItemTake item, ObjectArrayList<FluidEdit> fluids, @Nullable EntityPlayerMP owner) {
        this.resource = resource;
        this.me = me;
        this.networkAmount = networkAmount;
        this.item = item;
        this.fluids = fluids;
        this.owner = owner;
    }

    static long missingAmount(EntityPlayerMP player, GenericStack resource, boolean useMe) {
        long missing = resource.amount() - new ConstructionInventory(player).take(resource);
        if (missing <= 0 || !useMe) return missing;
        var me = ConstructionMEAccess.find(player);
        return me == null ? missing : missing - me.extract(resource.what(), missing, Actionable.SIMULATE);
    }

    static @Nullable ConstructionPayment prepare(EntityPlayerMP player, GenericStack resource, boolean useMe) {
        var inventories = ConstructionInventory.inventories(player);
        ItemTake item = null;
        long local = 0;
        var fluids = new ObjectArrayList<FluidEdit>();
        inventoryLoop:
        for (var inventory : inventories) {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                if (resource.what() instanceof AEItemKey key) {
                    var take = inventory.extractItem(slot, 1, true);
                    if (!key.matches(take) || take.getCount() != 1) continue;
                    item = new ItemTake(inventory, slot);
                    local = 1;
                    break inventoryLoop;
                }
                if (!(resource.what() instanceof AEFluidKey fluid) || !(inventory instanceof IItemHandlerModifiable writable))
                    continue;
                var stack = inventory.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                var after = stack.copy();
                var returned = new ObjectArrayList<ItemStack>();
                long taken = 0;
                int count = stack.getCount();
                for (int container = 0; container < count && local + taken < resource.amount(); container++) {
                    var copy = stack.copy();
                    copy.setCount(1);
                    var handler = FluidUtil.getFluidHandler(copy);
                    if (handler == null) break;
                    var drained = handler.drain(fluid.toStack((int) (resource.amount() - local - taken)), true);
                    if (drained == null || drained.amount <= 0 || !fluid.equals(AEFluidKey.of(drained))) break;
                    taken += drained.amount;
                    if (count == 1) after = handler.getContainer();
                    else {
                        after.shrink(1);
                        returned.add(handler.getContainer());
                    }
                }
                if (taken > 0) {
                    fluids.add(new FluidEdit(writable, slot, stack.copy(), after, returned, taken));
                    local += taken;
                }
                if (local == resource.amount()) break inventoryLoop;
            }
        }
        long remaining = resource.amount() - local;
        var me = remaining > 0 && useMe ? ConstructionMEAccess.find(player) : null;
        if (remaining > 0 && (me == null || me.extract(resource.what(), remaining, Actionable.SIMULATE) != remaining))
            return null;
        return new ConstructionPayment(resource, me, remaining, item, fluids, player);
    }

    private static void give(EntityPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.inventory.addItemStackToInventory(stack)) player.dropItem(stack, false);
    }

    boolean commit() {
        if (resource == null) return true;
        for (var edit : fluids)
            if (!ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.before)) return false;
        if (me != null && networkAmount > 0) {
            networkTaken = me.extract(resource.what(), networkAmount, Actionable.MODULATE);
            if (networkTaken != networkAmount) {
                refund(owner);
                return false;
            }
        }
        if (item != null) {
            itemTaken = item.inventory.extractItem(item.slot, 1, false);
            if (!(resource.what() instanceof AEItemKey key) || !key.matches(itemTaken) || itemTaken.getCount() != 1) {
                refund(owner);
                return false;
            }
        }
        for (var edit : fluids)
            if (!ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.before)) {
                refund(owner);
                return false;
            }
        for (var edit : fluids) {
            // Include the current setter in rollback even if a capability throws after writing.
            edited++;
            edit.inventory.setStackInSlot(edit.slot, edit.after.copy());
        }
        return true;
    }

    void finish() {
        if (owner == null) return;
        for (var edit : fluids) for (var container : edit.returned) give(owner, container);
        owner.inventory.markDirty();
    }

    void refund(EntityPlayer player) {
        while (edited > 0) {
            var edit = fluids.get(--edited);
            boolean restored;
            try {
                var current = edit.inventory.getStackInSlot(edit.slot);
                if (ItemStack.areItemStacksEqual(current, edit.after))
                    edit.inventory.setStackInSlot(edit.slot, edit.before.copy());
                restored = ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.before);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Failed to restore construction fluid container", failure);
                // Some setters throw after writing. Check before issuing a compensating resource.
                restored = ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.before);
            }
            // Preserve callback changes to this slot; return only the fluid taken by this payment.
            if (!restored && resource != null)
                give(player, GenericResourcePackageItem.wrap(resource.what(), edit.amount));
        }
        if (item != null && !itemTaken.isEmpty()) {
            give(player, item.inventory.insertItem(item.slot, itemTaken, false));
            itemTaken = ItemStack.EMPTY;
        }
        if (resource != null && networkTaken > 0) {
            long restored = me == null ? 0 : me.refund(resource.what(), networkTaken);
            if (restored < networkTaken)
                give(player, GenericResourcePackageItem.wrap(resource.what(), networkTaken - restored));
            networkTaken = 0;
        }
    }

    private record ItemTake(IItemHandler inventory, int slot) {
    }

    private record FluidEdit(IItemHandlerModifiable inventory, int slot, ItemStack before, ItemStack after,
                             ObjectArrayList<ItemStack> returned, long amount) {
    }
}
