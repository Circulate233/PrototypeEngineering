package com.circulation.prototype_eng.structure.tool;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.items.misc.GenericResourcePackageItem;
import com.circulation.prototype_eng.PrototypeEngineering;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * Pays one block's material from player inventory, then from the builder's bound network.
 */
final class ConstructionPayment {
    static final ConstructionPayment FREE = new ConstructionPayment(null, null, 0, null, new ObjectArrayList<>(), null);
    private final @Nullable GenericStack resource;
    private final @Nullable ConstructionMEAccess me;
    private final long networkAmount;
    private final @Nullable ItemTake item;
    private final ObjectArrayList<FluidEdit> fluids;
    private final @Nullable Sources sources;
    private long networkTaken;
    private ItemStack itemTaken = ItemStack.EMPTY;
    private int edited;

    private ConstructionPayment(@Nullable GenericStack resource, @Nullable ConstructionMEAccess me, long networkAmount,
                                @Nullable ItemTake item, ObjectArrayList<FluidEdit> fluids, @Nullable Sources sources) {
        this.resource = resource;
        this.me = me;
        this.networkAmount = networkAmount;
        this.item = item;
        this.fluids = fluids;
        this.sources = sources;
    }

    static final class Sources {
        private final EntityPlayerMP owner;
        private final PlayerInvWrapper direct;
        private final Object2ObjectOpenHashMap<AEKey, ItemTake> verified = new Object2ObjectOpenHashMap<>();
        private final Reference2IntOpenHashMap<IItemHandler> origins = new Reference2IntOpenHashMap<>();
        private final ObjectArrayList<IItemHandler> inventories = new ObjectArrayList<>();
        private boolean enumerated;

        Sources(EntityPlayerMP owner) {
            this.owner = owner;
            direct = new PlayerInvWrapper(owner.inventory);
            inventories.add(direct);
            origins.defaultReturnValue(-1);
        }

        void beginSlice() {
            enumerated = false;
        }

        ObjectArrayList<IItemHandler> inventories() {
            if (!enumerated) rescan();
            return inventories;
        }

        private void rescan() {
            inventories.size(1);
            origins.clear();
            for (int slot = 0; slot < direct.getSlots(); slot++) {
                var nested = direct.getStackInSlot(slot).getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
                if (nested != null && !origins.containsKey(nested)) {
                    origins.put(nested, slot);
                    inventories.add(nested);
                }
            }
            enumerated = true;
        }

        private boolean current(IItemHandler handler) {
            if (handler == direct) return true;
            int anchor = origins.getInt(handler);
            return anchor >= 0 && direct.getStackInSlot(anchor).getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null) == handler;
        }

