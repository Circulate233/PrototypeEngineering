package com.circulation.prototype_eng.api.machine.identity;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Objects;
import java.util.UUID;

/** Saved machine and real owner UUIDs; neither UUID grants current team or execution permission. */
public record PersistentMachineIdentity(UUID machineId, UUID ownerId) {
    private static final int SCHEMA_VERSION = 1;

    public PersistentMachineIdentity {
        Objects.requireNonNull(machineId, "machineId");
        Objects.requireNonNull(ownerId, "ownerId");
    }

    public NBTTagCompound encode() {
        var tag = new NBTTagCompound();
        tag.setInteger("version", SCHEMA_VERSION);
        tag.setLong("machineMost", machineId.getMostSignificantBits());
        tag.setLong("machineLeast", machineId.getLeastSignificantBits());
        tag.setLong("ownerMost", ownerId.getMostSignificantBits());
        tag.setLong("ownerLeast", ownerId.getLeastSignificantBits());
        return tag;
    }

    /** Validates the entire borrowed compound before returning a new identity, without mutating the input. */
    public static PersistentMachineIdentity decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "version", "machineMost", "machineLeast", "ownerMost", "ownerLeast");
        if (StrictNbt.requireInt(tag, "version") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported persistent machine identity version");
        }
        long machineMost = StrictNbt.requireLong(tag, "machineMost");
        long machineLeast = StrictNbt.requireLong(tag, "machineLeast");
        long ownerMost = StrictNbt.requireLong(tag, "ownerMost");
        long ownerLeast = StrictNbt.requireLong(tag, "ownerLeast");
        return new PersistentMachineIdentity(new UUID(machineMost, machineLeast), new UUID(ownerMost, ownerLeast));
    }
}
