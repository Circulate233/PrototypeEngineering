package com.circulation.prototype_eng.storage;

import ae2.api.behaviors.GenericInternalInventory;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import ae2.helpers.externalstorage.GenericStackInv;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/** Slot view for automation. A slot retains its input/output role even in mixed hatches. */
public final class HatchInventoryPort implements GenericInternalInventory {
    private final BufferedResourceHatch hatch;
    private final boolean includeInput;
    private final boolean includeOutput;

    public HatchInventoryPort(BufferedResourceHatch hatch, boolean includeInput, boolean includeOutput) {
        this.hatch = Objects.requireNonNull(hatch);
        this.includeInput = includeInput;
        this.includeOutput = includeOutput;
    }

    private int inputSize() {
        return includeInput && hatch.input() != null ? hatch.input().size() : 0;
    }

    @Override
    public int size() {
        return inputSize() + (includeOutput && hatch.output() != null ? hatch.output().size() : 0);
    }

    private GenericStackInv inventory(int slot) {
        Objects.checkIndex(slot, size());
        return slot < inputSize() ? hatch.input() : hatch.output();
    }

    private int localSlot(int slot) {
        return slot < inputSize() ? slot : slot - inputSize();
    }

    @Override
    @Nullable
    public GenericStack getStack(int slot) {
        return inventory(slot).getStack(localSlot(slot));
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

    private GenericStackInv anyInventory() {
        return includeInput && hatch.input() != null ? hatch.input() : includeOutput ? hatch.output() : null;
    }

    @Override
    public long getMaxAmount(AEKey key) {
        GenericStackInv inv = anyInventory();
        return inv == null || !inv.isSupportedType(key) ? 0 : inv.getMaxAmount(key);
    }

    @Override
    public long getCapacity(AEKeyType type) {
        GenericStackInv inv = anyInventory();
        return inv == null || !inv.isSupportedType(type) ? 0 : inv.getCapacity(type);
    }

    @Override
    public boolean canInsert() {
        return hatch.isValid() && inputSize() != 0;
    }

    @Override
    public boolean canExtract() {
        return hatch.isValid() && includeOutput && hatch.output() != null;
    }

    @Override
    public boolean isSupportedType(AEKeyType type) {
        GenericStackInv inv = anyInventory();
        return inv != null && inv.isSupportedType(type);
    }

    @Override
    public boolean isAllowedIn(int slot, AEKey key) {
        GenericStackInv inv = inventory(slot);
        return canInsert() && slot < inputSize() && inv.isAllowedIn(localSlot(slot), key);
    }

    @Override
    public long insert(int slot, AEKey key, long amount, Actionable mode) {
        GenericStackInv inv = inventory(slot);
        return isAllowedIn(slot, key) ? inv.insert(localSlot(slot), key, amount, mode) : 0;
    }

    @Override
    public long extract(int slot, AEKey key, long amount, Actionable mode) {
        GenericStackInv inv = inventory(slot);
        return canExtract() && slot >= inputSize() ? inv.extract(localSlot(slot), key, amount, mode) : 0;
    }

    @Override
    public void setStack(int slot, @Nullable GenericStack next) {
        GenericStackInv inv = inventory(slot);
        GenericStack previous = getStack(slot);
        if (Objects.equals(previous, next)) {
            return;
        }
        if (!hatch.isValid()) {
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
        inv.setStack(localSlot(slot), next);
    }

    private void each(Consumer<GenericStackInv> action) {
        if (includeInput && hatch.input() != null) {
            action.accept(hatch.input());
        }
        if (includeOutput && hatch.output() != null) {
            action.accept(hatch.output());
        }
    }

    @Override
    public void beginBatch() { each(GenericStackInv::beginBatch); }
    @Override
    public void endBatch() { each(GenericStackInv::endBatch); }
    @Override
    public void endBatchSuppressed() { each(GenericStackInv::endBatchSuppressed); }
    @Override
    public void onChange() { each(GenericStackInv::onChange); }
}
