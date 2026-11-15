package com.circulation.prototype_eng.api.persistence;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.RenamedAttributeModifier;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;

/**
 * Frozen explicit modifier codec bindings. Unknown or ambiguous implementations cannot be saved.
 */
public final class AttributeModifierNbt implements NbtCodec<AttributeModifier> {
    public static final AttributeModifierNbt INSTANCE = new AttributeModifierNbt(List.of());
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, AttributeModifierCodec> codecs = new Object2ObjectLinkedOpenHashMap<>();

    public AttributeModifierNbt(Collection<Binding> bindings) {
        register(new Binding(new ResourceLocation(Tags.MOD_ID, "numeric"), new NumericCodec()));
        register(new Binding(new ResourceLocation(Tags.MOD_ID, "renamed"), new RenamedCodec()));
        for (var binding : bindings) {
            register(binding);
        }
    }

    private static AttributeModifier.Operation readOperation(NBTTagCompound tag) {
        var name = tag.getString("Operation");
        for (var operation : AttributeModifier.Operation.VALUES) {
            if (operation.name().equals(name)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("Unknown attribute modifier operation: " + name);
    }

    @Override
    public NBTTagCompound encode(AttributeModifier value) {
        ResourceLocation typeId = null;
        AttributeModifierCodec selected = null;
        for (var entry : codecs.entrySet()) {
            if (entry.getValue().supports(value)) {
                if (selected != null) {
                    throw new IllegalArgumentException("Ambiguous attribute modifier codecs for " + value.id());
                }
                typeId = entry.getKey();
                selected = entry.getValue();
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("No attribute modifier codec for " + value.id());
        }
        var tag = new NBTTagCompound();
        tag.setString("Type", typeId.toString());
        tag.setTag("Data", selected.encode(value));
        return tag;
    }

    @Override
    public AttributeModifier decode(NBTTagCompound tag) {
        var id = new ResourceLocation(tag.getString("Type"));
        var data = tag.getCompoundTag("Data");
        var codec = codecs.get(id);
        if (codec == null) {
            throw new IllegalArgumentException("Unknown attribute modifier codec: " + id);
        }
        return codec.decode(data);
    }

    private void register(Binding binding) {
        if (codecs.putIfAbsent(binding.id(), binding.codec()) != null) {
            throw new IllegalArgumentException("Duplicate attribute modifier codec ID: " + binding.id());
        }
    }

    public record Binding(ResourceLocation id, AttributeModifierCodec codec) {
    }

    private static final class NumericCodec implements AttributeModifierCodec {
        @Override
        public boolean supports(AttributeModifier modifier) {
            return modifier instanceof NumericAttributeModifier numeric && (numeric.adder() == null || numeric.adder() instanceof String);
        }

        @Override
        public NBTTagCompound encode(AttributeModifier value) {
            var numeric = (NumericAttributeModifier) value;
            var tag = new NBTTagCompound();
            tag.setString("Id", numeric.id());
            tag.setString("Operation", numeric.operation().name());
            tag.setDouble("Amount", numeric.amount());
            if (numeric.adder() instanceof String adder) {
                tag.setString("Adder", adder);
            }
            return tag;
        }

        @Override
        public AttributeModifier decode(NBTTagCompound tag) {
            return new NumericAttributeModifier(tag.getString("Id"), tag.getDouble("Amount"),
                readOperation(tag), tag.hasKey("Adder") ? tag.getString("Adder") : null);
        }
    }

    private final class RenamedCodec implements AttributeModifierCodec {
        @Override
        public boolean supports(AttributeModifier modifier) {
            return modifier instanceof RenamedAttributeModifier;
        }

        @Override
        public NBTTagCompound encode(AttributeModifier value) {
            var renamed = (RenamedAttributeModifier) value;
            var tag = new NBTTagCompound();
            tag.setString("Id", renamed.id());
            tag.setTag("Delegate", AttributeModifierNbt.this.encode(renamed.delegate()));
            return tag;
        }

        @Override
        public AttributeModifier decode(NBTTagCompound tag) {
            return new RenamedAttributeModifier(tag.getString("Id"),
                AttributeModifierNbt.this.decode(tag.getCompoundTag("Delegate")));
        }
    }
}
