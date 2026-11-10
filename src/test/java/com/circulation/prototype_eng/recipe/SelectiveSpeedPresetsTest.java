package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifiers;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectiveSpeedPresetsTest {
    private static final ResourceLocation SPEED = StandardMachineAttributes.PROCESS_SPEED;

    private static RecipeRequirement<SelectiveRequirement> selector(String name, RecipeRequirement<?> candidate,
                                                                    SelectiveModifierDescriptor... modifiers) {
        return RecipeRequirements.SELECTIVE.requirement(id(name), new SelectiveRequirement(List.of(candidate), List.of(modifiers)));
    }

    private static RecipeRequirement<Integer> candidate() {
        return new RecipeRequirementType<Integer>(id("candidate_type"), new RecipeRequirementSystem<>() {
        }).requirement(id("candidate"), 0);
    }

    private static RecipeProcess process(RecipeRequirement<?>... requirements) {
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), 10, List.of(requirements)), 17);
    }

    private static NumericAttributeModifier foreignAddition(double amount) {
        return new NumericAttributeModifier("foreign", amount, AttributeModifier.Operation.ADDITION);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("selective_speed_test", name);
    }

    @Test
    void selectedPresetsComposeWithInheritedAndForeignSpeedAndAdvanceActualProgress() {
        var choice = selector("choice", candidate(),
            SelectiveModifiers.processSpeedMultiplier(id("fast"), "speed", 1.25),
            SelectiveModifiers.processSpeedAddition(id("addition"), "speed", 0.5),
            SelectiveModifiers.processSpeedMultiplier(id("slow"), "speed", 0.8));
        var process = process(choice);
        process.owner().attributes().setBase(SPEED, 2);
        process.attributes().addModifier(SPEED, foreignAddition(0.5));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(3, process.attributes().value(SPEED), 1e-12);
        assertEquals(3, process.state().progress(), 1e-12);
        assertEquals(1, process.state().successfulTicks());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(6, process.state().progress(), 1e-12);
        assertTrue(process.cancel());
        assertEquals(2.5, process.attributes().value(SPEED), 1e-12);
    }

    @Test
    void contextSpeedOperationsReplaceOnlyTheirLocalModifierAndKeepSiblingSelectors() {
        var first = selector("first", candidate(), new SelectiveModifierDescriptor(id("custom"), context -> {
            context.addProcessSpeed("addition", 4);
            context.addProcessSpeed("addition", 1);
            context.mulProcessSpeed("factor", 3);
            context.mulProcessSpeed("factor", 1.5);
        }));
        var second = selector("second", candidate(), SelectiveModifiers.processSpeedMultiplier(id("custom"), "factor", 2));
        var process = process(first, second);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(6, process.state().progress(), 1e-12);
        assertTrue(process.cancel());
        assertEquals(1, process.attributes().value(SPEED), 1e-12);
    }

    @Test
    void throwingModifierAfterPresetsRollsBackChildBusinessAndAllOwnedSpeed() {
        int[] childBalance = {0};
        var candidate = new RecipeRequirementType<Integer>(id("business"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                return new Business(childBalance);
            }
        }).requirement(id("candidate"), 0);
        var failure = new IllegalStateException("modifier failure");
        var choice = selector("choice", candidate,
            SelectiveModifiers.processSpeedAddition(id("addition"), "speed", 2),
            SelectiveModifiers.processSpeedMultiplier(id("fast"), "speed", 3),
            new SelectiveModifierDescriptor(id("failure"), context -> {
                assertEquals(12, context.process().attributes().value(SPEED), 1e-12);
                throw failure;
            }));
        var process = process(choice);
        process.attributes().addModifier(SPEED, foreignAddition(1));
        assertSame(failure, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(0, childBalance[0]);
        assertEquals(2, process.attributes().value(SPEED), 1e-12);
        assertEquals(0, process.state().progress());
        assertNull(SelectiveState.getOrCreate(process).selection(choice.path(null)));
    }

    @Test
    void restoredPresetsKeepSpeedThroughBlockedEndAndClearAtEndCompletionWithoutReplay() {
        boolean[] blockEnd = {true};
        double[] endSpeed = {0};
        int[] applications = {0};
        var candidate = new RecipeRequirementType<Integer>(id("end_observer"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                endSpeed[0] = process.attributes().value(SPEED);
                return new Decision(blockEnd[0] ? new ProcessResult.Blocked("end") : ProcessResult.SUCCESS);
            }
        }).requirement(id("candidate"), 0);
        var choice = selector("choice", candidate,
            SelectiveModifiers.processSpeedAddition(id("addition"), "speed", 1),
            SelectiveModifiers.processSpeedMultiplier(id("fast"), "speed", 2),
            new SelectiveModifierDescriptor(id("observer"), _ -> applications[0]++));
        var original = process(choice);
        original.attributes().addModifier(SPEED, foreignAddition(1));
        assertSame(ProcessResult.SUCCESS, original.tick());
        var codec = RecipeProcessNbt.builder(original.recipe(), "speed-presets-1").build();
        var restored = codec.decode(original.owner(), codec.encode(original));
        assertEquals(1, applications[0]);
        assertEquals(6, restored.attributes().value(SPEED), 1e-12);
        assertInstanceOf(ProcessResult.Blocked.class, restored.tick());
        assertEquals(12, restored.state().progress(), 1e-12);
        assertEquals(6, endSpeed[0], 1e-12);
        assertEquals(6, restored.attributes().value(SPEED), 1e-12);
        restored = codec.decode(original.owner(), codec.encode(restored));
        blockEnd[0] = false;
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, restored.state().lifecycle());
        assertEquals(6, endSpeed[0], 1e-12);
        assertEquals(2, restored.attributes().value(SPEED), 1e-12);
        assertEquals(1, applications[0]);
        assertEquals(6, original.attributes().value(SPEED), 1e-12);
        assertTrue(SelectiveState.getOrCreate(restored).snapshot().get(choice.path(null)).completed());
    }

    @Test
    void zeroAndNegativeFactorsKeepSuccessfulTicksWithoutAdvancingWorkAndCancelCleansThem() {
        for (double factor : new double[]{0, -0.25}) {
            var process = process(selector("choice", candidate(), SelectiveModifiers.processSpeedMultiplier(id("factor"), "speed", factor)));
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(factor, process.attributes().value(SPEED), 1e-12);
            assertEquals(0, process.state().progress());
            assertEquals(1, process.state().successfulTicks());
            assertTrue(process.cancel());
            assertEquals(1, process.attributes().value(SPEED), 1e-12);
        }
    }

    private record Business(int[] balance) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
            balance[0]++;
        }

        @Override
        public void rollback() {
            balance[0]--;
        }
    }

    private record Decision(ProcessResult result) implements RequirementTransaction {
        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }
}
