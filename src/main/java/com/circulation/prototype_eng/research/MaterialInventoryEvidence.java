package com.circulation.prototype_eng.research;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/**
 * Exact player inventory plus one material receipt store, read from an authoritative saved player.
 */
public final class MaterialInventoryEvidence {
    private MaterialInventoryEvidence() {
    }

    public static void verify(NBTTagCompound saved, NBTTagCompound expectedInventory, String receiptStore,
                              NBTTagCompound expectedReceipts) {
        var image = new NBTTagCompound();
        image.setTag("inventory", saved.getTagList("Inventory", Constants.NBT.TAG_COMPOUND));
        image.setInteger("selectedSlot", saved.getInteger("SelectedItemSlot"));
        if (!image.equals(expectedInventory))
            throw new IllegalStateException("Saved player inventory differs from its material proof");
        var forge = saved.getCompoundTag("ForgeData");
        var persisted = forge.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        var namespace = persisted.getCompoundTag("prototype_eng");
        if (expectedReceipts.isEmpty()) {
            if (namespace.hasKey(receiptStore))
                throw new IllegalStateException("Saved player still contains cleared material receipts");
        } else if (!namespace.getCompoundTag(receiptStore).equals(expectedReceipts)) {
            throw new IllegalStateException("Saved player material receipt differs");
        }
    }
}
