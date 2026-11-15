package com.circulation.prototype_eng.api.persistence;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Map;

/**
 * Local attribute bases and modifiers; inherited values are not persisted.
 */
public final class MachineAttributesNbt implements NbtCodec<Map<ResourceLocation, MachineAttributes.LocalAttribute>> {
    public static final MachineAttributesNbt INSTANCE = new MachineAttributesNbt(AttributeModifierNbt.INSTANCE);
    private final AttributeModifierNbt modifiers;

    public MachineAttributesNbt(AttributeModifierNbt modifiers) {
        this.modifiers = modifiers;
    }

    @Override
    public NBTTagCompound encode(Map<ResourceLocation, MachineAttributes.LocalAttribute> value) {
        var entries = new NBTTagList();
        for (var entry : value.entrySet()) {
            var id = entry.getKey();
            var state = entry.getValue();
            var tag = new NBTTagCompound();
            tag.setString("Id", id.toString());
            tag.setBoolean("InheritModifiers", state.inheritModifiers());
            if (!Double.isNaN(state.baseOverride())) {
                tag.setDouble("Base", state.baseOverride());
            }
            var modifierList = new NBTTagList();
            for (var modifier : state.modifiers().values()) {
                modifierList.appendTag(modifiers.encode(modifier));
            }
            tag.setTag("Modifiers", modifierList);
            entries.appendTag(tag);
        }
        var tag = new NBTTagCompound();
        tag.setTag("Attributes", entries);
        return tag;
    }

    @Override
    public Map<ResourceLocation, MachineAttributes.LocalAttribute> decode(NBTTagCompound tag) {
        var entries = tag.getTagList("Attributes", Constants.NBT.TAG_COMPOUND);
        var replacement = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAttributes.LocalAttribute>(entries.tagCount());
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            var id = new ResourceLocation(entry.getString("Id"));
            double base = entry.hasKey("Base") ? entry.getDouble("Base") : Double.NaN;
            boolean inheritModifiers = entry.getBoolean("InheritModifiers");
            var modifierList = entry.getTagList("Modifiers", Constants.NBT.TAG_COMPOUND);
            var localModifiers = new Object2ObjectLinkedOpenHashMap<String, AttributeModifier>(modifierList.tagCount());
            for (int modifierIndex = 0; modifierIndex < modifierList.tagCount(); modifierIndex++) {
                var modifier = modifiers.decode(modifierList.getCompoundTagAt(modifierIndex));
                localModifiers.put(modifier.id(), modifier);
            }
            var state = new MachineAttributes.LocalAttribute(base, localModifiers, inheritModifiers);
            replacement.put(id, state);
        }
        return replacement;
    }
}
