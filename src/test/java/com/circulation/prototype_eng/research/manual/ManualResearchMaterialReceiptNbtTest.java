package com.circulation.prototype_eng.research.manual;

import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchMaterialReceipt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchMaterialReceiptNbt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManualResearchMaterialReceiptNbtTest {
    private static ManualResearchMaterialReceipt prepared(byte[] before) {
        var key = new ManualMaterialKey(new ResourceLocation("manual_receipt_test", "input"), 0);
        var pending = new Object2LongOpenHashMap<ManualMaterialKey>();
        pending.addTo(key, 1);
        var expected = new Object2LongOpenHashMap<ManualMaterialKey>();
        expected.addTo(key, 2);
        var intent = new ManualResearchSession.MaterialIntent(UUID.randomUUID(), UUID.randomUUID(), 1, pending, expected, 1, 0);
        return new ManualResearchMaterialReceipt(UUID.randomUUID(), "team",
            new TeamResearchQueue.Key(new ResourceLocation("manual_receipt_test", "research"), 1, 1), intent, before,
            null, ManualResearchMaterialReceipt.Status.PREPARED, 10, -1, -1);
    }

    private static NBTTagCompound inventory(int mainCount) {
        var root = new NBTTagCompound();
        var list = new NBTTagList();
        list.appendTag(stack(0, mainCount));
        list.appendTag(stack(100, 1));
        list.appendTag(stack(150, 1));
        root.setTag("inventory", list);
        root.setInteger("selectedSlot", 3);
        return root;
    }

    private static NBTTagCompound stack(int slot, int count) {
        var tag = new NBTTagCompound();
        tag.setByte("Slot", (byte) slot);
        tag.setString("id", "manual_receipt_test:material");
        tag.setByte("Count", (byte) count);
        tag.setShort("Damage", (short) 7);
        var payload = new NBTTagCompound();
        payload.setString("business", "payload");
        tag.setTag("tag", payload);
        var caps = new NBTTagCompound();
        caps.setLong("capability", 123);
        tag.setTag("ForgeCaps", caps);
        return tag;
    }

    @Test
    void allReceiptStagesRoundTripCompleteInventoryAndCannotLoseProofThroughAliasedArrays() {
        var beforeImage = inventory(5);
        var before = ManualResearchMaterialReceiptNbt.encodeInventory(beforeImage);
        var afterImage = inventory(4);
        var after = ManualResearchMaterialReceiptNbt.encodeInventory(afterImage);
        var prepared = prepared(before);
        var committed = prepared.committed(after, 11);
        var confirmed = committed.confirmed(12);
        for (var receipt : new ManualResearchMaterialReceipt[]{prepared, committed, confirmed}) {
            var restored = ManualResearchMaterialReceiptNbt.decode(ManualResearchMaterialReceiptNbt.encode(receipt));
            assertEquals(receipt, restored);
            assertEquals(receipt.hashCode(), restored.hashCode());
            assertEquals(beforeImage, ManualResearchMaterialReceiptNbt.decodeInventory(restored.inventoryBefore()));
            if (restored.inventoryAfter() != null) {
                assertEquals(afterImage, ManualResearchMaterialReceiptNbt.decodeInventory(Objects.requireNonNull(restored.inventoryAfter())));
            }
        }
    }

    @Test
    void invalidChronologyTypesAndDifferentIntentCannotMasqueradeAsTheRecordedOperation() {
        var receipt = prepared(ManualResearchMaterialReceiptNbt.encodeInventory(inventory(5)));
        var invalidPrepared = ManualResearchMaterialReceiptNbt.encode(receipt);
        invalidPrepared.setByteArray("inventoryAfter", receipt.inventoryBefore());
        assertThrows(IllegalArgumentException.class, () -> ManualResearchMaterialReceiptNbt.decode(invalidPrepared));
        var unsupportedVersion = ManualResearchMaterialReceiptNbt.encode(receipt);
        unsupportedVersion.setInteger("version", 2);
        assertThrows(IllegalArgumentException.class, () -> ManualResearchMaterialReceiptNbt.decode(unsupportedVersion));
        assertThrows(IllegalArgumentException.class, () -> receipt.committed(receipt.inventoryBefore(), 9));
        var committed = receipt.committed(ManualResearchMaterialReceiptNbt.encodeInventory(inventory(4)), 11);
        assertThrows(IllegalArgumentException.class, () -> committed.confirmed(10));
        var conflictingIntent = new ManualResearchSession.MaterialIntent(receipt.operationId(), receipt.playerId(), 2,
            receipt.intent().pendingWork(), receipt.intent().expectedWork(), 1, 0);
        var different = new ManualResearchMaterialReceipt(receipt.sessionId(), receipt.teamId(), receipt.queueKey(),
            conflictingIntent, receipt.inventoryBefore(), null, ManualResearchMaterialReceipt.Status.PREPARED, 10, -1, -1);
        assertFalse(committed.sameOperation(different), "A sequence mismatch cannot authorize a committed retry");
        var changedBefore = new ManualResearchMaterialReceipt(receipt.sessionId(), receipt.teamId(), receipt.queueKey(),
            receipt.intent(), ManualResearchMaterialReceiptNbt.encodeInventory(inventory(6)), null,
            ManualResearchMaterialReceipt.Status.PREPARED, 10, -1, -1);
        assertFalse(committed.sameOperation(changedBefore), "A retry must retain the original complete before image");
    }

}
