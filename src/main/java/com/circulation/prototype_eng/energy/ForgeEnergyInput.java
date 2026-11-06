package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.Objects;

/** External Forge view of an input hatch; machines consume via EnergyInput. */
public final class ForgeEnergyInput implements IEnergyStorage {
    private final EnergyHatch hatch;

    public ForgeEnergyInput(EnergyHatch hatch) {
        this.hatch = Objects.requireNonNull(hatch, "hatch");
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        if (amount <= 0 || !hatch.isValid()) {
            return 0;
        }
        return (int) hatch.receiveEnergy(amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE);
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return (int) Math.min(Integer.MAX_VALUE, hatch.storedEnergy());
    }

    @Override
    public int getMaxEnergyStored() {
        return (int) Math.min(Integer.MAX_VALUE, hatch.capacity());
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return hatch.isValid();
    }
}
