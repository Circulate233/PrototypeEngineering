package com.circulation.prototype_eng.api.persistence;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Complete 1.12 player inventory proofs, preserving native item tags and capabilities.
 */
public final class PlayerInventoryNbt {
    public static final int MAX_BYTES = 2_097_152;

    private PlayerInventoryNbt() {
    }

    public static NBTTagCompound capture(InventoryPlayer inventory) {
        var image = new NBTTagCompound();
        image.setTag("inventory", inventory.writeToNBT(new NBTTagList()));
        image.setInteger("selectedSlot", inventory.currentItem);
        return image;
    }

    public static byte[] encode(NBTTagCompound image) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                CompressedStreamTools.write(image, output);
            }
            var encoded = bytes.toByteArray();
            if (encoded.length > MAX_BYTES)
                throw new IllegalArgumentException("Inventory snapshot exceeds maximum size");
            return encoded;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode inventory proof", failure);
        }
    }

    public static NBTTagCompound decode(byte[] source) {
        if (source.length == 0 || source.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid inventory snapshot byte length");
        try {
            var bytes = new ByteArrayInputStream(source);
            NBTTagCompound image;
            try (var input = new DataInputStream(bytes)) {
                image = CompressedStreamTools.read(input);
                if (bytes.available() != 0) throw new IllegalArgumentException("Inventory snapshot has trailing bytes");
            }
            return image;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid encoded inventory proof", failure);
        }
    }

    public static int encodedSlot(int slot) {
        if (slot < 0 || slot >= 41) throw new IllegalArgumentException("Invalid player inventory slot");
        return slot < 36 ? slot : slot < 40 ? 100 + slot - 36 : 150;
    }

    /**
     * Empty compound denotes an empty slot; a nonempty result is a complete native stack without Slot.
     */
    public static NBTTagCompound stack(NBTTagCompound image, int slot) {
        int encoded = encodedSlot(slot);
        var list = image.getTagList("inventory", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < list.tagCount(); index++) {
            var stack = list.getCompoundTagAt(index);
            if (Byte.toUnsignedInt(stack.getByte("Slot")) != encoded) continue;
            var result = new NBTTagCompound();
            for (var key : stack.getKeySet()) if (!key.equals("Slot")) result.setTag(key, stack.getTag(key));
            return result;
        }
        return new NBTTagCompound();
    }

}
