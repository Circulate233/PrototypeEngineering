package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScannerBudgetTest {
    private static ResourceLocation id(String path) {
        return new ResourceLocation("scanner_budget_test", path);
    }

    private static RecipeCatalog catalog(List<MachineRecipe> recipes) {
        return new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "scanner.budget", recipes)));
    }

    private static AtomicInteger capacityModifiers(MachineInstance machine) {
        var calls = new AtomicInteger();
        for (int index = 0; index < 256; index++) {
            String name = "capacity_" + index;
            machine.attributes().addModifier(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, new AttributeModifier() {
                @Override
                public String id() {
                    return name;
                }

                @Override
                public Operation operation() {
                    return Operation.ADDITION;
                }

                @Override
                public double apply(double base, double current) {
                    calls.incrementAndGet();
                    return current;
                }
            });
        }
        return calls;
    }

    @Test
    void aOneUnitScanMetersTheActualCapacityChainBeforeRejectingAnEmptyHostAsFull() {
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 0);
        var calls = capacityModifiers(machine);
        var host = new MachineWorkSlots(machine, List.of());
        var factories = new AtomicInteger();
        var scanner = new RecipeScanner(host, catalog(List.of(new MachineRecipe(id("capacity_blocked"), 1, List.of()))), (owner, recipe, seed, tuning) -> {
            factories.incrementAndGet();
            return new RecipeProcess(owner, recipe, seed, tuning);
        }, List.of(), List.of(), _ -> false, () -> 17);
        var attempt = scanner.beginScan(new ScanLimits(1, 1));
        assertEquals(RecipeScanner.Status.YIELDED, attempt.advance(new RecipeWorkBudget(1), () -> true).status());
        assertEquals(0, calls.get(), "The empty slot visit exhausts the first slice before capacity modifiers run");
        RecipeScanner.Result result = null;
        for (int step = 0; step < 5000 && !attempt.finished(); step++) {
            int before = calls.get();
            result = attempt.advance(new RecipeWorkBudget(1), () -> true);
            assertTrue(calls.get() - before <= 1);
        }
        assertTrue(attempt.finished());
        assertEquals(RecipeScanner.Status.FULL, Objects.requireNonNull(result).status());
        assertEquals(256, calls.get());
        assertEquals(0, factories.get());
        assertTrue(host.activeProcesses().isEmpty());
    }

    @Test
    void oneUnitAdmissionMetersCapacityAndAttachesOnlyAfterTheOriginalCaptureCompletes() {
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
        var calls = capacityModifiers(machine);
        var host = new MachineWorkSlots(machine, List.of());
        var process = new RecipeProcess(machine, new MachineRecipe(id("capacity_admitted"), 1, List.of()), 17);
        var admission = host.beginAdmission("main", process);
        for (int step = 0; step < 5000 && !admission.finished(); step++) {
            int before = calls.get();
            admission.advance(new RecipeWorkBudget(1), () -> true);
            assertTrue(calls.get() - before <= 1);
            if (!admission.finished()) assertNull(process.host());
        }
        assertTrue(admission.finished());
        assertTrue(admission.admitted());
        assertEquals(256, calls.get());
        assertSame(process, host.requireSlot("main").process());
    }

    @Test
    void authorizationMutationInvalidatesTheCompletedAdmissionCapacityBeforeAnyAttachment() {
        var machine = new MachineInstance();
        var calls = capacityModifiers(machine);
        var host = new MachineWorkSlots(machine, List.of());
        var process = new RecipeProcess(machine, new MachineRecipe(id("capacity_mutated"), 1, List.of()), 17);
        var admission = host.beginAdmission("main", process);
        boolean[] changed = {false};
        var authorizationCalls = new AtomicInteger();
        for (int step = 0; step < 10000 && !admission.finished(); step++) {
            authorizationCalls.set(0);
            int before = calls.get();
            admission.advance(new RecipeWorkBudget(1), () -> {
                if (authorizationCalls.incrementAndGet() == 2 && !changed[0]) {
                    changed[0] = true;
                    machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 0);
                }
                return true;
            });
            assertTrue(calls.get() - before <= 1);
            assertNull(process.host());
        }
        assertTrue(changed[0]);
        assertTrue(admission.finished());
        assertFalse(admission.admitted());
        assertEquals(512, calls.get(), "The invalidated actual capacity chain is recaptured under the original admission");
        assertTrue(host.activeProcesses().isEmpty());
    }

    @Test
    @SuppressWarnings("ReturnOfInnerClass")
        // Fixture callbacks retain exact acquisition counters.
    void aSingleAttemptVisitsManyBlockedCandidatesAndAdmitsOnceWithoutRecreatingOrRecountingItsProbe() {
        var callbacks = new AtomicInteger();
        var factories = new AtomicInteger();
        var seeds = new AtomicInteger();
        var type = new RecipeRequirementType<Boolean>(id("guard"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                callbacks.incrementAndGet();
                return new RequirementTransaction() {
                    @Override
                    public ProcessResult result() {
                        return new ProcessResult.Blocked("unavailable");
                    }

                    @Override
                    public void commit() {
                    }

                    @Override
                    public void rollback() {
                    }
                };
            }
        });
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < 64; index++)
            recipes.add(new MachineRecipe(id("blocked_" + index), 1, List.of(type.requirement(id("guard_requirement"), true))));
        var accepted = new MachineRecipe(id("accepted"), 1, List.of());
        recipes.add(accepted);
        var machine = new MachineInstance();
        var host = new MachineWorkSlots(machine, List.of());
        var scanner = new RecipeScanner(host, catalog(recipes), (owner, recipe, seed, tuning) -> {
            factories.incrementAndGet();
            return new RecipeProcess(owner, recipe, seed, tuning);
        }, List.of(), List.of(), _ -> false, seeds::incrementAndGet);
        var attempt = scanner.beginScan(new ScanLimits(100, 100));
        int examined = 0;
        int probes = 0;
        RecipeScanner.Result result = null;
        for (int call = 0; call < 10000; call++) {
            int before = callbacks.get();
            result = attempt.advance(new RecipeWorkBudget(1), () -> true);
            assertTrue(callbacks.get() - before <= 1);
            assertTrue(result.candidates() >= examined);
            assertTrue(result.probes() >= probes);
            examined = result.candidates();
            probes = result.probes();
            if (attempt.finished()) break;
            assertEquals(RecipeScanner.Status.YIELDED, Objects.requireNonNull(result).status());
            assertTrue(host.activeProcesses().isEmpty());
        }
        assertTrue(attempt.finished(), "Original attempt progress: " + result + ", factories=" + factories.get() + ", callbacks=" + callbacks.get());
        assertEquals(RecipeScanner.Status.ADMITTED, Objects.requireNonNull(result).status());
        assertEquals(65, result.candidates());
        assertEquals(65, result.probes());
        assertEquals(65, factories.get());
        assertEquals(65, seeds.get());
        assertEquals(64, callbacks.get());
        assertSame(accepted, Objects.requireNonNull(result.process()).recipe());
        assertSame(result.process(), host.requireSlot("main").process());
    }

    @Test
    void aPreparationPauseCannotReleaseTheSearchOrStartAnotherScanUntilExactCancellationCleanup() {
        var prepared = new AtomicInteger();
        var undone = new AtomicInteger();
        var initialized = new AtomicInteger();
        var machine = new MachineInstance();
        var host = new MachineWorkSlots(machine, List.of());
        var recipe = new MachineRecipe(id("prepared"), 1, List.of());
        var scanner = new RecipeScanner(host, catalog(List.of(recipe)), (owner, definition, seed, tuning) -> {
            initialized.incrementAndGet();
            return new RecipeProcess(owner, definition, seed, tuning);
        }, List.of(), List.of(), _ -> false, () -> 17, List.of(_ -> {
            prepared.incrementAndGet();
            return new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void rollback() {
                    undone.incrementAndGet();
                }

                @Override
                public void commit() {
                    throw new AssertionError("Preparation cannot commit");
                }

                @Override
                public void complete() {
                    throw new AssertionError("Preparation cannot complete");
                }
            };
        }));
        var attempt = scanner.beginScan(new ScanLimits(1, 1));
        for (int call = 0; call < 100 && prepared.get() == 0; call++)
            attempt.advance(new RecipeWorkBudget(1), () -> true);
        assertEquals(1, prepared.get());
        assertTrue(attempt.hasObligations());
        assertThrows(IllegalStateException.class, attempt::releasePureYield);
        assertThrows(IllegalStateException.class, () -> scanner.beginScan(new ScanLimits(1, 1)));
        attempt.cancel();
        for (int call = 0; call < 100 && !attempt.finished(); call++)
            attempt.advance(new RecipeWorkBudget(1), () -> false);
        assertTrue(attempt.finished());
        assertFalse(attempt.hasObligations());
        assertEquals(1, initialized.get());
        assertEquals(1, undone.get());
        assertTrue(host.activeProcesses().isEmpty());
    }

    @Test
    void aLargeEmptyGroupScopeActuallyYieldsBeforeExhaustionAndTheSynchronousScanRemainsCompatible() {
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < 500; index++)
            recipes.add(new MachineRecipe(id("foreign_" + index), 1, List.of(), Set.of(id("other"))));
        var missingGroups = new ObjectLinkedOpenHashSet<ResourceLocation>();
        for (int index = 0; index < 500; index++) missingGroups.add(id("missing_group_" + index));
        var machine = new MachineInstance();
        var host = new MachineWorkSlots(machine, List.of(new WorkSlotDefinition("empty", missingGroups)));
        var scanner = new RecipeScanner(host, catalog(recipes), () -> 17);
        var attempt = scanner.beginScan(new ScanLimits(1, 1));
        assertEquals(RecipeScanner.Status.YIELDED, attempt.advance(new RecipeWorkBudget(0), () -> true).status());
        for (int call = 0; call < 10; call++)
            assertEquals(RecipeScanner.Status.YIELDED, attempt.advance(new RecipeWorkBudget(1), () -> true).status());
        RecipeScanner.Result result = null;
        for (int call = 0; call < 4 * missingGroups.size() && !attempt.finished(); call++)
            result = attempt.advance(new RecipeWorkBudget(1), () -> true);
        assertTrue(attempt.finished());
        assertEquals(RecipeScanner.Status.EXHAUSTED, Objects.requireNonNull(result).status());
        assertEquals(0, result.candidates());
        assertEquals(0, result.probes());
        assertEquals(RecipeScanner.Status.EXHAUSTED, scanner.scan(new ScanLimits(1, 1)).status());
        assertTrue(host.activeProcesses().isEmpty());
        var smallOwner = new MachineInstance();
        var smallHost = new MachineWorkSlots(smallOwner, List.of(new WorkSlotDefinition("small", Set.of(id("missing_group_0")))));
        var smallScanner = new RecipeScanner(smallHost, catalog(recipes), () -> 17);
        var small = smallScanner.beginScan(new ScanLimits(1, 1));
        RecipeScanner.Result smallResult = null;
        for (int call = 0; call < 32 && !small.finished(); call++)
            smallResult = small.advance(new RecipeWorkBudget(1), () -> true);
        assertTrue(small.finished(), "A single missing group uses the frozen posting table without traversing foreign recipes");
        assertEquals(RecipeScanner.Status.EXHAUSTED, Objects.requireNonNull(smallResult).status());
        assertEquals(0, smallResult.candidates());
        assertEquals(0, smallResult.probes());
    }
}
