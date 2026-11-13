package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.circulation.prototype_eng.api.machine.attribute.AttributeModifier.Operation.ADDITION;
import static com.circulation.prototype_eng.api.machine.attribute.AttributeModifier.Operation.MULTIPLY_BASE;
import static com.circulation.prototype_eng.api.machine.attribute.AttributeModifier.Operation.MULTIPLY_TOTAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineAttributesTest {
    private static final ResourceLocation SPEED = new ResourceLocation("attribute_test", "speed");
    private static final ResourceLocation OTHER = new ResourceLocation("attribute_test", "other");

    @Test
    void phasesUseOriginalBaseAndKeepCustomFunctionsInRegistrationOrder() {
        var attributes = new MachineAttributes();
        attributes.setBase(SPEED, 10);
        attributes.addModifier(SPEED, new NumericAttributeModifier("total", 0.5, MULTIPLY_TOTAL));
        attributes.addModifier(SPEED, new NumericAttributeModifier("base", 0.2, MULTIPLY_BASE));
        attributes.addModifier(SPEED, new NumericAttributeModifier("add", 3, ADDITION));
        assertEquals(22.5, attributes.value(SPEED));

        attributes.removeModifier(SPEED, "add");
        attributes.addModifier(SPEED, new DoubleCurrent("double"));
        attributes.addModifier(SPEED, new NumericAttributeModifier("add", 3, ADDITION));
        assertEquals(37.5, attributes.value(SPEED));
        attributes.removeModifier(SPEED, "double");
        attributes.addModifier(SPEED, new DoubleCurrent("double"));
        assertEquals(42, attributes.value(SPEED));
    }

    @Test
    void overlayShadowsLocalIdsAndRevealsLiveParentStateAfterRemoval() {
        var parent = new MachineAttributes();
        parent.setBase(new ResourceLocation("attribute_test", "speed"), 10);
        parent.addModifier(SPEED, new NumericAttributeModifier("add", 2, ADDITION));
        parent.addModifier(SPEED, new NumericAttributeModifier("total", 1, MULTIPLY_TOTAL));
        var child = new MachineAttributes(parent, 1);
        assertTrue(child.addModifier(SPEED, new NumericAttributeModifier("add", 7, ADDITION)));
        assertFalse(child.addModifier(SPEED, new NumericAttributeModifier("add", 99, ADDITION)));
        assertEquals(34, child.value(SPEED));
        assertEquals(24, parent.value(SPEED));
        assertTrue(child.removeModifier(SPEED, "add"));
        assertEquals(24, child.value(SPEED));
        assertFalse(child.removeModifier(SPEED, "add"));

        parent.setBase(SPEED, 20);
        assertEquals(44, child.value(SPEED));
        child.setBase(SPEED, 8);
        assertEquals(20, child.value(SPEED));
        parent.setBase(SPEED, 30);
        assertEquals(20, child.value(SPEED));
        assertTrue(child.clearBase(SPEED));
        assertEquals(64, child.value(SPEED));
        assertFalse(child.clearBase(SPEED));
    }

    @Test
    void resolvedParentValueIsAppliedOnceWhileLocalModifiersAndSnapshotsRemainActive() {
        var parent = new MachineAttributes();
        parent.setBase(SPEED, 2);
        parent.addModifier(SPEED, new NumericAttributeModifier("parent", 1, MULTIPLY_TOTAL));
        var child = new MachineAttributes(parent, 1);
        child.addModifier(SPEED, new NumericAttributeModifier("local", 1, ADDITION));
        child.addModifier(SPEED, new NumericAttributeModifier("local_total", 0.5, MULTIPLY_TOTAL));
        child.setResolvedBase(SPEED, parent.value(SPEED));
        assertEquals(7.5, child.value(SPEED));
        var saved = child.localSnapshot();
        parent.setBase(SPEED, 10);
        assertEquals(7.5, child.value(SPEED));
        child.setBase(SPEED, 100);
        child.restoreLocal(saved);
        assertEquals(7.5, child.value(SPEED));
        child.removeModifier(SPEED, "local");
        child.removeModifier(SPEED, "local_total");
        assertEquals(4, child.value(SPEED));
        assertThrows(IllegalArgumentException.class, () -> child.setResolvedBase(SPEED, Double.NaN));
        assertEquals(4, child.value(SPEED));
        assertThrows(IllegalArgumentException.class, () -> new MachineAttributes.LocalAttribute(null,
            Map.of("local", new NumericAttributeModifier("local", 1, ADDITION)), false));
    }

    @Test
    void inheritanceBlockingComposesAcrossLayersAndOrdinarySetOrClearRestoresParentModifiers() {
        var root = new MachineAttributes();
        root.setBase(SPEED, 2);
        root.addModifier(SPEED, new NumericAttributeModifier("root", 1, MULTIPLY_TOTAL));
        var middle = new MachineAttributes(root, 1);
        middle.addModifier(SPEED, new NumericAttributeModifier("middle", 1, ADDITION));
        middle.setResolvedBase(SPEED, root.value(SPEED));
        var leaf = new MachineAttributes(middle, 1);
        leaf.addModifier(SPEED, new NumericAttributeModifier("leaf", 2, ADDITION));
        assertEquals(7, leaf.value(SPEED));
        leaf.setResolvedBase(SPEED, middle.value(SPEED));
        assertEquals(7, leaf.value(SPEED));
        leaf.setBase(SPEED, 4);
        assertEquals(7, leaf.value(SPEED));
        assertTrue(middle.clearBase(SPEED));
        assertEquals(14, leaf.value(SPEED));
        assertTrue(leaf.clearBase(SPEED));
        assertEquals(10, leaf.value(SPEED));
        middle.setResolvedBase(SPEED, root.value(SPEED));
        assertEquals(7, leaf.value(SPEED));
        middle.setBase(SPEED, 4);
        assertEquals(14, leaf.value(SPEED));
    }

    @Test
    void modifierOnlyLayersFollowNewReplacedAndRemovedParentEntries() {
        var parent = new MachineAttributes();
        var child = new MachineAttributes(parent, 1);
        assertEquals(9, child.value(SPEED, 9));
        child.addModifier(SPEED, new NumericAttributeModifier("local", 2, ADDITION));
        assertEquals(3, child.value(SPEED, 9));
        parent.setBase(SPEED, 4);
        assertEquals(6, child.value(SPEED));
        parent.restoreLocal(Map.of(SPEED, new MachineAttributes.LocalAttribute(10.0,
            Map.of("parent", new NumericAttributeModifier("parent", 0.5, MULTIPLY_TOTAL)))));
        assertEquals(18, child.value(SPEED));
        parent.restoreLocal(Map.of());
        assertEquals(3, child.value(SPEED));
        child.removeModifier(SPEED, "local");
        assertEquals(9, child.value(SPEED, 9));
        parent.addModifier(SPEED, new NumericAttributeModifier("root", 2, ADDITION));
        assertEquals(2, child.value(SPEED));
    }

    @Test
    void localSnapshotsCopyBothMapsAndRestoreDeltasAgainstTheCurrentParent() {
        var parent = new MachineAttributes();
        parent.setBase(SPEED, 10);
        parent.setBase(OTHER, 99);
        var child = new MachineAttributes(parent, 1);
        var modifiers = new Object2ObjectLinkedOpenHashMap<String, AttributeModifier>();
        modifiers.put("add", new NumericAttributeModifier("add", 2, ADDITION));
        var saved = new MachineAttributes.LocalAttribute(null, modifiers);
        var input = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAttributes.LocalAttribute>();
        input.put(SPEED, saved);
        child.restoreLocal(input);
        modifiers.clear();
        input.clear();
        assertEquals(12, child.value(SPEED));
        var snapshot = child.localSnapshot();
        assertEquals(1, snapshot.size());

        child.setBase(SPEED, 100);
        child.removeModifier(SPEED, "add");
        child.setBase(OTHER, 5);
        parent.setBase(SPEED, 30);
        child.restoreLocal(snapshot);
        assertEquals(32, child.value(SPEED));
        assertEquals(99, child.value(OTHER));
        assertEquals(1, child.localSnapshot().size());

        var rootSnapshot = parent.localSnapshot();
        parent.setBase(SPEED, 100);
        parent.restoreLocal(rootSnapshot);
        assertEquals(32, child.value(SPEED));
    }

    @Test
    void rejectedRestoreCannotPartiallyReplaceExistingLocalState() {
        var attributes = new MachineAttributes();
        attributes.setBase(SPEED, 10);
        attributes.addModifier(SPEED, new NumericAttributeModifier("add", 2, ADDITION));
        var invalid = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAttributes.LocalAttribute>();
        invalid.put(SPEED, new MachineAttributes.LocalAttribute(99.0, Map.of()));
        invalid.put(OTHER, null);
        assertThrows(NullPointerException.class, () -> attributes.restoreLocal(invalid));
        assertEquals(12, attributes.value(SPEED));
        assertEquals(1, attributes.localSnapshot().size());
        assertThrows(IllegalArgumentException.class, () -> new MachineAttributes.LocalAttribute(null,
            Map.of("wrong_key", new NumericAttributeModifier("actual_id", 1, ADDITION))));
        assertEquals(12, attributes.value(SPEED));
    }

    @Test
    void nonfiniteInputsAndCalculatedResultsFailWithoutPoisoningStoredState() {
        var attributes = new MachineAttributes();
        attributes.setBase(SPEED, 10);
        assertThrows(IllegalArgumentException.class, () -> attributes.setBase(SPEED, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> attributes.value(SPEED, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new NumericAttributeModifier("bad", Double.NaN, ADDITION));
        assertEquals(10, attributes.value(SPEED));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            attributes.addModifier(SPEED, new FixedResult("custom", invalid));
            assertThrows(IllegalStateException.class, () -> attributes.value(SPEED));
            attributes.removeModifier(SPEED, "custom");
            assertEquals(10, attributes.value(SPEED));
        }
        attributes.setBase(SPEED, Double.MAX_VALUE);
        attributes.addModifier(SPEED, new NumericAttributeModifier("overflow", Double.MAX_VALUE, ADDITION));
        assertThrows(IllegalStateException.class, () -> attributes.value(SPEED));
        attributes.removeModifier(SPEED, "overflow");
        assertEquals(Double.MAX_VALUE, attributes.value(SPEED));
        attributes.setBase(SPEED, -2);
        attributes.addModifier(SPEED, new NumericAttributeModifier("negative", -3, ADDITION));
        assertEquals(-5, attributes.value(SPEED));
    }

    private record DoubleCurrent(String id) implements AttributeModifier {
        @Override
        public Operation operation() {
            return ADDITION;
        }

        @Override
        public double apply(double base, double current) {
            return current * 2;
        }
    }

    private record FixedResult(String id, double result) implements AttributeModifier {
        @Override
        public Operation operation() {
            return ADDITION;
        }

        @Override
        public double apply(double base, double current) {
            return result;
        }
    }
}
