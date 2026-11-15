package com.circulation.prototype_eng.api.research.delivery;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.UUID;

/**
 * Copies complete native templates into tagged leaves without reconstructing them from AEKey.
 */
public final class ItemDeliveryTemplates {
    public static final String DELIVERY_TAG = "PrototypeEngineeringRewardDelivery";

    private ItemDeliveryTemplates() {
    }

    public static NBTTagCompound copy(NBTTagCompound template) {
        var result = template.copy();
        if (result.getByte("Count") < 1)
            throw new IllegalArgumentException("Empty item delivery template");
        if (result.hasKey("tag") && result.getCompoundTag("tag").hasKey(DELIVERY_TAG)) {
            throw new IllegalArgumentException("Business item contains reserved delivery tag");
        }
        normalizeEmptyLists(result);
        return result;
    }

    /**
     * Native NBT writes empty lists as TAG_END, including lists nested inside business tags or caps.
     */
    private static NBTBase normalizeEmptyLists(NBTBase value) {
        if (value instanceof NBTTagCompound compound) {
            for (var key : compound.getKeySet()) {
                var child = compound.getTag(key);
                var normalized = normalizeEmptyLists(child);
                if (normalized != child) compound.setTag(key, normalized);
            }
        } else if (value instanceof NBTTagList list) {
            if (list.tagCount() == 0) return new NBTTagList();
            for (int index = 0; index < list.tagCount(); index++) {
                var child = list.get(index);
                var normalized = normalizeEmptyLists(child);
                if (normalized != child) list.set(index, normalized);
            }
        }
        return value;
    }

    public static NBTTagCompound taggedLeaf(NBTTagCompound template, int count, ItemDeliveryKey key, UUID id) {
        if (count < 1 || count > Byte.MAX_VALUE) throw new IllegalArgumentException("Invalid item delivery leaf count");
        var result = copy(template);
        result.setByte("Count", (byte) count);
        var root = result.hasKey("tag") ? result.getCompoundTag("tag") : new NBTTagCompound();
        root.setTag(DELIVERY_TAG, ItemDeliveryKeyNbt.identity(key, id));
        result.setTag("tag", root);
        return result;
    }
}
