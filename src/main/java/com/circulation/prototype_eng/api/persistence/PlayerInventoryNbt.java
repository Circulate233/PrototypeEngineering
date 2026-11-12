package com.circulation.prototype_eng.api.persistence;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
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
import java.util.Objects;

/**
 * Complete 1.12 player inventory proofs, preserving native item tags and capabilities.
 */
public final class PlayerInventoryNbt {
    public static final int MAX_BYTES = 2_097_152;

    private PlayerInventoryNbt() {
    }

    public static NBTTagCompound capture(InventoryPlayer inventory) {
        if (Objects.requireNonNull(inventory).getSizeInventory() != 41) {
            throw new IllegalStateException("Material proofs require the complete 1.12 player inventory");
        }
        var image = new NBTTagCompound();
        image.setTag("inventory", inventory.writeToNBT(new NBTTagList()));
        image.setInteger("selectedSlot", inventory.currentItem);
        validate(image);
        return image;
    }

    public static NBTTagCompound copy(NBTTagCompound image) {
        encode(image); // Enforce the same bounded serialized proof used by manual materials.
        return image.copy();
    }

    public static byte[] encode(NBTTagCompound image) {
        validate(image);
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
        Objects.requireNonNull(source);
        if (source.length == 0 || source.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid inventory snapshot byte length");
        try {
            var bytes = new ByteArrayInputStream(source);
            NBTTagCompound image;
            try (var input = new DataInputStream(bytes)) {
                image = CompressedStreamTools.read(input);
                if (bytes.available() != 0) throw new IllegalArgumentException("Inventory snapshot has trailing bytes");
            }
            validate(image);
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
        var list = StrictNbt.requireList(image, "inventory", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < list.tagCount(); index++) {
            var stack = list.getCompoundTagAt(index);
            if (Byte.toUnsignedInt(StrictNbt.requireByte(stack, "Slot")) != encoded) continue;
            var result = stack.copy();
            result.removeTag("Slot");
            return result;
        }
        return new NBTTagCompound();
    }

    public static void validate(NBTTagCompound image) {
        StrictNbt.requireKeys(image, "inventory", "selectedSlot");
        int selected = StrictNbt.requireInt(image, "selectedSlot");
        if (selected < 0 || selected >= 9) throw new IllegalArgumentException("Invalid selected inventory slot");
        var list = StrictNbt.requireList(image, "inventory", Constants.NBT.TAG_COMPOUND);
        if (list.tagCount() > 41) throw new IllegalArgumentException("Too many inventory proof slots");
        var occupied = new IntOpenHashSet(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var stack = list.getCompoundTagAt(index);
            int slot = Byte.toUnsignedInt(StrictNbt.requireByte(stack, "Slot"));
            if (!(slot < 36 || slot >= 100 && slot < 104 || slot == 150) || !occupied.add(slot)) {
                throw new IllegalArgumentException("Invalid or duplicate inventory proof slot " + slot);
            }
            StrictNbt.requireId(stack, "id");
            if (StrictNbt.requireByte(stack, "Count") <= 0)
                throw new IllegalArgumentException("Nonpositive inventory proof count");
            StrictNbt.requireShort(stack, "Damage");
            if (stack.hasKey("tag")) StrictNbt.requireCompound(stack, "tag");
            if (stack.hasKey("ForgeCaps")) StrictNbt.requireCompound(stack, "ForgeCaps");
        }
    }
}
