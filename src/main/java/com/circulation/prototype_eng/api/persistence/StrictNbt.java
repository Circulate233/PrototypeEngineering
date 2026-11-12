package com.circulation.prototype_eng.api.persistence;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;

/**
 * Required fields use their exact NBT types; numeric coercion and missing-field defaults are rejected.
 */
public final class StrictNbt {
    private StrictNbt() {
    }

    /**
     * Rejects unknown fields. Required fields are checked by the individual readers.
     */
    public static void requireKeys(NBTTagCompound tag, String... allowed) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(allowed, "allowed");
        var keys = new ObjectOpenHashSet<String>(allowed.length);
        for (var key : allowed) {
            keys.add(Objects.requireNonNull(key, "allowed key"));
        }
        for (var key : tag.getKeySet()) {
            if (!keys.contains(key)) {
                throw new IllegalArgumentException("Unknown NBT field: " + key);
            }
        }
    }

    /**
     * Returns a borrowed input tag. A decoder must copy it before passing it to an external codec.
     */
    public static NBTTagCompound requireCompound(NBTTagCompound tag, String key) {
        return (NBTTagCompound) requireTag(tag, key, Constants.NBT.TAG_COMPOUND);
    }

    /**
     * Returns a borrowed list after validating its declared and actual element types.
     */
    public static NBTTagList requireList(NBTTagCompound tag, String key, int elementType) {
        var list = (NBTTagList) requireTag(tag, key, Constants.NBT.TAG_LIST);
        requireListElements(list, elementType);
        return list;
    }

    public static void requireListElements(NBTTagList list, int elementType) {
        Objects.requireNonNull(list, "list");
        if (elementType < Constants.NBT.TAG_BYTE || elementType > Constants.NBT.TAG_LONG_ARRAY) {
            throw new IllegalArgumentException("Invalid NBT list element type: " + elementType);
        }
        // A newly created empty list has TAG_END until its first element is appended.
        if (list.getTagType() != elementType && !(list.tagCount() == 0 && list.getTagType() == Constants.NBT.TAG_END)) {
            throw new IllegalArgumentException("NBT list requires element type " + elementType);
        }
        for (int index = 0; index < list.tagCount(); index++) {
            if (list.get(index).getId() != elementType) {
                throw new IllegalArgumentException("Invalid NBT list element type at index " + index);
            }
        }
    }

    public static String requireString(NBTTagCompound tag, String key) {
        requireTag(tag, key, Constants.NBT.TAG_STRING);
        return tag.getString(key);
    }

    public static byte requireByte(NBTTagCompound tag, String key) {
        requireTag(tag, key, Constants.NBT.TAG_BYTE);
        return tag.getByte(key);
    }

    @SuppressWarnings("UnusedReturnValue") // ItemStack readers validate the type; external codecs may consume the short.
    public static short requireShort(NBTTagCompound tag, String key) {
        requireTag(tag, key, Constants.NBT.TAG_SHORT);
        return tag.getShort(key);
    }

    public static int requireInt(NBTTagCompound tag, String key) {
        requireTag(tag, key, Constants.NBT.TAG_INT);
        return tag.getInteger(key);
    }

    public static long requireLong(NBTTagCompound tag, String key) {
        requireTag(tag, key, Constants.NBT.TAG_LONG);
        return tag.getLong(key);
    }

    @SuppressWarnings("unused") // External component codecs may store exact finite float fields.
    public static float requireFloat(NBTTagCompound tag, String key) {
        requireTag(tag, key, Constants.NBT.TAG_FLOAT);
        float value = tag.getFloat(key);
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("NBT field must be finite: " + key);
        }
        return value;
    }

    public static double requireDouble(NBTTagCompound tag, String key) {
        requireTag(tag, key, Constants.NBT.TAG_DOUBLE);
        double value = tag.getDouble(key);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("NBT field must be finite: " + key);
        }
        return value;
    }

    public static boolean requireBoolean(NBTTagCompound tag, String key) {
        byte value = requireByte(tag, key);
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("NBT boolean must be byte 0 or 1: " + key);
        }
        return value == 1;
    }

    public static ResourceLocation requireId(NBTTagCompound tag, String key) {
        return requireId(requireString(tag, key));
    }

    public static ResourceLocation requireId(String value) {
        Objects.requireNonNull(value, "id");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("NBT ID requires a nonempty namespace and path: " + value);
        }
        var id = new ResourceLocation(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("NBT ID is not canonical: " + value);
        }
        return id;
    }

    private static NBTBase requireTag(NBTTagCompound tag, String key, int type) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(key, "key");
        var value = tag.getTag(key);
        if (value == null || value.getId() != type) {
            throw new IllegalArgumentException("NBT field '" + key + "' requires type " + type);
        }
        return value;
    }
}
