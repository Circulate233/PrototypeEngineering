package com.circulation.prototype_eng.api.machine;

import ae2.api.inventories.BaseInternalInventory;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.ObjIntConsumer;

/**
 * Controller-owned item slots. Use only after the machine's workers have joined.
 */
public final class ControllerInventory extends BaseInternalInventory {
    public static final int CAPACITY = 16;
    private final Runnable changed;
    @Nullable
    private ItemStack[] stacks;
    private int availableSlots;

    public ControllerInventory(Runnable changed) {
        this.changed = Objects.requireNonNull(changed);
    }

    public static int requireSlotCount(int count) {
        if (count < 0 || count > CAPACITY)
            throw new IllegalArgumentException("Controller inventory slots must be between 0 and " + CAPACITY);
        return count;
    }

    public int availableSlots() {
        return availableSlots;
    }

    /**
     * Closing a slot retains its contents, including through save/load and controller drops.
     */
    public void setAvailableSlots(int count) {
        availableSlots = requireSlotCount(count);
    }

    public boolean isAvailable(int slot) {
        return slot >= 0 && slot < availableSlots;
    }

    @Override
    public int size() {
        return CAPACITY;
    }

    /**
     * Borrowed native stack, including retained contents of closed slots. Do not mutate it directly.
     */
    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        Objects.checkIndex(slot, CAPACITY);
        var stack = stacks == null ? null : stacks[slot];
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /**
     * Native container synchronization and trusted writes; ordinary insertion must use insertItem.
     */
    @Override
    public void setItemDirect(int slot, @NotNull ItemStack stack) {
        Objects.checkIndex(slot, CAPACITY);
        Objects.requireNonNull(stack);
        if (stack.isEmpty() && stacks == null) return;
        if (stacks == null) stacks = new ItemStack[CAPACITY];
        stacks[slot] = stack.isEmpty() ? null : stack;
        changed.run();
    }

    @Override
    @SuppressWarnings("UnstableApiUsage") // AE native slots notify their backing inventory through this hook.
    public void sendChangeNotification(int slot) {
        Objects.checkIndex(slot, CAPACITY);
        changed.run();
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return isAvailable(slot);
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        Objects.checkIndex(slot, CAPACITY);
        return isAvailable(slot) ? super.insertItem(slot, stack, simulate) : stack;
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        Objects.checkIndex(slot, CAPACITY);
        return isAvailable(slot) ? super.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
    }

    /**
     * Visits every open slot in index order, including empty slots, without building a collection.
     */
    public void forEachAvailable(ObjIntConsumer<? super ItemStack> visitor) {
        Objects.requireNonNull(visitor);
        int count = availableSlots;
        for (int slot = 0; slot < count; slot++) visitor.accept(getStackInSlot(slot), slot);
    }

    /**
     * Visits occupied physical slots, including items retained while a slot is closed.
     */
    public void forEachStored(ObjIntConsumer<? super ItemStack> visitor) {
        Objects.requireNonNull(visitor);
        if (stacks == null) return;
        for (int slot = 0; slot < CAPACITY; slot++) {
            var stack = stacks[slot];
            if (stack != null && !stack.isEmpty()) visitor.accept(stack, slot);
        }
    }

    public void writeToNBT(NBTTagCompound tag, String key) {
        var saved = new NBTTagList();
        if (stacks != null) for (int slot = 0; slot < CAPACITY; slot++) {
            var stack = stacks[slot];
            if (stack == null || stack.isEmpty()) continue;
            var entry = stack.writeToNBT(new NBTTagCompound());
            entry.setInteger("Slot", slot);
            saved.appendTag(entry);
        }
        if (saved.tagCount() == 0) tag.removeTag(key);
        else tag.setTag(key, saved);
    }

    /**
     * Parses before replacing storage, so malformed entries cannot partially overwrite an inventory.
     */
    public void readFromNBT(NBTTagCompound tag, String key) {
        ItemStack[] replacement = null;
        if (tag.hasKey(key)) {
            var saved = StrictNbt.requireList(tag, key, 10);
            if (saved.tagCount() > CAPACITY)
                throw new IllegalArgumentException("Too many controller inventory entries");
            for (int index = 0; index < saved.tagCount(); index++) {
                var entry = saved.getCompoundTagAt(index);
                int slot = Objects.checkIndex(StrictNbt.requireInt(entry, "Slot"), CAPACITY);
                var stack = new ItemStack(entry);
                if (stack.isEmpty() || stack.getCount() > Math.min(64, stack.getMaxStackSize()))
                    throw new IllegalArgumentException("Invalid saved controller item");
                if (replacement == null) replacement = new ItemStack[CAPACITY];
                if (replacement[slot] != null)
                    throw new IllegalArgumentException("Duplicate controller inventory slot");
                replacement[slot] = stack;
            }
        }
        stacks = replacement;
    }

    public void addDrops(List<ItemStack> drops) {
        Objects.requireNonNull(drops);
        forEachStored((stack, _) -> drops.add(stack.copy()));
    }

    @Override
    public void clear() {
        if (stacks != null) {
            stacks = null;
            changed.run();
        }
    }
}
