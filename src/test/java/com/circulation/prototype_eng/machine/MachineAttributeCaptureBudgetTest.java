package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineAttributeCaptureBudgetTest {
    private static final ResourceLocation SPEED = new ResourceLocation("attribute_capture_test", "speed");
    private static final ResourceLocation ABSENT = new ResourceLocation("attribute_capture_test", "absent");

    private static double complete(MachineAttributes.ValueCapture capture) {
        for (int step = 0; step < 10000; step++) if (capture.advance(new RecipeWorkBudget(1))) return capture.result();
        throw new AssertionError("Attribute capture did not finish its native layer and modifier cursors");
    }

    @Test
    void nativeCaptureKeepsShadowedModifierOrderAndTheResolvedBaseBoundary() {
        var parent = new MachineAttributes();
        parent.setBase(SPEED, 10);
        parent.addModifier(SPEED, new NumericAttributeModifier("shadow", 2, AttributeModifier.Operation.ADDITION));
        parent.addModifier(SPEED, new NumericAttributeModifier("total", 0.5, AttributeModifier.Operation.MULTIPLY_TOTAL));
        var child = new MachineAttributes(parent, 1);
        child.addModifier(SPEED, new NumericAttributeModifier("shadow", 7, AttributeModifier.Operation.ADDITION));
        var calls = new AtomicInteger();
        child.addModifier(SPEED, new AttributeModifier() {
            @Override
            public String id() {
                return "double";
            }

            @Override
            public Operation operation() {
                return Operation.ADDITION;
            }

            @Override
            public double apply(double base, double current) {
                calls.incrementAndGet();
                return current * 2;
            }
        });
        assertEquals(51, child.value(SPEED));
        calls.set(0);
        assertEquals(51, complete(child.beginValueCapture(SPEED, -1)));
        assertEquals(1, calls.get());
        child.setResolvedBase(SPEED, parent.value(SPEED));
        calls.set(0);
        assertEquals(50, complete(child.beginValueCapture(SPEED, -1)));
        assertEquals(1, calls.get());
        assertEquals(123, complete(child.beginValueCapture(ABSENT, 123)));
    }

    @Test
    void mutationDuringCaptureRejectsTheOldProofAndAFreshCaptureUsesTheNewParent() {
        var parent = new MachineAttributes();
        parent.setBase(SPEED, 10);
        parent.addModifier(SPEED, new NumericAttributeModifier("add", 2, AttributeModifier.Operation.ADDITION));
        var child = new MachineAttributes(parent, 1);
        var capture = child.beginValueCapture(SPEED, -1);
        assertFalse(capture.advance(new RecipeWorkBudget(1)));
        parent.setBase(SPEED, 20);
        assertFalse(capture.current());
        assertFalse(capture.advance(new RecipeWorkBudget(100)));
        assertThrows(IllegalStateException.class, capture::result);
        assertEquals(22, complete(child.beginValueCapture(SPEED, -1)));
        assertTrue(child.beginValueCapture(SPEED, -1).current());
    }
}
