package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.storage.HatchMode;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.Objects;
import java.util.function.LongSupplier;

/** PM energy hatch's input/output/IO capability routing and configurable per-operation limits. */
public final class ForgeEnergyPort implements IEnergyStorage {
    private final EnergyHatch storage;
    private final HatchMode mode;
    private final LongSupplier receiveLimit;
    private final LongSupplier extractLimit;

    public ForgeEnergyPort(EnergyHatch storage, HatchMode mode, LongSupplier receiveLimit, LongSupplier extractLimit) {
        this.storage = Objects.requireNonNull(storage);
        this.mode = Objects.requireNonNull(mode);
        this.receiveLimit = Objects.requireNonNull(receiveLimit);
        this.extractLimit = Objects.requireNonNull(extractLimit);
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        if (!canReceive() || amount <= 0) { return 0; }
        return (int) storage.receiveEnergy(Math.min(amount, receiveLimit.getAsLong()), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        if (!canExtract() || amount <= 0) { return 0; }
        return (int) storage.extractEnergy(Math.min(amount, extractLimit.getAsLong()), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    @Override public int getEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, storage.storedEnergy()); }
    @Override public int getMaxEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, storage.capacity()); }
    @Override public boolean canExtract() { return storage.isValid() && mode.hasOutput() && extractLimit.getAsLong() > 0; }
    @Override public boolean canReceive() { return storage.isValid() && mode.hasInput() && receiveLimit.getAsLong() > 0; }
}
