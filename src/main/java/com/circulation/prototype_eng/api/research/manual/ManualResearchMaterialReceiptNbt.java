package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Receipt codec using complete InventoryPlayer images.
 */
public final class ManualResearchMaterialReceiptNbt {
    private ManualResearchMaterialReceiptNbt() {
    }

    public static NBTTagCompound encode(ManualResearchMaterialReceipt receipt) {
        Objects.requireNonNull(receipt);
        var tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        tag.setString("sessionId", receipt.sessionId().toString());
        tag.setString("teamId", receipt.teamId());
        tag.setString("researchId", receipt.queueKey().researchId().toString());
        tag.setInteger("targetLevel", receipt.queueKey().targetLevel());
        tag.setLong("queueEntryId", receipt.queueKey().queueEntryId());
        tag.setTag("intent", ManualResearchSessionNbt.encodeMaterialIntent(receipt.intent()));
        tag.setByteArray("inventoryBefore", receipt.inventoryBefore());
        tag.setString("status", receipt.status().name());
        tag.setLong("preparedAt", receipt.preparedAt());
        tag.setLong("committedAt", receipt.committedAt());
        tag.setLong("confirmedAt", receipt.confirmedAt());
        var after = receipt.inventoryAfter();
        if (after != null) {
            tag.setByteArray("inventoryAfter", after);
        }
        return tag;
    }

    public static ManualResearchMaterialReceipt decode(NBTTagCompound tag) {
        if (tag.getInteger("version") != 1) {
            throw new IllegalArgumentException("Unsupported material receipt version");
        }
        String rawSession = tag.getString("sessionId");
        var session = UUID.fromString(rawSession);
        return new ManualResearchMaterialReceipt(session, tag.getString("teamId"),
            new TeamResearchQueue.Key(new ResourceLocation(tag.getString("researchId")), tag.getInteger("targetLevel"),
                tag.getLong("queueEntryId")),
            ManualResearchSessionNbt.decodeMaterialIntent(tag.getCompoundTag("intent")),
            tag.getByteArray("inventoryBefore"), tag.hasKey("inventoryAfter") ? tag.getByteArray("inventoryAfter") : null,
            ManualResearchMaterialReceipt.Status.valueOf(tag.getString("status")),
            tag.getLong("preparedAt"), tag.getLong("committedAt"), tag.getLong("confirmedAt"));
    }

    public static byte[] encodeInventory(NBTTagCompound image) {
        return PlayerInventoryNbt.encode(image);
    }

    public static NBTTagCompound decodeInventory(byte[] source) {
        return PlayerInventoryNbt.decode(source);
    }

}
