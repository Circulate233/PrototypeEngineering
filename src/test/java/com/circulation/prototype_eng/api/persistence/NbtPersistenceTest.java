package com.circulation.prototype_eng.api.persistence;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.RenamedAttributeModifier;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtPersistenceTest {
    private static final KeyType TYPE = new KeyType("nbt_persistence");
    private static final AEKeyType RETAINING_TYPE = new AEKeyType(new ResourceLocation("persistence_test", "retaining"),
        RetainingKey.class, new TextComponentString("retaining")) {
        @Override public AEKey readFromPacket(PacketBuffer input) { throw new UnsupportedOperationException(); }
        @Override public AEKey loadKeyFromTag(NBTTagCompound tag) { return new RetainingKey(tag); }
    };

    @Test
    void rejectsMissingFieldsNumericCoercionInvalidBooleansAndNonfiniteNumbers() {
        var tag = new NBTTagCompound();
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireLong(tag, "Quantity"));
        tag.setInteger("Quantity", 4);
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireLong(tag, "Quantity"));
        tag.setFloat("Progress", 0.5f);
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireDouble(tag, "Progress"));
        tag.setDouble("Progress", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireDouble(tag, "Progress"));
        tag.setDouble("Progress", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireDouble(tag, "Progress"));
        tag.setByte("Started", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireBoolean(tag, "Started"));
        tag.setInteger("Started", 1);
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireBoolean(tag, "Started"));
        tag.setBoolean("Started", true);
        assertTrue(StrictNbt.requireBoolean(tag, "Started"));
    }

    @Test
    void rejectsImplicitNamespaceNoncanonicalIdsAndUnknownFields() {
        var tag = new NBTTagCompound();
        for (var id : List.of("", "material", ":material", "example:", "EXAMPLE:material")) {
            tag.setString("Type", id);
            assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireId(tag, "Type"));
        }
        tag.setString("Type", "example:material");
        tag.setString("Unrecognized", "value");
        assertThrows(IllegalArgumentException.class, () -> StrictNbt.requireKeys(tag, "Type"));
    }

    @Test
    void rejectsUnknownNullAndForeignResourceDecodersWithoutReplacingMissingContent() {
        var codec = new ResourceKeyNbt(id -> id.equals(TYPE.getId()) ? TYPE : null);
        var saved = codec.encode(new Key(TYPE, "material"));
        saved.setString("Type", "persistence_test:unknown");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(saved));
        saved.setString("Type", TYPE.getId().toString());
        saved.setString("Data", "wrong type");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(saved));
        saved.setTag("Data", new NBTTagCompound());
        saved.setInteger("Unexpected", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(saved));

        var foreign = new AEKeyType(new ResourceLocation("persistence_test", "foreign"), Key.class,
            new TextComponentString("foreign")) {
            @Override public AEKey readFromPacket(PacketBuffer input) { throw new UnsupportedOperationException(); }
            @Override public AEKey loadKeyFromTag(NBTTagCompound tag) { return new Key(TYPE, "material"); }
        };
        var foreignTag = new NBTTagCompound();
        foreignTag.setString("Type", foreign.getId().toString());
        foreignTag.setTag("Data", new NBTTagCompound());
        assertThrows(IllegalArgumentException.class, () -> new ResourceKeyNbt(_ -> foreign).decode(foreignTag));

        var absent = new AEKeyType(new ResourceLocation("persistence_test", "absent"), Key.class,
            new TextComponentString("absent")) {
            @Override public AEKey readFromPacket(PacketBuffer input) { throw new UnsupportedOperationException(); }
            @Override public AEKey loadKeyFromTag(NBTTagCompound tag) { return null; }
        };
        foreignTag.setString("Type", absent.getId().toString());
        assertThrows(IllegalArgumentException.class, () -> new ResourceKeyNbt(_ -> absent).decode(foreignTag));
    }

    @Test
    void isolatesEncodingAndDecodingEvenWhenAnExternalKeyRetainsMutableData() {
        var codec = new ResourceKeyNbt(_ -> RETAINING_TYPE);
        var data = new NBTTagCompound();
        var nested = new NBTTagCompound();
        nested.setString("Value", "original");
        data.setTag("Nested", nested);
        var original = new RetainingKey(data);
        var saved = codec.encode(original);
        saved.getCompoundTag("Data").getCompoundTag("Nested").setString("Value", "encoded change");
        assertEquals("original", original.toTag().getCompoundTag("Nested").getString("Value"));

        var restored = codec.decode(saved);
        saved.getCompoundTag("Data").getCompoundTag("Nested").setString("Value", "input change");
        assertEquals("encoded change", restored.toTag().getCompoundTag("Nested").getString("Value"));
        restored.toTag().getCompoundTag("Nested").setString("Value", "decoded change");
        assertEquals("input change", saved.getCompoundTag("Data").getCompoundTag("Nested").getString("Value"));
    }

    @Test
    void stackListRoundTripPreservesOrderAndRejectsTheWholeListOnMalformedLaterEntries() {
        var codec = new ResourceKeyNbt(_ -> TYPE);
        var expected = List.of(new GenericStack(new Key(TYPE, "first"), 2), new GenericStack(new Key(TYPE, "second"), 5));
        var saved = codec.writeStacks(expected);
        var restored = codec.readStacks(saved);
        assertEquals(expected, restored);
        saved.getCompoundTagAt(1).setInteger("Quantity", 5);
        assertThrows(IllegalArgumentException.class, () -> codec.readStacks(saved));
        saved.getCompoundTagAt(1).setLong("Quantity", 0);
        assertThrows(IllegalArgumentException.class, () -> codec.readStacks(saved));
        assertEquals(expected, restored);
        assertThrows(IllegalArgumentException.class, () -> codec.writeStacks(List.of(new GenericStack(new Key(TYPE, "first"), -1))));

        var wrongElements = new NBTTagList();
        wrongElements.appendTag(new NBTTagString("not a stack"));
        assertThrows(IllegalArgumentException.class, () -> codec.readStacks(wrongElements));
    }

    @Test
    void restoresExternalModifierBehaviorAndNestedRenamesWithLiveParentAndInsertionOrder() {
        var bindings = new ObjectArrayList<AttributeModifierNbt.Binding>();
        bindings.add(new AttributeModifierNbt.Binding(new ResourceLocation("persistence_test", "square"), squareCodec()));
        var modifierCodec = new AttributeModifierNbt(bindings);
        bindings.clear();
        var codec = new MachineAttributesNbt(modifierCodec);
        var id = new ResourceLocation("persistence_test", "attribute");
        var parent = new MachineAttributes();
        parent.setBase(id, 2);
        parent.addModifier(id, new NumericAttributeModifier("parent", 1, AttributeModifier.Operation.ADDITION));
        var source = new MachineAttributes(parent, 1);
        source.addModifier(id, new NumericAttributeModifier("before", 2, AttributeModifier.Operation.ADDITION, "source"));
        source.addModifier(id, new RenamedAttributeModifier("outer", new RenamedAttributeModifier("inner", new SquareModifier("square"))));
        var saved = codec.encode(source.localSnapshot());
        var restored = new MachineAttributes(parent, 1);
        restored.restoreLocal(codec.decode(saved));
        assertEquals(25, restored.value(id));
        assertEquals(List.of("before", "outer"), List.copyOf(restored.localSnapshot().get(id).modifiers().keySet()));
        assertNull(restored.localSnapshot().get(id).baseOverride());
        parent.setBase(id, 3);
        assertEquals(36, restored.value(id));
        assertEquals(source.value(id), restored.value(id));
        restored.removeModifier(id, "outer");
        assertEquals(6, restored.value(id));
        assertThrows(IllegalArgumentException.class, () -> modifierCodec.encode(
            new NumericAttributeModifier("opaque", 1, AttributeModifier.Operation.ADDITION, new Object())));
    }

    @Test
    void rejectsDuplicateCodecBindingsAmbiguousSupportAndUnknownSavedTypes() {
        var binding = new AttributeModifierNbt.Binding(new ResourceLocation("persistence_test", "square"), squareCodec());
        assertThrows(IllegalArgumentException.class, () -> new AttributeModifierNbt(List.of(binding, binding)));
        var second = new AttributeModifierNbt.Binding(new ResourceLocation("persistence_test", "also_square"), squareCodec());
        var ambiguous = new AttributeModifierNbt(List.of(binding, second));
        assertThrows(IllegalArgumentException.class, () -> ambiguous.encode(new SquareModifier("square")));
        var codec = new AttributeModifierNbt(List.of(binding));
        var saved = codec.encode(new SquareModifier("square"));
        saved.setString("Type", "persistence_test:unknown");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(saved));
    }

    @Test
    void validatesAllAttributeEntriesBeforePublishingAndRejectsDuplicatesOrEmptyEntries() {
        var codec = new MachineAttributesNbt(new AttributeModifierNbt());
        var first = new ResourceLocation("persistence_test", "first");
        var second = new ResourceLocation("persistence_test", "second");
        var source = new MachineAttributes();
        source.setBase(first, 4);
        source.addModifier(second, new NumericAttributeModifier("modifier", 2, AttributeModifier.Operation.ADDITION));
        var target = new MachineAttributes();
        target.setBase(first, 9);
        var saved = codec.encode(source.localSnapshot());
        var entries = saved.getTagList("Attributes", 10);
        var modifiers = entries.getCompoundTagAt(1).getTagList("Modifiers", 10);
        modifiers.getCompoundTagAt(0).getCompoundTag("Data").setString("Operation", "invalid");
        var invalidOperation = saved;
        assertThrows(IllegalArgumentException.class, () -> target.restoreLocal(codec.decode(invalidOperation)));
        assertEquals(9, target.value(first));
        assertEquals(1, target.localSnapshot().size());

        saved = codec.encode(source.localSnapshot());
        entries = saved.getTagList("Attributes", 10);
        entries.appendTag(entries.getCompoundTagAt(0).copy());
        var duplicateAttribute = saved;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateAttribute));
        saved = codec.encode(source.localSnapshot());
        modifiers = saved.getTagList("Attributes", 10).getCompoundTagAt(1).getTagList("Modifiers", 10);
        modifiers.appendTag(modifiers.getCompoundTagAt(0).copy());
        var duplicateModifier = saved;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateModifier));
        saved = codec.encode(source.localSnapshot());
        saved.getTagList("Attributes", 10).getCompoundTagAt(0).removeTag("Base");
        var emptyEntry = saved;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(emptyEntry));
        saved = codec.encode(source.localSnapshot());
        saved.setInteger("Version", 99);
        var incompatible = saved;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(incompatible));
    }

    @Test
    void copiesExternalCodecOutputAndInputBeforeCallingTheImplementation() {
        var shared = new NBTTagCompound();
        var external = new AttributeModifierCodec() {
            @Override public boolean supports(AttributeModifier modifier) { return modifier instanceof SquareModifier; }
            @Override public NBTTagCompound encode(AttributeModifier value) {
                shared.setString("Id", value.id());
                return shared;
            }
            @Override public AttributeModifier decode(NBTTagCompound tag) {
                var id = StrictNbt.requireString(tag, "Id");
                tag.setString("Id", "decoder mutation");
                return new SquareModifier(id);
            }
        };
        var codec = new AttributeModifierNbt(List.of(new AttributeModifierNbt.Binding(new ResourceLocation("persistence_test", "square"), external)));
        var saved = codec.encode(new SquareModifier("original"));
        shared.setString("Id", "encoder mutation");
        assertEquals("original", saved.getCompoundTag("Data").getString("Id"));
        assertEquals(9, codec.decode(saved).apply(0, 3));
        assertEquals("original", saved.getCompoundTag("Data").getString("Id"));
    }

    @Test
    void overlayCodecsPreserveAbsentAndExplicitEmptyFieldsAndRejectMalformedQuantities() {
        var resources = new ResourceKeyNbt(_ -> TYPE);
        var codec = BuiltinOverlayCodecs.resourceIo(resources);
        var patch = new ResourceRequirementPatch(null, List.of(), null, List.of(new GenericStack(new Key(TYPE, "material"), 3)), false);
        var saved = codec.encode(patch);
        var restored = codec.decode(saved);
        assertNull(restored.inputs());
        assertEquals(List.of(), restored.inputsPerTick());
        assertEquals(patch.outputs(), restored.outputs());
        assertEquals(Boolean.FALSE, restored.voidExcessOutputs());
        saved.getTagList("Outputs", 10).getCompoundTagAt(0).setLong("Quantity", 0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(saved));
        assertEquals(3, Objects.requireNonNull(restored.outputs()).getFirst().amount());

        var energy = BuiltinOverlayCodecs.ENERGY_RATES;
        assertEquals(new EnergyRequirementPatch(null, 0L, false), energy.decode(energy.encode(new EnergyRequirementPatch(null, 0L, false))));
        var invalid = energy.encode(new EnergyRequirementPatch(1L, null, null));
        invalid.setInteger("InputsPerTick", 1);
        assertThrows(IllegalArgumentException.class, () -> energy.decode(invalid));
        invalid.setLong("InputsPerTick", -1);
        assertThrows(IllegalArgumentException.class, () -> energy.decode(invalid));
    }

    private static AttributeModifierCodec squareCodec() {
        return new AttributeModifierCodec() {
            @Override public boolean supports(AttributeModifier modifier) { return modifier instanceof SquareModifier; }
            @Override public NBTTagCompound encode(AttributeModifier value) {
                var tag = new NBTTagCompound();
                tag.setString("Id", value.id());
                return tag;
            }
            @Override public AttributeModifier decode(NBTTagCompound tag) {
                StrictNbt.requireKeys(tag, "Id");
                return new SquareModifier(StrictNbt.requireString(tag, "Id"));
            }
        };
    }

    private record SquareModifier(String id) implements AttributeModifier {
        @Override public Operation operation() { return Operation.ADDITION; }
        @Override public double apply(double base, double current) { return current * current; }
    }

    /** Deliberately returns and retains its tag to expose codec aliasing. */
    private static final class RetainingKey extends AEKey {
        private final NBTTagCompound data;

        private RetainingKey(NBTTagCompound data) { this.data = data; }

        @Override public AEKeyType getType() { return RETAINING_TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public Object getPrimaryKey() { return this; }
        @Override public ResourceLocation getId() { return RETAINING_TYPE.getId(); }
        @Override public Object getReadOnlyStack() { return this; }
        @Override protected ITextComponent computeDisplayName() { return new TextComponentString("retaining"); }
        @Override public NBTBase get(String componentId) { return data.getTag(componentId); }
        @Override public void writeToPacket(PacketBuffer output) { throw new UnsupportedOperationException(); }
        @Override public NBTTagCompound toTag() { return data; }
    }
}
