package com.circulation.prototype_eng.storage;

import ae2.api.behaviors.GenericInternalInventory;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.List;

import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;

/** Slot view for automation. A slot retains its input/output role even in mixed hatches. */
public final class HatchInventoryPort implements GenericInternalInventory {
    @Nullable private final SlottedResourceStorage input;
    @Nullable private final SlottedResourceStorage output;
    private final List<StorageContext> contexts;
    private final ThreadLocal<Boolean> batching = new ThreadLocal<>();

    public HatchInventoryPort(SlottedResourceHatch hatch, boolean includeInput, boolean includeOutput) {
        Objects.requireNonNull(hatch);
        input = includeInput ? hatch.input() : null;
        output = includeOutput ? hatch.output() : null;
        if (input != null && output != null && input.context() != output.context()) {
            contexts = input.context().order() < output.context().order()
                ? List.of(input.context(), output.context()) : List.of(output.context(), input.context());
        } else if (input != null) {
            contexts = List.of(input.context());
        } else {
            contexts = output == null ? List.of() : List.of(output.context());
        }
    }

    private int inputSize() {
        return input == null ? 0 : input.size();
    }

    @Override
    public int size() {
        return Math.addExact(inputSize(), output == null ? 0 : output.size());
    }

    private SlottedResourceStorage inventory(int slot) {
        Objects.checkIndex(slot, size());
        return Objects.requireNonNull(slot < inputSize() ? input : output, "Missing hatch storage side");
    }

    private int localSlot(int slot) {
        return slot < inputSize() ? slot : slot - inputSize();
    }

    @Override
    @Nullable
    public GenericStack getStack(int slot) {
        SlottedResourceStorage inv = inventory(slot);
        inv.context().lock();
        try {
            GenericStack stack = inv.getStack(localSlot(slot));
            if (stack == null || slot < inputSize()) {
                return stack;
            }
            long available = inv.availableAmount(localSlot(slot));
            return available == 0 ? null : new GenericStack(stack.what(), available);
        } finally {
            inv.context().unlock();
        }
    }

    @Override
    @Nullable
    public AEKey getKey(int slot) {
        GenericStack stack = getStack(slot);
        return stack == null ? null : stack.what();
    }

    @Override
    public long getAmount(int slot) {
        GenericStack stack = getStack(slot);
        return stack == null ? 0 : stack.amount();
    }

    @Override
    public long getMaxAmount(AEKey key) {
        return Math.max(input == null || !input.isSupportedType(key.getType()) ? 0 : input.getMaxAmount(key),
            output == null || !output.isSupportedType(key.getType()) ? 0 : output.getMaxAmount(key));
    }

    /** Without a slot, AE requests a capacity estimate: the maximum of either supported side. */
    @Override
    public long getCapacity(AEKeyType type) {
        return Math.max(input == null || !input.isSupportedType(type) ? 0 : input.getCapacity(type),
            output == null || !output.isSupportedType(type) ? 0 : output.getCapacity(type));
    }

    public long getCapacity(int slot, AEKeyType type) {
        var side = inventory(slot);
        return side.isSupportedType(type) ? side.getCapacity(type) : 0;
    }

    @Override
    public boolean canInsert() {
        return input != null && input.isValid();
    }

    @Override
    public boolean canExtract() {
        return output != null && output.isValid();
    }

    @Override
    public boolean isSupportedType(AEKeyType type) {
        return input != null && input.isSupportedType(type) || output != null && output.isSupportedType(type);
    }

    @Override
    public boolean isAllowedIn(int slot, AEKey key) {
        SlottedResourceStorage inv = inventory(slot);
        return canInsert() && slot < inputSize() && inv.isAllowedIn(localSlot(slot), key);
    }

    @Override
    public long insert(int slot, AEKey key, long amount, Actionable mode) {
        SlottedResourceStorage inv = inventory(slot);
        return isAllowedIn(slot, key) ? inv.insert(localSlot(slot), key, amount, mode) : 0;
    }

    @Override
    public long extract(int slot, AEKey key, long amount, Actionable mode) {
        SlottedResourceStorage inv = inventory(slot);
        return canExtract() && slot >= inputSize() ? inv.extract(localSlot(slot), key, amount, mode) : 0;
    }

    @Override
    public void setStack(int slot, @Nullable GenericStack next) {
        SlottedResourceStorage inv = inventory(slot);
        inv.context().lock();
        try {
            GenericStack previous = inv.getStack(localSlot(slot));
            if (Objects.equals(previous, next)) {
                return;
            }
            if (!inv.isValid()) {
                throw new IllegalStateException("Hatch is no longer available");
            }
            if (next != null && (next.amount() <= 0 || !inv.isAllowedIn(localSlot(slot), next.what())
                || next.amount() > inv.getMaxAmount(next.what()))) {
                throw new IllegalArgumentException("Stack does not fit this hatch slot");
            }
            boolean sameKey = previous != null && next != null && previous.what().equals(next.what());
            boolean removes = previous != null && (!sameKey || next.amount() < previous.amount());
            boolean adds = next != null && (!sameKey || next.amount() > previous.amount());
            if ((removes && slot < inputSize()) || (adds && slot >= inputSize())) {
                throw new UnsupportedOperationException("Cannot reverse this hatch slot's direction");
            }
            if (!inv.tryReplaceSlot(localSlot(slot), previous, next)) {
                throw new IllegalStateException("Hatch slot replacement conflicts with a reservation");
            }
        } finally {
            inv.context().unlock();
        }
    }

    @Override
    public void beginBatch() {
        if (Boolean.TRUE.equals(batching.get())) {
            throw new IllegalStateException("Inventory batch is already open");
        }
        StorageContext.lockAll(contexts);
        batching.set(true);
    }

    @Override
    public void endBatch() {
        finishBatch();
        onChange();
    }

    @Override
    public void endBatchSuppressed() {
        finishBatch();
    }

    @Override
    public void onChange() {
        contexts.forEach(StorageContext::publishChanges);
    }

    private void finishBatch() {
        if (!Boolean.TRUE.equals(batching.get())) {
            throw new IllegalStateException("Inventory batch is not open on this thread");
        }
        for (int i = contexts.size() - 1; i >= 0; i--) {
            contexts.get(i).unlockDeferred();
        }
        batching.remove();
    }
}
