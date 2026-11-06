package com.circulation.prototype_eng.storage;

import ae2.api.behaviors.GenericStackDisplayInventory;
import ae2.api.inventories.InternalInventory;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/** Committed server slot display, with a separate client-only cache for vanilla slot synchronization. */
public final class HatchGuiInventory implements InternalInventory, GenericStackDisplayInventory {
    private final SlottedResourceStorage storage;
    private final boolean client;
    private final ItemStack[] syncedStacks;

    public HatchGuiInventory(SlottedResourceStorage storage, boolean client) {
        this.storage = Objects.requireNonNull(storage);
        this.client = client;
        syncedStacks = new ItemStack[client ? storage.size() : 0];
        Arrays.fill(syncedStacks, ItemStack.EMPTY);
    }

    @Override
    public int size() {
        return storage.size();
    }

    @Override
    public int getSlotLimit(int slot) {
        Objects.checkIndex(slot, size());
        return (int) Math.min(Integer.MAX_VALUE, storage.getCapacity(AEKeyType.items()));
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        if (client) {
            return syncedStacks[Objects.checkIndex(slot, size())].copy();
        }
        GenericStack stack = storage.getStack(slot);
        if (stack != null && stack.what() instanceof AEItemKey item
            && stack.amount() > 0 && stack.amount() <= item.getMaxStackSize()) {
            return item.toStack((int) stack.amount());
        }
        return GenericStack.wrapInItemStack(stack);
    }

    @Override
    public void setItemDirect(int slot, @NotNull ItemStack stack) {
        if (!client) {
            throw new UnsupportedOperationException("Server hatch GUI writes require container actions");
        }
        syncedStacks[Objects.checkIndex(slot, size())] = stack.copy();
    }

    @Nullable
    private GenericStack displayStack(int slot) {
        if (!client) {
            return storage.getStack(slot);
        }
        ItemStack stack = syncedStacks[Objects.checkIndex(slot, size())];
        GenericStack generic = GenericStack.unwrapItemStack(stack);
        if (generic != null) {
            return generic;
        }
        AEItemKey key = AEItemKey.of(stack);
        return key == null ? null : new GenericStack(key, stack.getCount());
    }

    @Override
    public boolean hasGenericDisplayStack(int slot) {
        return displayStack(slot) != null;
    }

    @Override
    @Nullable
    public AEKey getDisplayKey(int slot) {
        GenericStack stack = displayStack(slot);
        return stack == null ? null : stack.what();
    }

    @Override
    public long getDisplayAmount(int slot) {
        GenericStack stack = displayStack(slot);
        return stack == null ? 0 : stack.amount();
    }
}
