package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Single-slot material operation with borrowed read-only NBT images.
 * Producers must retain each image unchanged for this operation and any committed receipt.
 * A planned after image alone does not prove consumption.
 */
public record ResearchDownloadMaterial(UUID playerId, UUID operationId, int slot, NBTTagCompound material,
                                       NBTTagCompound inventoryBefore, NBTTagCompound inventoryAfter, Status status) {
    public static final NbtCodec<ResearchDownloadMaterial> CODEC = new NbtCodec<>() {
        @Override
        public NBTTagCompound encode(ResearchDownloadMaterial value) {
            var tag = new NBTTagCompound();
            tag.setInteger("version", 1);
            tag.setString("playerId", value.playerId().toString());
            tag.setString("operationId", value.operationId().toString());
            tag.setInteger("slot", value.slot());
            tag.setTag("material", value.material());
            tag.setTag("inventoryBefore", value.inventoryBefore());
            tag.setTag("inventoryAfter", value.inventoryAfter());
            tag.setString("status", value.status().name());
            return tag;
        }

        @Override
        public ResearchDownloadMaterial decode(NBTTagCompound tag) {
            if (tag.getInteger("version") != 1)
                throw new IllegalArgumentException("Unknown download material version");
            return new ResearchDownloadMaterial(uuid(tag, "playerId"), uuid(tag, "operationId"), tag.getInteger("slot"),
                tag.getCompoundTag("material"), tag.getCompoundTag("inventoryBefore"),
                tag.getCompoundTag("inventoryAfter"), Status.valueOf(tag.getString("status")));
        }
    };

    /**
     * Pure capture from an existing inventory image; empty slots are unavailable.
     */
    @Nullable
    public static ResearchDownloadMaterial prepare(UUID playerId, UUID operationId, int slot, NBTTagCompound before) {
        var material = PlayerInventoryNbt.stack(before, slot);
        if (material.isEmpty()) return null;
        material.setByte("Count", (byte) 1);
        return new ResearchDownloadMaterial(playerId, operationId, slot, material, before, subtractOne(before, slot), Status.PREPARED);
    }

    private static NBTTagCompound subtractOne(NBTTagCompound before, int slot) {
        var after = before.copy();
        int encoded = PlayerInventoryNbt.encodedSlot(slot);
        var entries = after.getTagList("inventory", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < entries.tagCount(); index++) {
            var stack = entries.getCompoundTagAt(index);
            if (Byte.toUnsignedInt(stack.getByte("Slot")) != encoded) continue;
            int count = stack.getByte("Count");
            if (count == 1) {
                entries.removeTag(index);
                // Native serialization gives an empty list TAG_END; retain that same after proof.
                if (entries.tagCount() == 0) after.setTag("inventory", new NBTTagList());
            } else stack.setByte("Count", (byte) (count - 1));
            return after;
        }
        throw new IllegalArgumentException("Download material slot is empty");
    }

    private static UUID uuid(NBTTagCompound tag, String name) {
        var text = tag.getString(name);
        return UUID.fromString(text);
    }

    public boolean sameOperation(@Nullable ResearchDownloadMaterial other) {
        return other != null && playerId.equals(other.playerId) && operationId.equals(other.operationId) && slot == other.slot
            && material.equals(other.material) && inventoryBefore.equals(other.inventoryBefore) && inventoryAfter.equals(other.inventoryAfter);
    }

    public ResearchDownloadMaterial committed() {
        if (status != Status.PREPARED) throw new IllegalStateException("Download material is already committed");
        return new ResearchDownloadMaterial(playerId, operationId, slot, material, inventoryBefore, inventoryAfter, Status.COMMITTED);
    }

    public enum Status {PREPARED, COMMITTED}
}
