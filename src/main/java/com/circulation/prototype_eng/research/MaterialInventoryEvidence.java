package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
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
        image.setTag("inventory", StrictNbt.requireList(saved, "Inventory", Constants.NBT.TAG_COMPOUND).copy());
        image.setInteger("selectedSlot", StrictNbt.requireInt(saved, "SelectedItemSlot"));
        if (!image.equals(expectedInventory))
            throw new IllegalStateException("Saved player inventory differs from its material proof");
        var forge = StrictNbt.requireCompound(saved, "ForgeData");
        var persisted = StrictNbt.requireCompound(forge, EntityPlayer.PERSISTED_NBT_TAG);
        var namespace = StrictNbt.requireCompound(persisted, "prototype_eng");
        if (expectedReceipts.isEmpty()) {
            if (namespace.hasKey(receiptStore))
                throw new IllegalStateException("Saved player still contains cleared material receipts");
        } else if (!StrictNbt.requireCompound(namespace, receiptStore).equals(expectedReceipts)) {
            throw new IllegalStateException("Saved player material receipt differs");
        }
    }
}
