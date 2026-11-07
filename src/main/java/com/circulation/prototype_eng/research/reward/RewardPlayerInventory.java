package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Actual protected stacks remain owned by exactly one player across vanilla respawn and saving. */
public final class RewardPlayerInventory {
    public static final String PROTECTED_CURSOR_TAG = "PrototypeEngineeringRewardCursor";

    private RewardPlayerInventory() { }

    /** Vanilla writes all 41 inventory slots but omits the carried cursor, including after death. */
    public static void writeProtectedCursor(EntityPlayer player, NBTTagCompound compound) {
        if (player.world.isRemote) return;
        var cursor = player.inventory.getItemStack();
        if (RewardItemProtection.isProtected(cursor)) {
            compound.setTag(PROTECTED_CURSOR_TAG, cursor.writeToNBT(new NBTTagCompound()));
        } else { compound.removeTag(PROTECTED_CURSOR_TAG); }
    }

    public static void readProtectedCursor(EntityPlayer player, NBTTagCompound compound) {
        if (player.world.isRemote) return;
        if (!compound.hasKey(PROTECTED_CURSOR_TAG)) {
            // A full player reload supersedes any previous protected cursor on this same object.
            if (RewardItemProtection.isProtected(player.inventory.getItemStack())) player.inventory.setItemStack(ItemStack.EMPTY);
            return;
        }
        if (!compound.hasKey(PROTECTED_CURSOR_TAG, 10)) throw new IllegalArgumentException("Invalid saved reward cursor");
        var cursor = new ItemStack(compound.getCompoundTag(PROTECTED_CURSOR_TAG));
        if (!RewardItemProtection.isProtected(cursor)) throw new IllegalArgumentException("Saved reward cursor lost its protection");
        requireAvailable(player.inventory.getItemStack(), cursor);
        player.inventory.setItemStack(cursor);
    }

    /**
     * Runs after vanilla copyInventory and before Forge Clone. copyInventory shares original stack
     * references in keepInventory/keepEverything paths; move those references instead of copying again.
     * Removing them from the old source also prevents Clone consumers from copying them a second time.
     */
    public static void transferProtected(EntityPlayer oldPlayer, EntityPlayer newPlayer) {
        if (oldPlayer == newPlayer || oldPlayer.world.isRemote || newPlayer.world.isRemote) return;
        if (!oldPlayer.getUniqueID().equals(newPlayer.getUniqueID())) throw new IllegalArgumentException("Reward inventory actor changed");
        var source = oldPlayer.inventory;
        var destination = newPlayer.inventory;
        if (source == destination) return;
        if (source.getSizeInventory() != destination.getSizeInventory()) {
            throw new IllegalStateException("Reward player inventory size changed");
        }
        // Check every target before changing either source; a conflict cannot strand a partial transfer.
        for (int slot = 0; slot < source.getSizeInventory(); slot++) {
            var stack = source.getStackInSlot(slot);
            if (RewardItemProtection.isProtected(stack)) requireAvailable(destination.getStackInSlot(slot), stack);
        }
        var cursor = source.getItemStack();
        if (RewardItemProtection.isProtected(cursor)) requireAvailable(destination.getItemStack(), cursor);
        boolean changed = false;
        for (int slot = 0; slot < source.getSizeInventory(); slot++) {
            var stack = source.getStackInSlot(slot);
            if (!RewardItemProtection.isProtected(stack)) continue;
            destination.setInventorySlotContents(slot, stack);
            source.setInventorySlotContents(slot, ItemStack.EMPTY);
            changed = true;
        }
        if (RewardItemProtection.isProtected(cursor)) {
            destination.setItemStack(cursor);
            source.setItemStack(ItemStack.EMPTY);
            changed = true;
        }
        if (changed) {
            source.markDirty();
            destination.markDirty();
        }
    }

    private static void requireAvailable(ItemStack existing, ItemStack transferred) {
        if (!existing.isEmpty() && !ItemStack.areItemStacksEqual(existing, transferred)) {
            throw new IllegalStateException("Reward player transfer would overwrite another stack");
        }
    }
}
