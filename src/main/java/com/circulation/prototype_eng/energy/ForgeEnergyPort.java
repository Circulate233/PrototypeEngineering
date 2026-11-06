package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.storage.HatchMode;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.Objects;

/** PM energy hatch's input/output/IO capability routing and configurable per-operation limits. */
public final class ForgeEnergyPort implements IEnergyStorage {
    private final EnergyHatch storage;
    private final HatchMode mode;

    public ForgeEnergyPort(EnergyHatch storage, HatchMode mode) {
        this.storage = Objects.requireNonNull(storage);
        this.mode = Objects.requireNonNull(mode);
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        if (!mode.hasInput() || amount <= 0) { return 0; }
        var context = storage.context();
        context.lock();
        try {
            if (!storage.isValid()) { return 0; }
            return checkedTransfer(storage.receiveEnergy(amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE), amount);
        } finally {
            context.unlock();
        }
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        if (!mode.hasOutput() || amount <= 0) { return 0; }
        var context = storage.context();
        context.lock();
        try {
            if (!storage.isValid()) { return 0; }
            return checkedTransfer(storage.extractEnergy(amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE), amount);
        } finally {
            context.unlock();
        }
    }

    private static int checkedTransfer(long amount, int requested) {
        if (amount < 0 || amount > requested) {
            throw new IllegalStateException("Energy storage violated its transfer contract");
        }
        return (int) amount;
    }

    @Override public int getEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, storage.storedEnergy()); }
    @Override public int getMaxEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, storage.capacity()); }
    @Override public boolean canExtract() {
        var context = storage.context();
        context.lock();
        try {
            return storage.isValid() && mode.hasOutput() && storage.energySnapshot().maxExtract() > 0;
        } finally {
            context.unlock();
        }
    }
    @Override public boolean canReceive() {
        var context = storage.context();
        context.lock();
        try {
            return storage.isValid() && mode.hasInput() && storage.energySnapshot().maxReceive() > 0;
        } finally {
            context.unlock();
        }
    }
}
