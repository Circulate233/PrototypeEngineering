package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.MachineRecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScanCaptureBudgetTest {
    private static ResourceLocation id(String path) {
        return new ResourceLocation("scan_capture_budget_test", path);
    }

    private static RecipeProcess complete(RecipeProcessFactory.Creation creation) {
        for (int call = 0; call < 10000; call++)
            if (creation.advance(new RecipeWorkBudget(1))) return creation.result();
        throw new AssertionError("Factory did not complete under small slices");
    }

    @Test
    void theStandardFactoryCapturesProviderOnceAndPreservesCanonicalStableOrderInActualStartCallbacks() {
        var events = new ObjectArrayList<String>();
        var providerCalls = new AtomicInteger();
        var system = new RecipeRequirementSystem<Boolean>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                events.add(requirement.id().getPath());
                return RequirementTransaction.SUCCESS;
            }
        };
        var earlier = new RecipeRequirementType<>(id("a_type"), system);
        var later = new RecipeRequirementType<>(id("z_type"), system);
        var declared = later.requirement(id("declared"), true);
        var additions = new ObjectArrayList<RecipeRequirement<?>>();
        for (int index = 0; index < 200; index++) additions.add(earlier.requirement(id("provider_" + index), true));
        additions.add(later.requirement(id("later_provider"), true));
        var machine = new MachineInstance();
        var provider = new ComponentType<MachineInstance, MachineRecipeRequirements>(id("provider"), _ -> _ -> List.copyOf(additions), List.of());
        machine.components().put(provider, _ -> {
            providerCalls.incrementAndGet();
            return additions;
        });
        var recipe = new MachineRecipe(id("recipe"), 1, List.of(declared));
        var creation = RecipeProcessFactory.STANDARD.beginCreation(machine, recipe, 17, ProbabilityTuning.defaultTuning());
        assertFalse(creation.advance(new RecipeWorkBudget(0)));
        assertEquals(0, providerCalls.get());
        assertThrows(IllegalStateException.class, creation::result);
        var process = complete(creation);
        assertEquals(1, providerCalls.get());
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        var expected = new ObjectArrayList<String>();
        for (int index = 0; index < 200; index++) expected.add("provider_" + index);
        expected.add("declared");
        expected.add("later_provider");
        assertEquals(expected, events);
        assertSame(declared, process.orderedRequirements().get(200));
    }

    @Test
    void theStandardFactoryRejectsCounterfeitCanonicalTypesBeforePublishingAProcess() {
        var machine = new MachineInstance();
        var canonicalType = new RecipeRequirementType<Boolean>(id("type"), new RecipeRequirementSystem<>() {
        });
        var counterfeitType = new RecipeRequirementType<Boolean>(id("type"), new RecipeRequirementSystem<>() {
        });
        var declared = canonicalType.requirement(id("declared"), true);
        var provider = new ComponentType<MachineInstance, MachineRecipeRequirements>(id("provider"), _ -> _ -> List.of(), List.of());
        machine.components().put(provider, _ -> List.of(counterfeitType.requirement(id("extra"), true)));
        var recipe = new MachineRecipe(id("recipe"), 1, List.of(declared));
        var creation = RecipeProcessFactory.STANDARD.beginCreation(machine, recipe, 17, ProbabilityTuning.defaultTuning());
        assertThrows(IllegalArgumentException.class, () -> complete(creation));
        assertThrows(IllegalStateException.class, creation::result);
    }

    @Test
    void aDirtySystemGraphIsValidatedIncrementallyAndItsStableRevisionIsReusedBeforeARealTick() {
        var map = new ComponentMap<>();
        var calls = new AtomicInteger();
        var beforeId = id("before");
        var tailId = id("tail");
        var events = new ObjectArrayList<String>();
        var tailSystem = new ComponentSystem<Object, Integer>() {
            @Override
            public ResourceLocation id() {
                return tailId;
            }

            @Override
            public void onTick(Object owner, Integer component) {
                events.add("tail");
            }
        };
        var beforeSystem = new ComponentSystem<Object, Integer>() {
            @Override
            public ResourceLocation id() {
                return beforeId;
            }

            @Override
            public Set<ResourceLocation> runBefore() {
                calls.incrementAndGet();
                return Set.of(tailId);
            }

            @Override
            public void onTick(Object owner, Integer component) {
                events.add("before");
            }
        };
        var tail = new ComponentType<>(id("tail_component"), _ -> 0, List.of(tailSystem));
        var owners = new ObjectArrayList<ComponentType<Object, Integer>>();
        map.put(tail, 0);
        for (int index = 0; index < 80; index++) {
            int number = index;
            var owner = new ComponentType<>(id("owner_" + index), _ -> number, List.of(beforeSystem));
            owners.add(owner);
            map.put(owner, index);
        }
        var capture = map.beginSnapshotBindings();
        assertFalse(capture.advance(new RecipeWorkBudget(0)));
        for (int call = 0; call < 10000 && !capture.advance(new RecipeWorkBudget(1)); call++)
            assertTrue(capture.current());
        assertEquals(81, capture.result().size());
        assertEquals(80, calls.get());
        var reused = map.beginSnapshotBindings();
        assertTrue(reused.advance(new RecipeWorkBudget(0)));
        assertEquals(80, calls.get());
        map.tick(new Object());
        assertEquals(81, events.size());
        assertEquals("tail", events.getLast());
        map.addDependency(owners.getFirst(), tail);
        var cyclic = map.beginSnapshotBindings();
        assertThrows(IllegalStateException.class, () -> {
            for (int call = 0; call < 10000; call++)
                if (cyclic.advance(new RecipeWorkBudget(1))) throw new AssertionError("Cycle was accepted");
        });
    }

    @Test
    void smallAdmissionSlicesKeepCapacityAndGroupChecksAuthoritativeAndCancelWithoutAttaching() {
        var definitions = new ObjectArrayList<WorkSlotDefinition>();
        for (int index = 0; index < 100; index++)
            definitions.add(new WorkSlotDefinition("slot_" + index, Set.of(id("allowed"))));
        var machine = new MachineInstance();
        var host = new MachineWorkSlots(machine, definitions);
        var recipe = new MachineRecipe(id("recipe"), 1, List.of(), Set.of(id("allowed")));
        var process = new RecipeProcess(machine, recipe, 17);
        var admission = host.beginAdmission("slot_99", process);
        assertFalse(admission.advance(new RecipeWorkBudget(1), () -> true));
        assertFalse(host.quiescent());
        assertThrows(IllegalStateException.class, () -> host.startIn("slot_0", new RecipeProcess(machine, recipe, 19)));
        admission.cancel();
        assertFalse(admission.admitted());
        assertTrue(host.quiescent());
        assertTrue(host.activeProcesses().isEmpty());
        var retry = host.beginAdmission("slot_99", process);
        for (int call = 0; call < 1000 && !retry.advance(new RecipeWorkBudget(1), () -> true); call++)
            assertTrue(host.activeProcesses().isEmpty());
        assertTrue(retry.admitted());
        assertSame(process, host.requireSlot("slot_99").process());
        var full = host.beginAdmission("slot_0", new RecipeProcess(machine, recipe, 23));
        for (int call = 0; call < 1000 && !full.advance(new RecipeWorkBudget(1), () -> true); call++) {
            assertEquals(1, host.activeProcesses().size());
        }
        assertFalse(full.admitted());
        assertEquals(1, host.activeProcesses().size());
    }
}
