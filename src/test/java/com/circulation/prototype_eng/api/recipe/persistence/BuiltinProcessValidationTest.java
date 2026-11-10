package com.circulation.prototype_eng.api.recipe.persistence;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinProcessValidationTest {
    private static final KeyType TYPE = new KeyType("process_validation");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key WRONG = new Key(TYPE, "wrong");

    private static SelectiveState.SelectionSnapshot decided(int index, boolean completed) {
        return new SelectiveState.SelectionSnapshot(true, index, completed, List.of(), List.of());
    }

    private static RecipeRequirement<SelectiveRequirement> selector(String name) {
        return RecipeRequirements.SELECTIVE.requirement(id(name), new SelectiveRequirement(List.of()));
    }

    private static RecipeRequirement<CheckpointRequirement> checkpoint(String name, int time, RecipeRequirement<?> child, boolean scaled) {
        return RecipeRequirements.CHECKPOINT.requirement(id(name), new CheckpointRequirement(time, child, scaled));
    }

    private static RecipeRequirement<ResourceRequirement> resource() {
        var matcher = new NamedResourceMatcher(id("exact"), AEKey::equals);
        return RecipeRequirements.RESOURCES.requirement(id("resource"), new ResourceRequirement(List.of(), List.of(), List.of(),
            List.of(), false, ResourceChance.ALWAYS, null, List.of(new FuzzyInputGroup(List.of(MATERIAL), 1)),
            List.of(new DynamicInputGroup(matcher, MATERIAL, 1))));
    }

    private static RecipeProcess process(RecipeRequirement<?> root) {
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), 20, List.of(root)), 1);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("process_validation_test", path);
    }

    @Test
    void zeroWidthNestedTickAllowsMissingPositiveChildButRejectsAnInventedDecision() {
        var selection = selector("selection");
        var inner = checkpoint("inner", 1, selection, true);
        var outer = checkpoint("outer", 0, inner, true);
        var process = process(outer);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var index = new RecipeDefinitionIndex(process.recipe());
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(process, index));
        var path = selection.path(inner.path(outer.path(null)));
        SelectiveState.getOrCreate(process).restore(Map.of(path, decided(-1, true)));
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
    }

    @Test
    void coincidentNestedThresholdRequiresTheCompletedChildDecision() {
        var selection = selector("selection");
        var inner = checkpoint("inner", 2, selection, true);
        var outer = checkpoint("outer", 2, inner, true);
        var process = process(outer);
        process.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 2);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var index = new RecipeDefinitionIndex(process.recipe());
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(process, index));
        var path = selection.path(inner.path(outer.path(null)));
        assertTrue(Objects.requireNonNull(SelectiveState.getOrCreate(process).selection(path)).completed());
        process.components().remove(SelectiveState.TYPE);
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
    }

    @Test
    void identicalCurrentClocksCanHaveDifferentValidNestedScaledCheckpointHistories() {
        var selection = selector("selection");
        var inner = checkpoint("inner", 5, selection, true);
        var outer = checkpoint("outer", 10, inner, true);
        var recipe = new MachineRecipe(id("recipe"), 20, List.of(outer));
        var jumped = new RecipeProcess(new MachineInstance(), recipe, 1);
        var stepped = new RecipeProcess(new MachineInstance(), recipe, 1);
        jumped.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0);
        for (int tick = 0; tick < 9; tick++) {
            assertSame(ProcessResult.SUCCESS, jumped.tick());
            assertSame(ProcessResult.SUCCESS, stepped.tick());
        }
        jumped.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 10);
        assertSame(ProcessResult.SUCCESS, jumped.tick());
        assertSame(ProcessResult.SUCCESS, stepped.tick());
        assertEquals(jumped.state(), stepped.state());
        assertEquals(1, SelectiveState.getOrCreate(jumped).snapshot().size());
        assertTrue(SelectiveState.getOrCreate(stepped).snapshot().isEmpty());
        var index = new RecipeDefinitionIndex(recipe);
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(jumped, index));
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(stepped, index));
    }

    @Test
    void distinctUnscaledThresholdsCannotInventAChildDecisionInOneSuccessfulTick() {
        var selection = selector("selection");
        var inner = checkpoint("inner", 1, selection, false);
        var outer = checkpoint("outer", 2, inner, false);
        var process = process(outer);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertSame(ProcessResult.SUCCESS, process.tick());
        var index = new RecipeDefinitionIndex(process.recipe());
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(process, index));
        SelectiveState.getOrCreate(process).restore(Map.of(selection.path(inner.path(outer.path(null))), decided(-1, true)));
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
    }

    @Test
    void missingOrMismatchingCommittedGroupsAreRejectedWithoutRepeatingInputConsumption() {
        var resource = resource();
        var capacity = new Reference2LongArrayMap<AEKeyType>();
        capacity.put(TYPE, 20);
        var hatch = new BufferedResourceHatch(HatchMode.INPUT, 1, capacity, () -> {
        });
        var input = Objects.requireNonNull(hatch.input());
        input.insert(MATERIAL, 10, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        var process = new RecipeProcess(machine, new MachineRecipe(id("recipe"), 20, List.of(resource)), 1);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var index = new RecipeDefinitionIndex(process.recipe());
        var locks = ResourceInputLocks.getOrCreate(process);
        var original = locks.snapshot();
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(process, index));
        var fuzzy = new ResourceInputLocks.Key(resource.path(null), RecipeStage.START, ResourceInputLocks.Kind.FUZZY, 0);
        var dynamic = new ResourceInputLocks.Key(resource.path(null), RecipeStage.START, ResourceInputLocks.Kind.DYNAMIC, 0);
        locks.restore(Map.of(fuzzy, MATERIAL));
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
        locks.restore(Map.of(fuzzy, WRONG, dynamic, MATERIAL));
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
        locks.restore(Map.of(fuzzy, MATERIAL, dynamic, WRONG));
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
        locks.restore(original);
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(process, index));
        assertEquals(8, input.availableAmount(MATERIAL));
    }

    @Test
    void missingSelectionOrWrongEndCompletionCannotEraseACommittedRootChoice() {
        var root = selector("root");
        var process = process(root);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var index = new RecipeDefinitionIndex(process.recipe());
        assertDoesNotThrow(() -> BuiltinProcessValidation.validate(process, index));
        process.components().remove(SelectiveState.TYPE);
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
        SelectiveState.getOrCreate(process).restore(Map.of(root.path(null), decided(-1, true)));
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
        process.components().remove(SelectiveState.TYPE);
        SelectiveState.getOrCreate(process).restore(Map.of(root.path(null), decided(0, false)));
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index));
    }

    @Test
    void duplicateSelectionOwnershipIsRejectedBeforeAnySelectionIsPublished() {
        var child = RecipeRequirements.RESOURCES.requirement(id("empty"), new ResourceRequirement(List.of(), List.of()));
        var first = RecipeRequirements.SELECTIVE.requirement(id("first"), new SelectiveRequirement(List.of(child)));
        var second = RecipeRequirements.SELECTIVE.requirement(id("second"), new SelectiveRequirement(List.of(child)));
        var recipe = new MachineRecipe(id("recipe"), 20, List.of(first, second));
        var process = RecipeProcess.restored(new MachineInstance(), recipe, 1, ProbabilityTuning.defaultTuning(),
            new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 1, 1, ProcessResult.SUCCESS));
        var attribute = id("attribute");
        process.attributes().addModifier(attribute, new NumericAttributeModifier("owned", 2, AttributeModifier.Operation.ADDITION));
        var reference = new SelectiveState.ModifierReference(attribute, "owned");
        var duplicate = new SelectiveState.SelectionSnapshot(true, 0, false, List.of(reference), List.of());
        var state = SelectiveState.getOrCreate(process);
        assertThrows(IllegalArgumentException.class, () -> state.restore(Map.of(first.path(null), duplicate, second.path(null), duplicate)));
        assertTrue(state.snapshot().isEmpty());
        assertEquals(3, process.attributes().value(attribute));
    }

    @Test
    void externalStructureExemptionDoesNotPermitCommittedDecisionsBeforeStart() {
        var resource = resource();
        var process = process(resource);
        ResourceInputLocks.getOrCreate(process).putIfAbsent(new ResourceInputLocks.Key(resource.path(null), RecipeStage.START,
            ResourceInputLocks.Kind.FUZZY, 0), MATERIAL);
        var index = new RecipeDefinitionIndex(process.recipe());
        assertThrows(IllegalArgumentException.class, () -> BuiltinProcessValidation.validate(process, index, false, false));
    }
}
