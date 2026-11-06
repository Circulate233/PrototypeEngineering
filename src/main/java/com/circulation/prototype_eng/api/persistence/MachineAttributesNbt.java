package com.circulation.prototype_eng.api.persistence;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Saves only ordered local attributes; callers restore the decoded map after complete validation. */
public final class MachineAttributesNbt implements NbtCodec<Map<ResourceLocation, MachineAttributes.LocalAttribute>> {
    private final AttributeModifierNbt modifiers;

    public MachineAttributesNbt(AttributeModifierNbt modifiers) {
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
    }

    @Override
    public NBTTagCompound encode(Map<ResourceLocation, MachineAttributes.LocalAttribute> value) {
        Objects.requireNonNull(value, "value");
        var entries = new NBTTagList();
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        for (var entry : value.entrySet()) {
            var id = StrictNbt.requireId(Objects.requireNonNull(entry.getKey(), "attribute ID").toString());
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate local attribute ID: " + id);
            }
            var state = Objects.requireNonNull(entry.getValue(), "local attribute");
            var checked = new MachineAttributes.LocalAttribute(state.baseOverride(), state.modifiers());
            var tag = new NBTTagCompound();
            tag.setString("Id", id.toString());
            if (checked.baseOverride() != null) {
                tag.setDouble("Base", checked.baseOverride());
            }
            var modifierList = new NBTTagList();
            for (var modifier : checked.modifiers().values()) {
                modifierList.appendTag(modifiers.encode(modifier));
            }
            tag.setTag("Modifiers", modifierList);
            entries.appendTag(tag);
        }
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setTag("Attributes", entries);
        return tag;
    }

    @Override
    public Map<ResourceLocation, MachineAttributes.LocalAttribute> decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "Attributes");
        if (StrictNbt.requireInt(tag, "Version") != 1) {
            throw new IllegalArgumentException("Unsupported local attribute NBT version");
        }
        var entries = StrictNbt.requireList(tag, "Attributes", Constants.NBT.TAG_COMPOUND);
        var replacement = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAttributes.LocalAttribute>();
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Id", "Base", "Modifiers");
            var id = StrictNbt.requireId(entry, "Id");
            Double base = entry.hasKey("Base") ? StrictNbt.requireDouble(entry, "Base") : null;
            var modifierList = StrictNbt.requireList(entry, "Modifiers", Constants.NBT.TAG_COMPOUND);
            var localModifiers = new Object2ObjectLinkedOpenHashMap<String, AttributeModifier>();
            for (int modifierIndex = 0; modifierIndex < modifierList.tagCount(); modifierIndex++) {
                var modifier = modifiers.decode(modifierList.getCompoundTagAt(modifierIndex));
                if (localModifiers.putIfAbsent(modifier.id(), modifier) != null) {
                    throw new IllegalArgumentException("Duplicate local attribute modifier ID: " + modifier.id());
                }
            }
            var state = new MachineAttributes.LocalAttribute(base, localModifiers);
            if (replacement.putIfAbsent(id, state) != null) {
                throw new IllegalArgumentException("Duplicate local attribute ID: " + id);
            }
        }
        return Collections.unmodifiableMap(replacement);
    }
}
