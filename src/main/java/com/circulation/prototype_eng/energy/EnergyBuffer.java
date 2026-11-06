package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.energy.EnergyHatch;

import java.util.Objects;

/**
 * Server-thread-owned FE balance. A field needs no atomic wrapper here.
 */
public final class EnergyBuffer implements EnergyHatch {
    private final long capacity;
    private long maxReceive;
    private long maxExtract;
    private final Runnable onChanged;
    private long stored;
    private boolean valid = true;

    public EnergyBuffer(long capacity, Runnable onChanged) {
        this(capacity, capacity, capacity, onChanged);
    }

    public EnergyBuffer(long capacity, long maxReceive, long maxExtract, Runnable onChanged) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        setTransferLimits(maxReceive, maxExtract);
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    /** Updates per-operation limits without replacing the balance or its capability views. */
    public void setTransferLimits(long maxReceive, long maxExtract) {
        if (maxReceive < 0 || maxExtract < 0) { throw new IllegalArgumentException("Negative energy transfer limit"); }
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
    }

    private static void checkRequest(long amount, Actionable mode) {
        Objects.requireNonNull(mode, "mode");
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public long storedEnergy() {
        return valid ? stored : 0;
    }

    @Override
    public long receiveEnergy(long amount, Actionable mode) {
        checkRequest(amount, mode);
        long accepted = valid ? Math.min(Math.min(amount, maxReceive), capacity - stored) : 0;
        if (accepted > 0 && mode == Actionable.MODULATE) {
            stored += accepted;
            onChanged.run();
        }
        return accepted;
    }

    @Override
    public long extractEnergy(long amount, Actionable mode) {
        checkRequest(amount, mode);
        long extracted = valid ? Math.min(Math.min(amount, maxExtract), stored) : 0;
        if (extracted > 0 && mode == Actionable.MODULATE) {
            stored -= extracted;
            onChanged.run();
        }
        return extracted;
    }

    /**
     * Loads the owner's saved balance without generating a new mutation event.
     */
    public void restore(long amount) {
        if (amount < 0 || amount > capacity) {
            throw new IllegalArgumentException("Saved energy is outside capacity");
        }
        stored = amount;
    }

    /**
     * Persistence still retains the balance after the host has invalidated its transfer views.
     */
    public long savedEnergy() {
        return stored;
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    public void invalidate() {
        valid = false;
    }
}
