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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
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

    private static AttributeModifierCodec squareCodec() {
        return new AttributeModifierCodec() {
            @Override
            public boolean supports(AttributeModifier modifier) {
                return modifier instanceof SquareModifier;
            }

            @Override
            public NBTTagCompound encode(AttributeModifier value) {
                var tag = new NBTTagCompound();
                tag.setString("Id", value.id());
                return tag;
            }

            @Override
            public AttributeModifier decode(NBTTagCompound tag) {
                return new SquareModifier(tag.getString("Id"));
            }
        };
    }

    @Test
    void rejectsUnknownAndNullResourceDecodersWithoutReplacingMissingContent() {
        var codec = new ResourceKeyNbt(id -> id.equals(TYPE.getId()) ? TYPE : null);
        var saved = codec.encode(new Key(TYPE, "material"));
        saved.setString("Type", "persistence_test:unknown");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(saved));
        var absent = new AEKeyType(new ResourceLocation("persistence_test", "absent"), Key.class,
            new TextComponentString("absent")) {
            @Override
            public AEKey readFromPacket(PacketBuffer input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AEKey loadKeyFromTag(NBTTagCompound tag) {
                return null;
            }
        };
        var foreignTag = new NBTTagCompound();
        foreignTag.setString("Type", absent.getId().toString());
        assertThrows(IllegalArgumentException.class, () -> new ResourceKeyNbt(_ -> absent).decode(foreignTag));
    }


    @Test
    void stackListRoundTripPreservesOrder() {
        var codec = new ResourceKeyNbt(_ -> TYPE);
        var expected = List.of(new GenericStack(new Key(TYPE, "first"), 2), new GenericStack(new Key(TYPE, "second"), 5));
        var saved = codec.writeStacks(expected);
        var restored = codec.readStacks(saved);
        assertEquals(expected, restored);
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
    void attributeDecodeFailuresLeaveTargetUnchangedAndRejectDuplicateIds() {
        var codec = MachineAttributesNbt.INSTANCE;
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
        saved.setInteger("Version", 99);
        var incompatible = saved;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(incompatible));
    }

    @Test
    void resolvedBasesRoundTripWithoutReapplyingParentsAndRequireExplicitInheritanceSemantics() {
        var id = new ResourceLocation("persistence_test", "resolved");
        var parent = new MachineAttributes();
        parent.setBase(id, 2);
        parent.addModifier(id, new NumericAttributeModifier("parent", 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
        var source = new MachineAttributes(parent, 1);
        source.addModifier(id, new NumericAttributeModifier("local", 1, AttributeModifier.Operation.ADDITION));
        source.setResolvedBase(id, parent.value(id));
        var codec = MachineAttributesNbt.INSTANCE;
        var saved = codec.encode(source.localSnapshot());
        parent.setBase(id, 10);
        var restored = new MachineAttributes(parent, 1);
        restored.restoreLocal(codec.decode(saved));
        assertEquals(5, restored.value(id));
        assertEquals(source.value(id), restored.value(id));
        var leaf = new MachineAttributes(restored, 1);
        leaf.addModifier(id, new NumericAttributeModifier("leaf", 2, AttributeModifier.Operation.ADDITION));
        assertEquals(7, leaf.value(id));

        var oldVersion = saved.copy();
        oldVersion.setInteger("Version", 1);
        assertThrows(IllegalArgumentException.class, () -> restored.restoreLocal(codec.decode(oldVersion)));
        assertEquals(5, restored.value(id));
        assertTrue(restored.clearBase(id));
        assertEquals(22, restored.value(id));
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
        invalid.setLong("InputsPerTick", -1);
        assertThrows(IllegalArgumentException.class, () -> energy.decode(invalid));
    }

    private record SquareModifier(String id) implements AttributeModifier {
        @Override
        public Operation operation() {
            return Operation.ADDITION;
        }

        @Override
        public double apply(double base, double current) {
            return current * current;
        }
    }

}
