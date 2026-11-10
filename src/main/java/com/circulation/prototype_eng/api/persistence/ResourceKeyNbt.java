package com.circulation.prototype_eng.api.persistence;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * AE resource persistence with explicit type resolution and no missing-content replacement.
 */
public final class ResourceKeyNbt implements NbtCodec<AEKey> {
    private final Function<ResourceLocation, AEKeyType> resolver;

    public ResourceKeyNbt() {
        this(AEKeyTypes::get);
    }

    /**
     * The resolver must keep each resource type ID bound to the same AEKeyType instance.
     */
    public ResourceKeyNbt(Function<ResourceLocation, AEKeyType> resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public NBTTagCompound encode(AEKey value) {
        Objects.requireNonNull(value, "value");
        var type = Objects.requireNonNull(value.getType(), "resource type");
        var id = StrictNbt.requireId(Objects.requireNonNull(type.getId(), "resource type ID").toString());
        if (resolve(id) != type) {
            throw new IllegalArgumentException("Resource key type does not match its registered type: " + id);
        }
        var tag = new NBTTagCompound();
        tag.setString("Type", id.toString());
        tag.setTag("Data", Objects.requireNonNull(value.toTag(), "resource data").copy());
        return tag;
    }

    @Override
    public AEKey decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Type", "Data");
        var id = StrictNbt.requireId(tag, "Type");
        var data = StrictNbt.requireCompound(tag, "Data");
        var type = resolve(id);
        var key = type.loadKeyFromTag(data.copy());
        if (key == null || key.getType() != type) {
            throw new IllegalArgumentException("Resource data does not decode to the declared type: " + id);
        }
        return key;
    }

    /**
     * Entries use Key (compound) and Quantity (positive long); order and repeated keys are retained.
     */
    public NBTTagList writeStacks(List<GenericStack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        var list = new NBTTagList();
        for (var stack : stacks) {
            Objects.requireNonNull(stack, "stack");
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Persistent stack quantity must be positive");
            }
            var entry = new NBTTagCompound();
            entry.setTag("Key", encode(stack.what()));
            entry.setLong("Quantity", stack.amount());
            list.appendTag(entry);
        }
        return list;
    }

    /**
     * Validates the complete list before returning any decoded result.
     */
    public List<GenericStack> readStacks(NBTTagList list) {
        StrictNbt.requireListElements(list, Constants.NBT.TAG_COMPOUND);
        var stacks = new ObjectArrayList<GenericStack>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Key", "Quantity");
            long quantity = StrictNbt.requireLong(entry, "Quantity");
            if (quantity <= 0) {
                throw new IllegalArgumentException("Persistent stack quantity must be positive at index " + index);
            }
            stacks.add(new GenericStack(decode(StrictNbt.requireCompound(entry, "Key")), quantity));
        }
        return List.copyOf(stacks);
    }

    private AEKeyType resolve(ResourceLocation id) {
        var type = resolver.apply(id);
        if (type == null || !id.equals(type.getId())) {
            throw new IllegalArgumentException("Unknown or mismatched resource type: " + id);
        }
        return type;
    }
}
