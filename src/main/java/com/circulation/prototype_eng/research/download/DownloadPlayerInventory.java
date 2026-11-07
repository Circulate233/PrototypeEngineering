package com.circulation.prototype_eng.research.download;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Objects;

/**
 * Native inventory fences while a single exact download material receipt awaits settlement.
 */
public final class DownloadPlayerInventory {
    private static final String CURSOR = "PrototypeEngineeringDownloadCursor";
    private static final String NAMESPACE = "prototype_eng";

    private DownloadPlayerInventory() {
    }

    public static boolean pending(EntityPlayer player) {
        return !Objects.requireNonNull(player).world.isRemote && player instanceof EntityPlayerMP actual
            && MinecraftResearchDownloadInventory.hasPending(actual);
    }

    public static boolean protectedStack(EntityPlayer player, ItemStack stack) {
        return pending(player) || RewardItemProtection.isProtected(stack);
    }

    public static void writeCursor(EntityPlayer player, NBTTagCompound compound) {
        if (player.world.isRemote) return;
        if (pending(player)) compound.setTag(CURSOR, player.inventory.getItemStack().writeToNBT(new NBTTagCompound()));
        else compound.removeTag(CURSOR);
    }

    public static void readCursor(EntityPlayer player, NBTTagCompound compound) {
        if (player.world.isRemote || !compound.hasKey(CURSOR)) return;
        if (!pending(player)) throw new IllegalArgumentException("Saved download cursor has no material owner");
        var cursor = new ItemStack(StrictNbt.requireCompound(compound, CURSOR));
        requireAvailable(player.inventory.getItemStack(), cursor);
        player.inventory.setItemStack(cursor);
    }

    /**
     * Move the original inventory before Forge Clone callbacks, preserving the receipt and cursor.
     */
    public static void transfer(EntityPlayerMP original, EntityPlayerMP replacement) {
        if (original == replacement || !pending(original)) return;
        if (!original.getUniqueID().equals(replacement.getUniqueID()))
            throw new IllegalArgumentException("Download material actor changed");
        var source = original.inventory;
        var target = replacement.inventory;
        if (source == target)
            throw new IllegalStateException("Respawn cannot share the original download inventory owner");
        if (source.getSizeInventory() != target.getSizeInventory())
            throw new IllegalStateException("Download inventory size changed");
        for (int slot = 0; slot < source.getSizeInventory(); slot++)
            requireAvailable(target.getStackInSlot(slot), source.getStackInSlot(slot));
        requireAvailable(target.getItemStack(), source.getItemStack());
        var persisted = StrictNbt.requireCompound(original.getEntityData(), EntityPlayer.PERSISTED_NBT_TAG);
        var namespace = StrictNbt.requireCompound(persisted, NAMESPACE);
        var receipts = namespace.getTag(MinecraftResearchDownloadInventory.STORE);
        var replacementData = replacement.getEntityData();
        var replacementPersisted = replacementData.hasKey(EntityPlayer.PERSISTED_NBT_TAG)
            ? StrictNbt.requireCompound(replacementData, EntityPlayer.PERSISTED_NBT_TAG).copy() : new NBTTagCompound();
        var replacementNamespace = replacementPersisted.hasKey(NAMESPACE)
            ? StrictNbt.requireCompound(replacementPersisted, NAMESPACE).copy() : new NBTTagCompound();
        if (replacementNamespace.hasKey(MinecraftResearchDownloadInventory.STORE)
            && !Objects.equals(replacementNamespace.getTag(MinecraftResearchDownloadInventory.STORE), receipts)) {
            throw new IllegalStateException("Respawn has another download material receipt");
        }
        replacementNamespace.setTag(MinecraftResearchDownloadInventory.STORE, Objects.requireNonNull(receipts).copy());
        replacementPersisted.setTag(NAMESPACE, replacementNamespace);
        replacementData.setTag(EntityPlayer.PERSISTED_NBT_TAG, replacementPersisted);
        for (int slot = 0; slot < source.getSizeInventory(); slot++) {
            target.setInventorySlotContents(slot, source.getStackInSlot(slot));
            source.setInventorySlotContents(slot, ItemStack.EMPTY);
        }
        target.currentItem = source.currentItem;
        target.setItemStack(source.getItemStack());
        source.setItemStack(ItemStack.EMPTY);
        var oldPersisted = persisted.copy();
        var oldNamespace = namespace.copy();
        oldNamespace.removeTag(MinecraftResearchDownloadInventory.STORE);
        oldPersisted.setTag(NAMESPACE, oldNamespace);
        original.getEntityData().setTag(EntityPlayer.PERSISTED_NBT_TAG, oldPersisted);
        source.markDirty();
        target.markDirty();
    }

    private static void requireAvailable(ItemStack present, ItemStack original) {
        if (!present.isEmpty() && !ItemStack.areItemStacksEqual(present, original)) {
            throw new IllegalStateException("Download inventory transfer would overwrite another stack");
        }
    }
}
