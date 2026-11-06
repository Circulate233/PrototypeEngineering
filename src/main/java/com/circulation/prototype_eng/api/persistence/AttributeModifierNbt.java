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
import java.util.Objects;

/** Frozen explicit modifier codec bindings. Unknown or ambiguous implementations cannot be saved. */
public final class AttributeModifierNbt implements NbtCodec<AttributeModifier> {
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, AttributeModifierCodec> codecs = new Object2ObjectLinkedOpenHashMap<>();

    public AttributeModifierNbt() {
        this(List.of());
    }

    public AttributeModifierNbt(Collection<Binding> bindings) {
        register(new Binding(new ResourceLocation(Tags.MOD_ID, "numeric"), new NumericCodec()));
        register(new Binding(new ResourceLocation(Tags.MOD_ID, "renamed"), new RenamedCodec()));
        for (var binding : Objects.requireNonNull(bindings, "bindings")) {
            register(Objects.requireNonNull(binding, "binding"));
        }
    }

    @Override
    public NBTTagCompound encode(AttributeModifier value) {
        validate(value);
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
        tag.setString("Type", Objects.requireNonNull(typeId).toString());
        tag.setTag("Data", Objects.requireNonNull(selected.encode(value), "modifier data").copy());
        return tag;
    }

    @Override
    public AttributeModifier decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Type", "Data");
        var id = StrictNbt.requireId(tag, "Type");
        var data = StrictNbt.requireCompound(tag, "Data");
        var codec = codecs.get(id);
        if (codec == null) {
            throw new IllegalArgumentException("Unknown attribute modifier codec: " + id);
        }
        var modifier = codec.decode(data.copy());
        validate(modifier);
        if (!codec.supports(modifier)) {
            throw new IllegalArgumentException("Decoded modifier does not match its codec: " + id);
        }
        return modifier;
    }

    public record Binding(ResourceLocation id, AttributeModifierCodec codec) {
        public Binding {
            StrictNbt.requireId(Objects.requireNonNull(id, "id").toString());
            Objects.requireNonNull(codec, "codec");
        }
    }

    private void register(Binding binding) {
        if (codecs.putIfAbsent(binding.id(), binding.codec()) != null) {
            throw new IllegalArgumentException("Duplicate attribute modifier codec ID: " + binding.id());
        }
    }

    private static void validate(AttributeModifier modifier) {
        if (modifier == null || modifier.id() == null || modifier.id().isBlank() || modifier.operation() == null) {
            throw new IllegalArgumentException("Attribute modifier requires a nonblank ID and operation");
        }
    }

    private static AttributeModifier.Operation readOperation(NBTTagCompound tag) {
        var name = StrictNbt.requireString(tag, "Operation");
        for (var operation : AttributeModifier.Operation.values()) {
            if (operation.name().equals(name)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("Unknown attribute modifier operation: " + name);
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
            StrictNbt.requireKeys(tag, "Id", "Operation", "Amount", "Adder");
            return new NumericAttributeModifier(StrictNbt.requireString(tag, "Id"), StrictNbt.requireDouble(tag, "Amount"),
                readOperation(tag), tag.hasKey("Adder") ? StrictNbt.requireString(tag, "Adder") : null);
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
            StrictNbt.requireKeys(tag, "Id", "Delegate");
            return new RenamedAttributeModifier(StrictNbt.requireString(tag, "Id"),
                AttributeModifierNbt.this.decode(StrictNbt.requireCompound(tag, "Delegate")));
        }
    }
}
