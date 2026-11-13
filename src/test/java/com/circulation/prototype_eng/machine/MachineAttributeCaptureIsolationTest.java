package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordinary attribute ancestry and whole-machine parallel accounting have different invalidation scopes.
 */
class MachineAttributeCaptureIsolationTest {
    private static final ResourceLocation PARALLEL = StandardMachineAttributes.PROCESS_PARALLELISM;

    private static double complete(MachineAttributes.ValueCapture capture) {
        for (int slice = 0; slice < 100; slice++) {
            if (capture.advance(new RecipeWorkBudget(1))) return capture.result();
            assertTrue(capture.current());
        }
        throw new AssertionError("Stable attribute capture did not make bounded progress");
    }

    private static MachineWorkSlots.SharedUsage complete(MachineWorkSlots.UsageCapture capture) {
        for (int slice = 0; slice < 100; slice++) {
            if (capture.advance(new RecipeWorkBudget(1))) return capture.result();
            assertTrue(capture.current());
        }
        throw new AssertionError("Stable shared-usage capture did not make bounded progress");
    }

    @Test
    void siblingNonParallelLocalEditDoesNotRevokeAValueCaptureThatAlreadyYielded() {
        var root = new MachineAttributes();
        root.setBase(PARALLEL, 4);
        var first = new MachineAttributes(root, 1);
        var sibling = new MachineAttributes(root, 1);
        var capture = first.beginValueCapture(PARALLEL, -1);
        assertFalse(capture.advance(new RecipeWorkBudget(1)));
        sibling.setBase(StandardMachineAttributes.PROCESS_SPEED, 2);
        assertTrue(capture.current());
        assertEquals(4, complete(capture));
        assertTrue(capture.current());
    }

    @Test
    void ownOrRealParentEditRevokesTheOriginalCaptureAndAFreshCaptureResolvesTheNewValue() {
        for (boolean editParent : List.of(false, true)) {
            var root = new MachineAttributes();
            root.setBase(PARALLEL, 4);
            var first = new MachineAttributes(root, 1);
            var capture = first.beginValueCapture(PARALLEL, -1);
            assertFalse(capture.advance(new RecipeWorkBudget(1)));
            if (editParent) root.setBase(PARALLEL, 9);
            else first.setBase(PARALLEL, 7);
            assertFalse(capture.current());
            assertFalse(capture.advance(new RecipeWorkBudget(100)));
            assertThrows(IllegalStateException.class, capture::result);
            assertEquals(editParent ? 9 : 7, complete(first.beginValueCapture(PARALLEL, -1)));
        }
    }

    @Test
    void siblingParallelEditRevokesAggregateUsageButLeavesTheOtherProcessesOwnValueCaptureCurrent() {
        var owner = new MachineInstance();
        owner.attributes().setBase(PARALLEL, 6);
        var firstHost = new MachineWorkSlots(owner, List.of(new WorkSlotDefinition("first")));
        var secondHost = new MachineWorkSlots(owner, List.of(new WorkSlotDefinition("second")));
        var recipe = new MachineRecipe(new ResourceLocation("attribute_isolation_test", "recipe"), 2, List.of());
        var first = new RecipeProcess(owner, recipe, 7);
        var sibling = new RecipeProcess(owner, recipe, 11);
        first.attributes().setResolvedBase(PARALLEL, 1);
        sibling.attributes().setResolvedBase(PARALLEL, 2);
        assertTrue(firstHost.startIn("first", first));
        assertTrue(secondHost.startIn("second", sibling));
        var usage = firstHost.beginSharedUsage(recipe);
        var old = complete(usage);
        assertEquals(3, old.occupied());
        assertEquals(3, old.available());
        var firstValue = first.attributes().beginValueCapture(PARALLEL, -1);
        assertFalse(firstValue.advance(new RecipeWorkBudget(1)));
        sibling.attributes().setResolvedBase(PARALLEL, 4);
        assertTrue(firstValue.current());
        assertEquals(1, complete(firstValue));
        assertFalse(usage.current());
        assertThrows(IllegalStateException.class, usage::result);
        var refreshed = complete(firstHost.beginSharedUsage(recipe));
        assertEquals(5, refreshed.occupied());
        assertEquals(1, refreshed.available());
    }
}
