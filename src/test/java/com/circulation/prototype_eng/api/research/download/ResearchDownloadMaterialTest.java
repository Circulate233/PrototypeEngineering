package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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


}
