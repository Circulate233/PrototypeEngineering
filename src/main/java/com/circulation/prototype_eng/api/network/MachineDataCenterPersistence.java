package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.function.BooleanSupplier;

/**
 * Real persistence adapter for a machine database component. External hosts supply their own writer
 * and authoritative readback without inheriting a built-in tile. Dirty callbacks cannot implement this.
 */
@FunctionalInterface
public interface MachineDataCenterPersistence {
    /**
     * Save and read back one exact machine/ownership/storage binding and this complete component payload.
     * Return only when the real recovery source has settled and matches; recheck physicalCurrent through
     * the barrier. Failure retains the published operation evidence and must propagate.
     */
    void persistAndVerify(MachineInstance owner, ResourceLocation storageType, ResourceLocation ownershipType,
                          NBTTagCompound expected, BooleanSupplier physicalCurrent);
}
