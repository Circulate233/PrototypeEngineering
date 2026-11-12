package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.ControllerInventory;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControllerInventoryTest {
    @Test
    void openSlotsDriveNativeInsertionExtractionAndOrderedTraversalWithoutChangingPhysicalIndices() {
        var inventory = new ControllerInventory(() -> {});
        assertEquals(0, inventory.availableSlots());
        assertEquals(16, inventory.size());
        assertFalse(inventory.isItemValid(3, ItemStack.EMPTY));
        inventory.setAvailableSlots(4);
        assertTrue(inventory.isItemValid(3, ItemStack.EMPTY));
        var open = new IntArrayList(4);
        inventory.forEachAvailable((stack, slot) -> {
            assertTrue(stack.isEmpty());
            open.add(slot);
        });
        assertEquals(IntArrayList.of(0, 1, 2, 3), open);
        inventory.setAvailableSlots(2);
        assertFalse(inventory.isItemValid(3, ItemStack.EMPTY));
        assertSame(ItemStack.EMPTY, inventory.insertItem(3, ItemStack.EMPTY, false));
        assertSame(ItemStack.EMPTY, inventory.extractItem(3, 1, false));
        inventory.setAvailableSlots(4);
        assertTrue(inventory.isAvailable(3));
        assertThrows(IllegalArgumentException.class, () -> inventory.setAvailableSlots(17));
        assertEquals(4, inventory.availableSlots());
    }

    @Test
    void malformedAndOversizedSavedInventoriesAreRejectedBeforeAnyEntryIsRead() {
        var inventory = new ControllerInventory(() -> {});
        var malformed = new NBTTagCompound();
        malformed.setString("Inventory", "invalid");
        assertThrows(IllegalArgumentException.class, () -> inventory.readFromNBT(malformed, "Inventory"));
        var entries = new NBTTagList();
        for (int index = 0; index < 17; index++) entries.appendTag(new NBTTagCompound());
        malformed.setTag("Inventory", entries);
        assertThrows(IllegalArgumentException.class, () -> inventory.readFromNBT(malformed, "Inventory"));
        inventory.readFromNBT(new NBTTagCompound(), "Inventory");
        assertTrue(inventory.isEmpty());
        var saved = new NBTTagCompound();
        inventory.writeToNBT(saved, "Inventory");
        assertFalse(saved.hasKey("Inventory"));
    }
}
