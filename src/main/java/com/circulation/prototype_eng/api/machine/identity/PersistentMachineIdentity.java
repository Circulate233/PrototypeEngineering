package com.circulation.prototype_eng.api.machine.identity;

import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * Saved machine and real owner UUIDs; neither UUID grants current team or execution permission.
 */
public record PersistentMachineIdentity(UUID machineId, UUID ownerId) {
    private static final int SCHEMA_VERSION = 1;


    /**
     * Validates the entire borrowed compound before returning a new identity, without mutating the input.
     */
    public static PersistentMachineIdentity decode(NBTTagCompound tag) {
        if (tag.getInteger("version") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported persistent machine identity version");
        }
        long machineMost = tag.getLong("machineMost");
        long machineLeast = tag.getLong("machineLeast");
        long ownerMost = tag.getLong("ownerMost");
        long ownerLeast = tag.getLong("ownerLeast");
        return new PersistentMachineIdentity(new UUID(machineMost, machineLeast), new UUID(ownerMost, ownerLeast));
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
}
