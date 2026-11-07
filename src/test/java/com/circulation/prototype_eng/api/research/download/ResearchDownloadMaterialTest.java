package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchMaterialReceiptNbt;
import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact native slot proofs and persistence state, with no Minecraft initialization.
 */
class ResearchDownloadMaterialTest {
    private static final UUID PLAYER = new UUID(0, 51);
    private static final UUID OPERATION = new UUID(0, 52);

    private static NBTTagCompound inventory(NBTTagCompound... stacks) {
        var list = new NBTTagList();
        for (var stack : stacks) list.appendTag(stack);
        var result = new NBTTagCompound();
        result.setTag("inventory", list);
        result.setInteger("selectedSlot", 7);
        return result;
    }

    private static NBTTagCompound stack(int slot, int count) {
        var result = new NBTTagCompound();
        result.setString("id", "download_material:encoded_material");
        result.setByte("Count", (byte) count);
        result.setShort("Damage", (short) 7);
        result.setByte("Slot", (byte) PlayerInventoryNbt.encodedSlot(slot));
        var tag = new NBTTagCompound();
        tag.setString("material", "exact");
        result.setTag("tag", tag);
        var caps = new NBTTagCompound();
        caps.setInteger("stored", 42);
        result.setTag("ForgeCaps", caps);
        return result;
    }

