package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceInputResolutionTest {
    private static final KeyType FIRST = new KeyType("resolution_first");
    private static final KeyType SECOND = new KeyType("resolution_second");
    private static final Key A = new Key(FIRST, "a");
    private static final Key B = new Key(FIRST, "b");
    private static final Key C = new Key(SECOND, "c");
    private static final NamedResourceMatcher ALL = new NamedResourceMatcher(id("all"), (_, _) -> true);

    private static RecipeRequirement<ResourceRequirement> resource(List<GenericStack> fixed, List<FuzzyInputGroup> fuzzy,
                                                                   List<DynamicInputGroup> dynamic, ResourceChance chance) {
        return RecipeRequirements.RESOURCES.requirement(id("resources"), new ResourceRequirement(fixed, List.of(), List.of(),
            List.of(), false, chance, null, fuzzy, dynamic));
    }

    private static ResourceInputLocks.Key lock(RecipeRequirement<?> requirement, ResourceInputLocks.Kind kind, int group) {
        return new ResourceInputLocks.Key(requirement.path(null), RecipeStage.START, kind, group);
    }

    private static RecipeRequirement<String> gate(boolean[] active, boolean failCommit) {
        var type = new RecipeRequirementType<>(new ResourceLocation("zz_resolution_test", "gate"), new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return new GateTransaction(active, failCommit);
            }
        });
        return type.requirement(id("gate"), "gate");
    }

    private static RecipeProcess process(List<? extends ResourceHatch> hatches, int parallelism, RecipeRequirement<?>... requirements) {
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallelism);
        machine.components().put(MachineHatches.TYPE, new MachineHatches(new ObjectArrayList<>(hatches), List.of(), List.of()));
        return new RecipeProcess(machine, new MachineRecipe(id("recipe"), 0, ObjectArrayList.of(requirements)), 19);
    }

    private static BufferedResourceHatch hatch() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, 32);
        capacities.put(SECOND, 32);
        return new BufferedResourceHatch(HatchMode.INPUT, 3, capacities, () -> {
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("resolution_test", path);
    }

    @Test
    void fixedAndEarlierGroupDemandParticipateBeforeChoosingLaterAlternativesAcrossTypes() {
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        input.insert(A, 6, Actionable.MODULATE);
        input.insert(B, 6, Actionable.MODULATE);
        input.insert(C, 6, Actionable.MODULATE);
        var resource = resource(List.of(new GenericStack(A, 1)), List.of(
            new FuzzyInputGroup(List.of(A, B), 2), new FuzzyInputGroup(List.of(A, B, C), 2),
            new FuzzyInputGroup(List.of(B, C), 2)), List.of(), ResourceChance.ALWAYS);
        var process = process(List.of(hatch), 2, resource);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.amount(A));
        assertEquals(2, input.amount(B));
        assertEquals(2, input.amount(C));
        var locks = ResourceInputLocks.getOrCreate(process);
        assertEquals(A, locks.get(lock(resource, ResourceInputLocks.Kind.FUZZY, 0)));
        assertEquals(B, locks.get(lock(resource, ResourceInputLocks.Kind.FUZZY, 1)));
        assertEquals(C, locks.get(lock(resource, ResourceInputLocks.Kind.FUZZY, 2)));
    }

    @Test
    void rejectedOuterStartUndoesChoicesAndRetryCanUseADifferentCandidate() {
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        input.insert(A, 4, Actionable.MODULATE);
        input.insert(C, 4, Actionable.MODULATE);
        boolean[] blocked = {true};
        var resource = resource(List.of(), List.of(new FuzzyInputGroup(List.of(A, C), 2)), List.of(), ResourceChance.ALWAYS);
        var process = process(List.of(hatch), 1, resource, gate(blocked, false));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(4, input.availableAmount(A));
        assertTrue(ResourceInputLocks.getOrCreate(process).snapshot().isEmpty());
        input.extract(A, 4, Actionable.MODULATE);
        blocked[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(2, input.amount(C));
        assertEquals(C, ResourceInputLocks.getOrCreate(process).get(lock(resource, ResourceInputLocks.Kind.FUZZY, 0)));
    }

    @Test
    void laterBusinessCommitFailureRefundsActualInputAndRemovesOnlyNewChoices() {
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        input.insert(A, 8, Actionable.MODULATE);
        input.insert(C, 8, Actionable.MODULATE);
        boolean[] fail = {true};
        var resource = resource(List.of(), List.of(new FuzzyInputGroup(List.of(A, C), 2),
            new FuzzyInputGroup(List.of(C), 1)), List.of(), ResourceChance.ALWAYS);
        var process = process(List.of(hatch), 1, resource, gate(fail, true));
        var locks = ResourceInputLocks.getOrCreate(process);
        var original = lock(resource, ResourceInputLocks.Kind.FUZZY, 0);
        locks.putIfAbsent(original, C);
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(8, input.availableAmount(C));
        assertEquals(1, locks.snapshot().size());
        assertEquals(C, locks.get(original));
        fail[0] = false;
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(8, input.amount(C));
        assertEquals(8, input.amount(A));
        assertEquals(1, locks.snapshot().size());
        assertEquals(C, locks.get(original));
    }

    @Test
    void zeroChanceRequiresConservativeQuantityAndRetainsChoiceWithoutConsumingIt() {
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        input.insert(A, 1, Actionable.MODULATE);
        var resource = resource(List.of(), List.of(new FuzzyInputGroup(List.of(A, C), 1)), List.of(), new ResourceChance(0, null));
        var process = process(List.of(hatch), 2, resource);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertTrue(ResourceInputLocks.getOrCreate(process).snapshot().isEmpty());
        input.insert(C, 2, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, input.availableAmount(A));
        assertEquals(2, input.availableAmount(C));
        assertEquals(C, ResourceInputLocks.getOrCreate(process).get(lock(resource, ResourceInputLocks.Kind.FUZZY, 0)));
    }

    @Test
    void restoredDynamicIdentityNeverSwitchesToANewEarlierCandidateWhenLockedMaterialIsMissing() {
        var originalHatch = hatch();
        Objects.requireNonNull(originalHatch.input()).insert(B, 2, Actionable.MODULATE);
        var resource = resource(List.of(), List.of(), List.of(new DynamicInputGroup(ALL, A, 1)), new ResourceChance(0, null));
        var original = process(List.of(originalHatch), 1, resource);
        assertSame(ProcessResult.SUCCESS, original.tick());
        var saved = ResourceInputLocks.getOrCreate(original).snapshot();
        var restoredHatch = hatch();
        var input = Objects.requireNonNull(restoredHatch.input());
        input.insert(A, 2, Actionable.MODULATE);
        var restored = process(List.of(restoredHatch), 1, resource);
        var locks = ResourceInputLocks.getOrCreate(restored);
        locks.restore(saved);
        assertInstanceOf(ProcessResult.Blocked.class, restored.tick());
        assertEquals(saved, locks.snapshot());
        input.insert(B, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(B, locks.get(lock(resource, ResourceInputLocks.Kind.DYNAMIC, 0)));
        assertEquals(2, input.amount(A));
    }

    @Test
    void dynamicSelectionSortsBeforeLimitAndAccountsForFuzzyDemandAndAliasedSources() {
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        input.insert(B, 1, Actionable.MODULATE);
        input.insert(A, 2, Actionable.MODULATE);
        input.insert(C, 3, Actionable.MODULATE);
        var resource = resource(List.of(), List.of(new FuzzyInputGroup(List.of(A), 1)),
            List.of(new DynamicInputGroup(ALL, C, 2, List.of(C), 2)), ResourceChance.ALWAYS);
        var process = process(List.of(hatch, hatch), 1, resource);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(2, input.availableAmount(A));
        assertTrue(ResourceInputLocks.getOrCreate(process).snapshot().isEmpty());
        input.insert(B, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, input.amount(A));
        assertEquals(0, input.amount(B));
        assertEquals(3, input.amount(C));
        assertEquals(B, ResourceInputLocks.getOrCreate(process).get(lock(resource, ResourceInputLocks.Kind.DYNAMIC, 0)));
    }

    @Test
    void checkpointsReusingOneChildDefinitionOwnIndependentChoicesAndRollBackTogether() {
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        input.insert(A, 1, Actionable.MODULATE);
        input.insert(C, 1, Actionable.MODULATE);
        var child = resource(List.of(), List.of(new FuzzyInputGroup(List.of(A, C), 1)), List.of(), ResourceChance.ALWAYS);
        var first = RecipeRequirements.CHECKPOINT.requirement(id("first"), new CheckpointRequirement(0, child));
        var second = RecipeRequirements.CHECKPOINT.requirement(id("second"), new CheckpointRequirement(0, child));
        boolean[] blocked = {true};
        var process = process(List.of(hatch), 1, first, second, gate(blocked, false));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        var locks = ResourceInputLocks.getOrCreate(process);
        assertTrue(locks.snapshot().isEmpty());
        assertEquals(1, input.availableAmount(A));
        assertEquals(1, input.availableAmount(C));
        blocked[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.amount(A));
        assertEquals(0, input.amount(C));
        assertEquals(A, locks.get(new ResourceInputLocks.Key(child.path(first.path(null)), RecipeStage.START, ResourceInputLocks.Kind.FUZZY, 0)));
        assertEquals(C, locks.get(new ResourceInputLocks.Key(child.path(second.path(null)), RecipeStage.START, ResourceInputLocks.Kind.FUZZY, 0)));
    }

    private record GateTransaction(boolean[] active, boolean failCommit) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return active[0] && !failCommit ? new ProcessResult.Blocked("external.wait") : ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
            if (active[0] && failCommit) {
                throw new IllegalStateException("External business commit failed");
            }
        }

        @Override
        public void rollback() {
        }
    }
}
