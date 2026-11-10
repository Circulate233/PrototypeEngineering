package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.common.util.FakePlayer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Actual protected stacks remain owned by exactly one player across vanilla respawn and saving.
 */
public final class RewardPlayerInventory {
    public static final String PROTECTED_CURSOR_TAG = "PrototypeEngineeringRewardCursor";

    private RewardPlayerInventory() {
    }

    /**
     * Runs after Entity has read ForgeData but before player loading callbacks can save old data.
     */
    public static void reconcileDeliveryJournal(EntityPlayer player, NBTTagCompound compound) {
        if (player.world.isRemote || !(player instanceof EntityPlayerMP actual) || player instanceof FakePlayer) return;
        var server = Objects.requireNonNull(actual.getServerWorld().getMinecraftServer(), "Reward player loading server");
        if (!server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Reward player loading requires the server thread");
        // Entity has read the file UUID here; EntityPlayer only resets it from the profile later.
        var id = EntityPlayer.getUUID(player.getGameProfile());
        if (!compound.hasUniqueId("UUID") || !id.equals(compound.getUniqueId("UUID"))) {
            throw new IllegalArgumentException("Loaded reward player identity differs from the authenticated profile");
        }
        var loaded = RewardDeliveryJournal.read(player.getEntityData(), id);
        var overworld = Objects.requireNonNull(server.getWorld(0), "Reward player loading overworld");
        if (!(overworld.getSaveHandler() instanceof SaveHandler handler) || handler.getPlayerNBTManager() != handler) {
            // The delivery adapter also rejects this writer; it cannot establish a new proof.
            return;
        }
        var directory = handler.getWorldDirectory().toPath();
        var playerFile = directory.resolve("playerdata").resolve(id + ".dat");
        var hostFile = directory.resolve("level.dat");
        try {
            var saved = Files.exists(playerFile) ? RewardDeliveryPersistence.readPlayerJournal(playerFile, id)
                : RewardDeliveryJournal.read(new NBTTagCompound(), id);
            RewardDeliveryJournal host = null;
            if (server.isSinglePlayer() && player.getName().equals(server.getServerOwner())) {
                host = Files.exists(hostFile) ? RewardDeliveryPersistence.readHostJournal(hostFile, id)
                    : RewardDeliveryJournal.read(new NBTTagCompound(), id);
            }
            var reconciled = RewardDeliveryJournal.reconcile(loaded, saved, host);
            if (!reconciled.snapshot().equals(loaded.snapshot())) reconciled.writeTo(player.getEntityData());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Vanilla writes all 41 inventory slots but omits the carried cursor, including after death.
     */
    public static void writeProtectedCursor(EntityPlayer player, NBTTagCompound compound) {
        if (player.world.isRemote) return;
        var cursor = player.inventory.getItemStack();
        if (RewardItemProtection.isProtected(cursor)) {
            compound.setTag(PROTECTED_CURSOR_TAG, cursor.writeToNBT(new NBTTagCompound()));
        } else {
            compound.removeTag(PROTECTED_CURSOR_TAG);
        }
    }

    public static void readProtectedCursor(EntityPlayer player, NBTTagCompound compound) {
        if (player.world.isRemote) return;
        if (!compound.hasKey(PROTECTED_CURSOR_TAG)) {
            // A full player reload supersedes any previous protected cursor on this same object.
            if (RewardItemProtection.isProtected(player.inventory.getItemStack()))
                player.inventory.setItemStack(ItemStack.EMPTY);
            return;
        }
        if (!compound.hasKey(PROTECTED_CURSOR_TAG, 10))
            throw new IllegalArgumentException("Invalid saved reward cursor");
        var cursor = new ItemStack(compound.getCompoundTag(PROTECTED_CURSOR_TAG));
        if (!RewardItemProtection.isProtected(cursor))
            throw new IllegalArgumentException("Saved reward cursor lost its protection");
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
        if (!oldPlayer.getUniqueID().equals(newPlayer.getUniqueID()))
            throw new IllegalArgumentException("Reward inventory actor changed");
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
