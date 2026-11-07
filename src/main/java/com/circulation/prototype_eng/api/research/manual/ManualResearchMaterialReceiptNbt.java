package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;
import java.util.UUID;

/**
 * Strict receipt codec and complete InventoryPlayer image validation without game initialization.
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
        StrictNbt.requireKeys(tag, "version", "sessionId", "teamId", "researchId", "targetLevel", "queueEntryId",
            "intent", "inventoryBefore", "inventoryAfter", "status", "preparedAt", "committedAt", "confirmedAt");
        if (StrictNbt.requireInt(tag, "version") != 1) {
            throw new IllegalArgumentException("Unsupported material receipt version");
        }
        String rawSession = StrictNbt.requireString(tag, "sessionId");
        var session = UUID.fromString(rawSession);
        if (!session.toString().equals(rawSession)) {
            throw new IllegalArgumentException("Non-canonical material receipt session UUID");
        }
        return new ManualResearchMaterialReceipt(session, StrictNbt.requireString(tag, "teamId"),
            new TeamResearchQueue.Key(StrictNbt.requireId(tag, "researchId"), StrictNbt.requireInt(tag, "targetLevel"),
                StrictNbt.requireLong(tag, "queueEntryId")),
            ManualResearchSessionNbt.decodeMaterialIntent(StrictNbt.requireCompound(tag, "intent")),
            bytes(tag, "inventoryBefore"), tag.hasKey("inventoryAfter") ? bytes(tag, "inventoryAfter") : null,
            ManualResearchMaterialReceipt.Status.valueOf(StrictNbt.requireString(tag, "status")),
            StrictNbt.requireLong(tag, "preparedAt"), StrictNbt.requireLong(tag, "committedAt"), StrictNbt.requireLong(tag, "confirmedAt"));
    }

    public static byte[] copyInventory(byte[] source) {
        var copy = Objects.requireNonNull(source).clone();
        decodeInventory(copy);
        return copy;
    }

    public static byte[] encodeInventory(NBTTagCompound image) {
        return PlayerInventoryNbt.encode(image);
    }

    public static NBTTagCompound decodeInventory(byte[] source) {
        return PlayerInventoryNbt.decode(source);
    }

    private static byte[] bytes(NBTTagCompound tag, String key) {
        if (!tag.hasKey(key, Constants.NBT.TAG_BYTE_ARRAY)) {
            throw new IllegalArgumentException("Receipt field must be a byte array: " + key);
        }
        return tag.getByteArray(key).clone();
    }
}
