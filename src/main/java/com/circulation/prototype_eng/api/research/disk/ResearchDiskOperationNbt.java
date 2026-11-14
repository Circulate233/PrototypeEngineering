package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.UUID;

/**
 * Strict world operation envelope; the database commit codec owns research-content validation.
 */
public final class ResearchDiskOperationNbt {
    private ResearchDiskOperationNbt() {
    }

    public static NBTTagCompound encode(ResearchDiskOperation value) {
        Objects.requireNonNull(value);
        var tag = new NBTTagCompound();
        tag.setString("nonce", value.intent().nonce().toString());
        tag.setString("owner", value.target().ownerId().toString());
        var location = value.target().location();
        var savedLocation = new NBTTagCompound();
        savedLocation.setString("type", location.machineType().toString());
        savedLocation.setInteger("dimension", location.dimension());
        savedLocation.setInteger("x", location.controllerPos().getX());
        savedLocation.setInteger("y", location.controllerPos().getY());
        savedLocation.setInteger("z", location.controllerPos().getZ());
        tag.setTag("location", savedLocation);
        tag.setTag("commit", ResearchDiskCommitNbt.encode(value.commit()));
        tag.setTag("material", ResearchDownloadMaterial.CODEC.encode(value.material()));
        tag.setLong("createdAt", value.createdAt());
        tag.setString("status", value.status().name());
        tag.setBoolean("databaseArmed", value.databaseArmed());
        return tag;
    }

    public static ResearchDiskOperation decode(NBTTagCompound tag) {
        var commit = ResearchDiskCommitNbt.decode(tag.getCompoundTag("commit"));
        var material = ResearchDownloadMaterial.CODEC.decode(tag.getCompoundTag("material"));
        var location = tag.getCompoundTag("location");
        var target = new ResearchDataCenters.Target(commit.before().dataCenterId(), uuid(tag, "owner"),
            new MachineIdentity(new ResourceLocation(location.getString("type")), location.getInteger("dimension"),
                new BlockPos(location.getInteger("x"), location.getInteger("y"), location.getInteger("z"))));
        return new ResearchDiskOperation(new ResearchDiskOperation.Intent(commit.actor(), uuid(tag, "nonce"), commit.before().dataCenterId(),
            commit.teamId(), material.slot()), target, commit, material, tag.getLong("createdAt"),
            ResearchDiskOperation.Status.valueOf(tag.getString("status")), tag.getBoolean("databaseArmed"));
    }

    private static UUID uuid(NBTTagCompound tag, String field) {
        var value = tag.getString(field);
        return UUID.fromString(value);
    }
}
