package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragmentNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Strict world operation envelope; the database commit codec owns research-content validation.
 */
public final class ResearchDiskOperationNbt {
    private static final ItemDeliveryFragmentNbt FRAGMENTS = new ItemDeliveryFragmentNbt(ResourceKeyNbt.INSTANCE);

    private ResearchDiskOperationNbt() {
    }

    public static NBTTagCompound encode(ResearchDiskOperation value) {
        return encode(value, FRAGMENTS);
    }

    public static NBTTagCompound encode(ResearchDiskOperation value, ItemDeliveryFragmentNbt fragmentCodec) {
        var tag = new NBTTagCompound();
        tag.setString("nonce", value.intent().nonce().toString());
        tag.setBoolean("machineInventory", value.intent().machineInventory());
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
        tag.setTag("output", value.output());
        tag.setLong("amount", value.amount());
        var fragments = new NBTTagList();
        for (int index = 0; index < value.fragments().size(); index++)
            fragments.appendTag(fragmentCodec.encode(value.fragments().get(index)));
        tag.setTag("fragments", fragments);
        return tag;
    }

    public static ResearchDiskOperation decode(NBTTagCompound tag) {
        return decode(tag, FRAGMENTS);
    }

    public static ResearchDiskOperation decode(NBTTagCompound tag, ItemDeliveryFragmentNbt fragmentCodec) {
        var commit = ResearchDiskCommitNbt.decode(tag.getCompoundTag("commit"));
        var material = ResearchDownloadMaterial.CODEC.decode(tag.getCompoundTag("material"));
        var location = tag.getCompoundTag("location");
        var target = new ResearchDataCenters.Target(commit.before().dataCenterId(), uuid(tag, "owner"),
            new MachineIdentity(new ResourceLocation(location.getString("type")), location.getInteger("dimension"),
                new BlockPos(location.getInteger("x"), location.getInteger("y"), location.getInteger("z"))));
        var savedFragments = tag.getTagList("fragments", 10);
        var fragments = new ObjectArrayList<ItemDeliveryFragment>(savedFragments.tagCount());
        for (int index = 0; index < savedFragments.tagCount(); index++)
            fragments.add(fragmentCodec.decode(savedFragments.getCompoundTagAt(index)));
        var operation = new ResearchDiskOperation(new ResearchDiskOperation.Intent(commit.actor(), uuid(tag, "nonce"), commit.before().dataCenterId(),
            commit.teamId(), material.slot(), tag.getBoolean("machineInventory")), target, commit, material, tag.getLong("createdAt"),
            ResearchDiskOperation.Status.valueOf(tag.getString("status")), tag.getBoolean("databaseArmed"), tag.getCompoundTag("output"),
            tag.getLong("amount"), fragments);
        operation.validateSaved();
        return operation;
    }

    private static UUID uuid(NBTTagCompound tag, String field) {
        var value = tag.getString(field);
        return UUID.fromString(value);
    }
}