        private @Nullable ItemTake find(AEItemKey key) {
            var previous = verified.get(key);
            if (previous != null && current(previous.inventory) && matches(previous.inventory, previous.slot, key))
                return previous;
            verified.remove(key);
            var handlers = inventories();
            for (int index = 0; index < handlers.size(); index++) {
                var handler = handlers.get(index);
                if (!current(handler)) continue;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    if (!matches(handler, slot, key)) continue;
                    var take = new ItemTake(handler, slot);
                    verified.put(key, take);
                    return take;
                }
            }
            return null;
        }

        private static boolean matches(IItemHandler handler, int slot, AEItemKey key) {
            if (slot >= handler.getSlots() || !key.matches(handler.getStackInSlot(slot))) return false;
            var take = handler.extractItem(slot, 1, true);
            return key.matches(take) && take.getCount() == 1;
        }

        Quote prepare(GenericStack resource, @Nullable ConstructionMEAccess me) {
            if (resource.what() instanceof AEItemKey key) {
                var item = find(key);
                return quote(resource, me, item == null ? 0 : 1, item, new ObjectArrayList<>());
            }
            var fluids = new ObjectArrayList<FluidEdit>();
            long local = 0;
            if (resource.what() instanceof AEFluidKey fluid) {
                var handlers = inventories();
                for (int index = 0; index < handlers.size() && local < resource.amount(); index++) {
                    var handler = handlers.get(index);
                    if (!(handler instanceof IItemHandlerModifiable writable) || !current(handler)) continue;
                    for (int slot = 0; slot < handler.getSlots() && local < resource.amount(); slot++) {
                        var stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty()) continue;
                        var after = stack.copy();
                        var returned = new ObjectArrayList<ItemStack>();
                        long taken = 0;
                        int count = stack.getCount();
                        for (int container = 0; container < count && local + taken < resource.amount(); container++) {
                            var copy = count == 1 ? after : stack.copy();
                            copy.setCount(1);
                            var fluidHandler = FluidUtil.getFluidHandler(copy);
                            if (fluidHandler == null) break;
                            int request = (int) Math.min(Integer.MAX_VALUE, resource.amount() - local - taken);
                            var drained = fluidHandler.drain(fluid.toStack(request), true);
                            if (drained == null || drained.amount <= 0 || !fluid.equals(AEFluidKey.of(drained))) break;
                            if (drained.amount > request) throw new IllegalStateException("Construction container returned too much fluid");
                            taken += drained.amount;
                            if (count == 1) after = fluidHandler.getContainer();
                            else {
                                after.shrink(1);
                                returned.add(fluidHandler.getContainer());
                            }
                        }
                        if (taken > 0) {
                            fluids.add(new FluidEdit(writable, slot, stack.copy(), after, returned, taken));
                            local += taken;
                        }
                    }
                }
            }
            return quote(resource, me, local, null, fluids);
        }

        private Quote quote(GenericStack resource, @Nullable ConstructionMEAccess me, long local, @Nullable ItemTake item,
                            ObjectArrayList<FluidEdit> fluids) {
            long remaining = resource.amount() - local;
            long available = me == null || remaining <= 0 ? 0 : me.extract(resource.what(), remaining, Actionable.SIMULATE);
            if (available < 0 || available > remaining) throw new IllegalStateException("Invalid construction network simulation");
            if (available != remaining) return new Quote(null, remaining - available);
            return new Quote(new ConstructionPayment(resource, me, remaining, item, fluids, this), 0);
        }
    }

    record Quote(@Nullable ConstructionPayment payment, long missing) {
    }

    private static void give(EntityPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.inventory.addItemStackToInventory(stack)) player.dropItem(stack, false);
    }

    boolean commit() {
        if (resource == null || sources == null) return true;
        if (!fluidsCurrent(sources)) return false;
        if (me != null && networkAmount > 0) {
            networkTaken = me.extract(resource.what(), networkAmount, Actionable.MODULATE);
            if (networkTaken < 0 || networkTaken > networkAmount)
                throw new IllegalStateException("Invalid construction network extraction amount");
            if (networkTaken != networkAmount) return false;
        }
        if (item != null) {
            if (!(resource.what() instanceof AEItemKey key) || !sources.current(item.inventory)) return false;
            itemTaken = item.inventory.extractItem(item.slot, 1, false);
            if (!key.matches(itemTaken) || itemTaken.getCount() != 1) return false;
        }
        if (!fluidsCurrent(sources)) return false;
        for (int index = 0; index < fluids.size(); index++) {
            var edit = fluids.get(index);
            if (!sourceCurrent(sources, edit)) return false;
            edited++;
            edit.inventory.setStackInSlot(edit.slot, edit.after.copy());
            if (!ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.after)) return false;
        }
        return true;
    }

    private boolean fluidsCurrent(Sources current) {
        for (int index = 0; index < fluids.size(); index++) {
            if (!sourceCurrent(current, fluids.get(index))) return false;
        }
        return true;
    }

    private static boolean sourceCurrent(Sources current, FluidEdit edit) {
        return current.current(edit.inventory) && edit.slot < edit.inventory.getSlots()
            && ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.before);
    }

    void finish() {
        if (sources == null) return;
        var owner = sources.owner;
        for (int index = 0; index < fluids.size(); index++) {
            var edit = fluids.get(index);
            for (int containerIndex = 0; containerIndex < edit.returned.size(); containerIndex++)
                give(owner, edit.returned.get(containerIndex));
        }
    }

    void refund(EntityPlayer player) {
        while (edited > 0) {
            var edit = fluids.get(--edited);
            boolean restored;
            try {
                var current = edit.inventory.getStackInSlot(edit.slot);
                if (ItemStack.areItemStacksEqual(current, edit.after))
                    edit.inventory.setStackInSlot(edit.slot, edit.before);
                restored = ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.before);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Failed to restore construction fluid container", failure);
                restored = ItemStack.areItemStacksEqual(edit.inventory.getStackInSlot(edit.slot), edit.before);
            }
            if (!restored && resource != null)
                give(player, GenericResourcePackageItem.wrap(resource.what(), edit.amount));
        }
        if (item != null && !itemTaken.isEmpty()) {
            give(player, sources != null && sources.current(item.inventory) && item.slot < item.inventory.getSlots()
                ? item.inventory.insertItem(item.slot, itemTaken, false) : itemTaken);
            itemTaken = ItemStack.EMPTY;
        }
        if (resource != null && networkTaken > 0) {
            long restored = me == null ? 0 : me.insert(resource.what(), networkTaken);
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
