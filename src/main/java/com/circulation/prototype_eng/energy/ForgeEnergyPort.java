package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.storage.HatchMode;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;


/**
 * PM energy hatch's input/output/IO capability routing and configurable per-operation limits.
 */
public final class ForgeEnergyPort implements IEnergyStorage {
    private final @Nullable EnergyHatch input;
    private final @Nullable EnergyHatch output;

    public ForgeEnergyPort(EnergyHatch storage, HatchMode mode) {
        this(mode.hasInput() ? storage : null,
            mode.hasOutput() ? storage : null);
    }

    /**
     * Receives only into the input balance and extracts only from the output balance.
     */
    public ForgeEnergyPort(@Nullable EnergyHatch input, @Nullable EnergyHatch output) {
        if (input == null && output == null) throw new IllegalArgumentException("No energy direction");
        this.input = input;
        this.output = output;
    }

    private static int checkedTransfer(long amount, int requested) {
        if (amount < 0 || amount > requested) {
            throw new IllegalStateException("Energy storage violated its transfer contract");
        }
        return (int) amount;
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        if (input == null || amount <= 0) {
            return 0;
        }
        var context = input.context();
        context.lock();
        try {
            if (!input.isValid()) {
                return 0;
            }
            int limited = (int) Math.min(amount, input.externalReceiveLimit());
            return checkedTransfer(input.receiveEnergy(limited, simulate ? Actionable.SIMULATE : Actionable.MODULATE), limited);
        } finally {
            context.unlock();
        }
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        if (output == null || amount <= 0) {
            return 0;
        }
        var context = output.context();
        context.lock();
        try {
            if (!output.isValid()) {
                return 0;
            }
            int limited = (int) Math.min(amount, output.externalExtractLimit());
            return checkedTransfer(output.extractEnergy(limited, simulate ? Actionable.SIMULATE : Actionable.MODULATE), limited);
        } finally {
            context.unlock();
        }
    }

    @Override
    public int getEnergyStored() {
        return combinedAmount(false);
    }

    @Override
    public int getMaxEnergyStored() {
        return combinedAmount(true);
    }

    // Forge exposes one informational meter; transfers and machine identities stay separate.
    private int combinedAmount(boolean capacity) {
        long amount = input == null ? 0 : Math.min(Integer.MAX_VALUE, capacity ? input.capacity() : input.storedEnergy());
        if (output != null && output != input)
            amount += Math.min(Integer.MAX_VALUE, capacity ? output.capacity() : output.storedEnergy());
        return (int) Math.min(Integer.MAX_VALUE, amount);
    }

    @Override
    public boolean canExtract() {
        if (output == null) return false;
        var context = output.context();
        context.lock();
        try {
            return output.isValid() && output.externalExtractLimit() > 0;
        } finally {
            context.unlock();
        }
    }

    @Override
    public boolean canReceive() {
        if (input == null) return false;
        var context = input.context();
        context.lock();
        try {
            return input.isValid() && input.externalReceiveLimit() > 0;
        } finally {
            context.unlock();
        }
    }
}