    @Test
    void selectedSlotConsumesOneAndLeavesEquivalentStacksAndCapabilitiesUnchanged() {
        var before = inventory(stack(0, 4), stack(5, 3), stack(40, 1));
        var receipt = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, before);
        assertNotNull(receipt);
        assertEquals(1, receipt.material().getByte("Count"));
        assertEquals(2, PlayerInventoryNbt.stack(receipt.inventoryAfter(), 5).getByte("Count"));
        assertEquals(PlayerInventoryNbt.stack(before, 0), PlayerInventoryNbt.stack(receipt.inventoryAfter(), 0));
        assertEquals(PlayerInventoryNbt.stack(before, 40), PlayerInventoryNbt.stack(receipt.inventoryAfter(), 40));
        assertEquals(PlayerInventoryNbt.stack(before, 5).getCompoundTag("ForgeCaps"), receipt.material().getCompoundTag("ForgeCaps"));
        assertEquals(before.getInteger("selectedSlot"), receipt.inventoryAfter().getInteger("selectedSlot"));
        assertEquals(ResearchDownloadMaterial.Status.PREPARED, receipt.status());
    }

    @Test
    void consumingTheLastItemRemovesOnlyItsExactArmorOrOffhandEntry() {
        for (int slot = 0; slot < 41; slot++) {
            var before = inventory(stack(slot, 1));
            var receipt = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, slot, before);
            assertNotNull(receipt);
            assertEquals(0, receipt.inventoryAfter().getTagList("inventory", 10).tagCount());
            assertEquals(1, receipt.material().getByte("Count"));
            assertFalse(receipt.material().hasKey("Slot"));
            assertEquals(receipt, ResearchDownloadMaterial.CODEC.decode(ResearchDownloadMaterial.CODEC.encode(receipt)));
            assertEquals(receipt.inventoryAfter(), PlayerInventoryNbt.decode(PlayerInventoryNbt.encode(receipt.inventoryAfter())));
        }
        assertThrows(IllegalArgumentException.class, () -> PlayerInventoryNbt.encodedSlot(-1));
        assertThrows(IllegalArgumentException.class, () -> PlayerInventoryNbt.encodedSlot(41));
    }

    @Test
    void emptyAndDeliveryProtectedSlotsCannotPrepareMaterials() {
        assertNull(ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, inventory(stack(0, 4))));
        var protectedStack = stack(5, 3);
        protectedStack.getCompoundTag("tag").setString(RewardItemProtection.DELIVERY_TAG, "malformed evidence stays protected");
        assertNull(ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, inventory(protectedStack)));
    }

    @Test
    void callersCannotMutateFrozenNativeMaterialOrEitherInventoryImage() {
        var before = inventory(stack(5, 3));
        var receipt = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, before);
        assertNotNull(receipt);
        var saved = ResearchDownloadMaterial.CODEC.encode(receipt);
        before.getTagList("inventory", 10).getCompoundTagAt(0).getCompoundTag("ForgeCaps").setInteger("stored", 0);
        receipt.material().getCompoundTag("ForgeCaps").setInteger("stored", 1);
        receipt.inventoryAfter().setInteger("selectedSlot", 1);
        assertEquals(saved, ResearchDownloadMaterial.CODEC.encode(receipt));
        assertEquals(42, receipt.material().getCompoundTag("ForgeCaps").getInteger("stored"));
    }

    @Test
    void matchingCandidateAfterInventoryDoesNotChangePreparedReceiptState() {
        var prepared = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, inventory(stack(5, 3)));
        assertNotNull(prepared);
        var restored = ResearchDownloadMaterial.CODEC.decode(ResearchDownloadMaterial.CODEC.encode(prepared));
        assertEquals(ResearchDownloadMaterial.Status.PREPARED, restored.status());
        var committed = prepared.committed();
        assertTrue(prepared.sameOperation(committed));
        assertEquals(ResearchDownloadMaterial.Status.COMMITTED, committed.status());
        assertEquals(committed, ResearchDownloadMaterial.CODEC.decode(ResearchDownloadMaterial.CODEC.encode(committed)));
        assertThrows(IllegalStateException.class, committed::committed);
    }

    @Test
    void mismatchedSlotMaterialCapabilitiesOtherSlotsAndSelectedSlotRejectForgedDeltas() {
        var before = inventory(stack(0, 4), stack(5, 3));
        var original = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, before);
        assertNotNull(original);
        var differentSlot = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 0, before);
        assertNotNull(differentSlot);
        assertFalse(original.sameOperation(differentSlot));
        assertThrows(IllegalArgumentException.class, () -> new ResearchDownloadMaterial(PLAYER, OPERATION, 5,
            original.material(), before, differentSlot.inventoryAfter(), original.status()));
        var changedCaps = original.material();
        changedCaps.getCompoundTag("ForgeCaps").setInteger("stored", 0);
        assertThrows(IllegalArgumentException.class, () -> new ResearchDownloadMaterial(PLAYER, OPERATION, 5,
            changedCaps, before, original.inventoryAfter(), original.status()));
        var changedSelection = original.inventoryAfter();
        changedSelection.setInteger("selectedSlot", 1);
        assertThrows(IllegalArgumentException.class, () -> new ResearchDownloadMaterial(PLAYER, OPERATION, 5,
            original.material(), before, changedSelection, original.status()));
        var changedCount = original.material();
        changedCount.setByte("Count", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> new ResearchDownloadMaterial(PLAYER, OPERATION, 5,
            changedCount, before, original.inventoryAfter(), original.status()));
    }

    @Test
    void strictCodecRejectsUnknownVersionsFieldsMalformedNativeSlotsAndPayloads() {
        var receipt = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, inventory(stack(5, 3)));
        assertNotNull(receipt);
        var unknownVersion = ResearchDownloadMaterial.CODEC.encode(receipt);
        unknownVersion.setInteger("version", 2);
        assertThrows(IllegalArgumentException.class, () -> ResearchDownloadMaterial.CODEC.decode(unknownVersion));
        var unknownField = ResearchDownloadMaterial.CODEC.encode(receipt);
        unknownField.setString("queueEntry", "not a manual session");
        assertThrows(IllegalArgumentException.class, () -> ResearchDownloadMaterial.CODEC.decode(unknownField));
        var duplicateSlots = inventory(stack(5, 3), stack(5, 4));
        assertThrows(IllegalArgumentException.class, () -> ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, duplicateSlots));
        var malformed = stack(5, 3);
        malformed.setString("ForgeCaps", "lost capabilities");
        assertThrows(IllegalArgumentException.class, () -> ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, inventory(malformed)));
        var nonpositive = stack(5, 3);
        nonpositive.setByte("Count", (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, inventory(nonpositive)));
    }

    @Test
    void downloadAndManualMaterialsShareTheSameCompleteInventoryEncoding() {
        var image = inventory(stack(0, 4), stack(36, 1), stack(40, 1));
        assertEquals(image, ManualResearchMaterialReceiptNbt.decodeInventory(PlayerInventoryNbt.encode(image)));
        assertEquals(image, PlayerInventoryNbt.decode(ManualResearchMaterialReceiptNbt.encodeInventory(image)));
    }
}
