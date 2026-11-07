package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Strict receipt codec and complete InventoryPlayer image validation without game initialization. */
public final class ManualResearchMaterialReceiptNbt {
    public static final int MAX_INVENTORY_BYTES = 2_097_152;
    private ManualResearchMaterialReceiptNbt() { }

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
        if (after != null) { tag.setByteArray("inventoryAfter", after); }
        return tag;
    }

    public static ManualResearchMaterialReceipt decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "version", "sessionId", "teamId", "researchId", "targetLevel", "queueEntryId",
            "intent", "inventoryBefore", "inventoryAfter", "status", "preparedAt", "committedAt", "confirmedAt");
        if (StrictNbt.requireInt(tag, "version") != 1) { throw new IllegalArgumentException("Unsupported material receipt version"); }
        String rawSession = StrictNbt.requireString(tag, "sessionId");
        var session = UUID.fromString(rawSession);
        if (!session.toString().equals(rawSession)) { throw new IllegalArgumentException("Non-canonical material receipt session UUID"); }
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
        validateInventory(image);
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) { CompressedStreamTools.write(image, output); }
            var encoded = bytes.toByteArray();
            if (encoded.length > MAX_INVENTORY_BYTES) { throw new IllegalArgumentException("Inventory snapshot exceeds maximum size"); }
            return encoded;
        } catch (IOException failure) { throw new IllegalStateException("Cannot encode inventory proof", failure); }
    }

    public static NBTTagCompound decodeInventory(byte[] source) {
        Objects.requireNonNull(source);
        if (source.length == 0 || source.length > MAX_INVENTORY_BYTES) { throw new IllegalArgumentException("Invalid inventory snapshot byte length"); }
        try {
            var bytes = new ByteArrayInputStream(source);
            NBTTagCompound image;
            try (var input = new DataInputStream(bytes)) {
                image = CompressedStreamTools.read(input);
                if (bytes.available() != 0) { throw new IllegalArgumentException("Inventory snapshot has trailing bytes"); }
            }
            validateInventory(image);
            return image;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid encoded inventory proof", failure); }
    }

    private static void validateInventory(NBTTagCompound image) {
        StrictNbt.requireKeys(image, "inventory", "selectedSlot");
        int selected = StrictNbt.requireInt(image, "selectedSlot");
        if (selected < 0 || selected >= 9) { throw new IllegalArgumentException("Invalid selected inventory slot"); }
        var list = StrictNbt.requireList(image, "inventory", Constants.NBT.TAG_COMPOUND);
        if (list.tagCount() > 41) { throw new IllegalArgumentException("Too many inventory proof slots"); }
        var occupied = new IntOpenHashSet();
        for (int index = 0; index < list.tagCount(); index++) {
            var stack = list.getCompoundTagAt(index);
            int slot = Byte.toUnsignedInt(StrictNbt.requireByte(stack, "Slot"));
            if (!(slot < 36 || slot >= 100 && slot < 104 || slot == 150) || !occupied.add(slot)) {
                throw new IllegalArgumentException("Invalid or duplicate inventory proof slot " + slot);
            }
            StrictNbt.requireId(stack, "id");
            if (StrictNbt.requireByte(stack, "Count") <= 0) { throw new IllegalArgumentException("Nonpositive inventory proof count"); }
            StrictNbt.requireShort(stack, "Damage");
            if (stack.hasKey("tag")) { StrictNbt.requireCompound(stack, "tag"); }
            if (stack.hasKey("ForgeCaps")) { StrictNbt.requireCompound(stack, "ForgeCaps"); }
        }
    }

    private static byte[] bytes(NBTTagCompound tag, String key) {
        if (!tag.hasKey(key, Constants.NBT.TAG_BYTE_ARRAY)) { throw new IllegalArgumentException("Receipt field must be a byte array: " + key); }
        return tag.getByteArray(key).clone();
    }
}
