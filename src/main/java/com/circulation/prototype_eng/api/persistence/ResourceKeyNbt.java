package com.circulation.prototype_eng.api.persistence;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.function.Function;

/**
 * AE resource persistence with explicit type resolution and no missing-content replacement.
 */
public final class ResourceKeyNbt implements NbtCodec<AEKey> {
    public static final ResourceKeyNbt INSTANCE = new ResourceKeyNbt(AEKeyTypes::get);
    private final Function<ResourceLocation, AEKeyType> resolver;

    /**
     * Reuse INSTANCE for the AE registry. Create one codec per custom resolver, retaining stable AEKeyType bindings.
     */
    public ResourceKeyNbt(Function<ResourceLocation, AEKeyType> resolver) {
        this.resolver = resolver;
    }

    @Override
    public NBTTagCompound encode(AEKey value) {
        var type = value.getType();
        var id = type.getId();
        var tag = new NBTTagCompound();
        tag.setString("Type", id.toString());
        tag.setTag("Data", value.toTag());
        return tag;
    }

    @Override
    public AEKey decode(NBTTagCompound tag) {
        return decode(new ResourceLocation(tag.getString("Type")), tag.getCompoundTag("Data"));
    }

    /**
     * Type loaders read their own fields without mutating data; enclosing inventory metadata may also be present.
     */
    public AEKey decode(ResourceLocation id, NBTTagCompound data) {
        var type = resolve(id);
        var key = type.loadKeyFromTag(data);
        if (key == null) {
            throw new IllegalArgumentException("Resource data does not decode to the declared type: " + id);
        }
        return key;
    }

    /**
     * Entries use Key (compound) and Quantity (positive long); order and repeated keys are retained.
     */
    public NBTTagList writeStacks(List<GenericStack> stacks) {
        var list = new NBTTagList();
        for (int index1 = 0; index1 < stacks.size(); index1++) {
            var stack = stacks.get(index1);
            var entry = new NBTTagCompound();
            entry.setTag("Key", encode(stack.what()));
            entry.setLong("Quantity", stack.amount());
            list.appendTag(entry);
        }
        return list;
    }

    /**
     * The completed result is transferred to its caller; retained consumers treat it as read-only.
     */
    public List<GenericStack> readStacks(NBTTagList list) {
        var stacks = new ObjectArrayList<GenericStack>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            long quantity = entry.getLong("Quantity");
            stacks.add(new GenericStack(decode(entry.getCompoundTag("Key")), quantity));
        }
        return stacks;
    }

    private AEKeyType resolve(ResourceLocation id) {
        var type = resolver.apply(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown or mismatched resource type: " + id);
        }
        return type;
    }
}
