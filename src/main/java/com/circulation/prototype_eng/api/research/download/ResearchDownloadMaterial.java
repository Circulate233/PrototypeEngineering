package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Frozen single-slot material operation. A planned after image alone does not prove consumption.
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
            StrictNbt.requireKeys(tag, "version", "playerId", "operationId", "slot", "material", "inventoryBefore", "inventoryAfter", "status");
            if (StrictNbt.requireInt(tag, "version") != 1)
                throw new IllegalArgumentException("Unknown download material version");
            return new ResearchDownloadMaterial(uuid(tag, "playerId"), uuid(tag, "operationId"), StrictNbt.requireInt(tag, "slot"),
                StrictNbt.requireCompound(tag, "material"), StrictNbt.requireCompound(tag, "inventoryBefore"),
                StrictNbt.requireCompound(tag, "inventoryAfter"), Status.valueOf(StrictNbt.requireString(tag, "status")));
        }
    };

    public ResearchDownloadMaterial {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(status);
        PlayerInventoryNbt.encodedSlot(slot);
        material = Objects.requireNonNull(material).copy();
        inventoryBefore = PlayerInventoryNbt.copy(inventoryBefore);
        inventoryAfter = PlayerInventoryNbt.copy(inventoryAfter);
        var selected = PlayerInventoryNbt.stack(inventoryBefore, slot);
        if (selected.isEmpty()) throw new IllegalArgumentException("Download material slot is empty");
        selected.setByte("Count", (byte) 1);
        if (!selected.equals(material) || protectedMaterial(material)) {
            throw new IllegalArgumentException("Download material differs from its exact native slot or is protected");
        }
        if (!subtractOne(inventoryBefore, slot).equals(inventoryAfter)) {
            throw new IllegalArgumentException("Download inventory delta must consume exactly one item in its named slot");
        }
    }

    /**
     * Pure capture from an existing inventory image; empty or delivery-protected slots are unavailable.
     */
    @Nullable
    public static ResearchDownloadMaterial prepare(UUID playerId, UUID operationId, int slot, NBTTagCompound before) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(operationId);
        var image = PlayerInventoryNbt.copy(before);
        var material = PlayerInventoryNbt.stack(image, slot);
        if (material.isEmpty() || protectedMaterial(material)) return null;
        material.setByte("Count", (byte) 1);
        return new ResearchDownloadMaterial(playerId, operationId, slot, material, image, subtractOne(image, slot), Status.PREPARED);
    }

    private static boolean protectedMaterial(NBTTagCompound material) {
        return material.hasKey("tag") && StrictNbt.requireCompound(material, "tag").hasKey(RewardItemProtection.DELIVERY_TAG);
    }

    private static NBTTagCompound subtractOne(NBTTagCompound before, int slot) {
        var after = before.copy();
        int encoded = PlayerInventoryNbt.encodedSlot(slot);
        var entries = StrictNbt.requireList(after, "inventory", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < entries.tagCount(); index++) {
            var stack = entries.getCompoundTagAt(index);
            if (Byte.toUnsignedInt(StrictNbt.requireByte(stack, "Slot")) != encoded) continue;
            int count = StrictNbt.requireByte(stack, "Count");
            if (count == 1) {
                entries.removeTag(index);
                // Native serialization gives an empty list TAG_END; retain that same after proof.
                if (entries.tagCount() == 0) after.setTag("inventory", new NBTTagList());
            }
            else stack.setByte("Count", (byte) (count - 1));
            return after;
        }
        throw new IllegalArgumentException("Download material slot is empty");
    }

    private static UUID uuid(NBTTagCompound tag, String name) {
        var text = StrictNbt.requireString(tag, name);
        var result = UUID.fromString(text);
        if (!result.toString().equals(text)) throw new IllegalArgumentException("Non-canonical download material UUID");
        return result;
    }

    @Override
    public NBTTagCompound material() {
        return material.copy();
    }

    @Override
    public NBTTagCompound inventoryBefore() {
        return inventoryBefore.copy();
    }

    @Override
    public NBTTagCompound inventoryAfter() {
        return inventoryAfter.copy();
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
