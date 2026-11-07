package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.UUID;

/** Strict world operation envelope; the database commit codec owns research-content validation. */
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

    public static ResearchDiskOperation decode(NBTTagCompound tag, ResearchRegistry registry) {
        StrictNbt.requireKeys(tag, "nonce", "owner", "location", "commit", "material", "createdAt", "status", "databaseArmed");
        var commit = ResearchDiskCommitNbt.decode(StrictNbt.requireCompound(tag, "commit"), registry);
        var material = ResearchDownloadMaterial.CODEC.decode(StrictNbt.requireCompound(tag, "material"));
        var location = StrictNbt.requireCompound(tag, "location");
        StrictNbt.requireKeys(location, "type", "dimension", "x", "y", "z");
        var target = new ResearchDataCenters.Target(commit.before().dataCenterId(), uuid(tag, "owner"),
            new MachineIdentity(StrictNbt.requireId(location, "type"), StrictNbt.requireInt(location, "dimension"),
                new BlockPos(StrictNbt.requireInt(location, "x"), StrictNbt.requireInt(location, "y"), StrictNbt.requireInt(location, "z"))));
        return new ResearchDiskOperation(new ResearchDiskOperation.Intent(commit.actor(), uuid(tag, "nonce"), commit.before().dataCenterId(),
            commit.teamId(), material.slot()), target, commit, material, StrictNbt.requireLong(tag, "createdAt"),
            ResearchDiskOperation.Status.valueOf(StrictNbt.requireString(tag, "status")), StrictNbt.requireBoolean(tag, "databaseArmed"));
    }

    private static UUID uuid(NBTTagCompound tag, String field) {
        var value = StrictNbt.requireString(tag, field);
        var result = UUID.fromString(value);
        if (!result.toString().equals(value)) throw new IllegalArgumentException("Non-canonical disk operation UUID");
        return result;
    }
}
