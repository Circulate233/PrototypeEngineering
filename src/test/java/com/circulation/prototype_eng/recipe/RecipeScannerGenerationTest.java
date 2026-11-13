package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.RecipeController;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanPreparation;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScannerGenerationTest {
    private static final ScanLimits LIMITS = new ScanLimits(32, 32);
    private static final KeyType TYPE = new KeyType("scanner_generation");
    private static final Key A = new Key(TYPE, "a");
    private static final Key B = new Key(TYPE, "b");
    private static final Key OUTPUT = new Key(TYPE, "output");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("scanner_generation", path);
    }

    private static MachineRecipe recipe(String path) {
        return new MachineRecipe(id(path), 3, List.of());
    }

    private static SimpleRecipePool.Entry entry(String path, Key key, long amount) {
        return SimpleRecipePool.Entry.create(id(path), 3, new GenericStack(key, amount), new GenericStack(OUTPUT, 1), Set.of());
    }

    private static RecipeRegistry registry(RecipePool pool) {
        var registry = new RecipeRegistry();
        registry.register(pool);
        registry.freeze();
        return registry;
    }

    private static RecipeRegistry registry(MachineRecipe recipe) {
        return registry(new DefaultRecipePool(id("pool"), "generation.pool", List.of(recipe)));
    }

    private static void replace(RecipeRegistry registry, MachineRecipe next, String revision) {
        registry.install(registry.prepareReplacement(registry.snapshot(), List.of(new RecipeRegistry.Definition(next, revision))));
    }

    private static BufferedResourceHatch hatch(MachineInstance owner) {
        var hatch = new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(TYPE, 100L), () -> {
        });
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        return hatch;
    }

    private static MachineWorkSlots host(RecipeController owner) {
        owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        owner.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        return owner.createRecipeHost(List.of(new WorkSlotDefinition("a"), new WorkSlotDefinition("b")), Set.of());
    }

    /**
     * The real source/computation protocol, sharing each one-credit caller allowance.
     */
    private static RecipeScanner.Result step(RecipeScanner.ScanAttempt attempt, int credits) {
        var budget = new RecipeWorkBudget(credits);
        for (var source : attempt.sources()) {
            if (attempt.resolvingSources()) attempt.finishSourceResolution(source, source.resolveWithinBudget(budget));
            else if (source.state() == ResourceBatch.SourceState.RESERVED) source.applyWithinBudget(budget);
        }
        var computation = attempt.computation();
        if (computation != null && computation.advance(budget)) attempt.finishComputation(computation);
        return attempt.advance(budget, () -> true);
    }

    private static RecipeScanner.Result complete(RecipeScanner.ScanAttempt attempt) {
        RecipeScanner.Result result = null;
        for (int slice = 0; slice < 10000 && !attempt.finished(); slice++) result = step(attempt, 1);
        assertTrue(attempt.finished(), "One-credit source/probe/admission work must finish");
        return Objects.requireNonNull(result);
    }

    @Test
    void everyScannerReportsTheCurrentStableSummaryBeforeItHasVisitedTheNewGeneration() {
        for (RecipeController owner : List.of(new MultithreadController(), new CrossRecipeController())) {
            var old = entry("stable", A, 1);
            var registry = registry(new SimpleRecipePool(id("pool"), "generation.pool", List.of(old)));
            var host = host(owner);
            var first = owner.createRecipeScanner(host, registry.catalog(), () -> 1).follow(registry, null);
            var secondHost = owner.createRecipeHost(List.of(new WorkSlotDefinition("other")), Set.of());
            var second = owner.createRecipeScanner(secondHost, registry.catalog(), () -> 2).follow(registry, null);
            assertTrue(owner.supportsStableFixedInputLowerBounds());
            assertEquals(0, first.scan(LIMITS).candidates());
            var unstable = new MachineRecipe(old.recipe().id(), 3, old.recipe().orderedRequirements(), Set.of(), 1, _ -> {
            });
            replace(registry, unstable, "v2");
            assertFalse(first.supportsStableFixedInputLowerBounds());
            assertFalse(second.supportsStableFixedInputLowerBounds());
            assertFalse(owner.supportsStableFixedInputLowerBounds());
            assertTrue(first.scan(LIMITS).candidates() > 0, "Unknown lower bounds must reach the real scan path");
            replace(registry, entry("stable", A, 1).recipe(), "v3");
            assertTrue(owner.supportsStableFixedInputLowerBounds());
            assertEquals(0, first.scan(LIMITS).candidates());
        }
    }

    @Test
    void bothControllersUseRebuiltSimplePoolPostingsSynchronouslyAndAcrossOneCreditYields() {
        for (boolean budgeted : List.of(false, true)) {
            for (RecipeController owner : List.of(new MultithreadController(), new CrossRecipeController())) {
                var old = entry("changed_input", A, 9);
                var next = entry("changed_input", B, 1);
                var registry = registry(new SimpleRecipePool(id("pool"), "generation.pool", List.of(old)));
                var host = host(owner);
                var input = Objects.requireNonNull(hatch(owner).input());
                assertEquals(1, input.insert(B, 1, Actionable.MODULATE));
                var scanner = owner.createRecipeScanner(host, registry.catalog(), () -> 3).follow(registry, null);
                assertEquals(0, scanner.scan(LIMITS).candidates());
                replace(registry, next.recipe(), "v2");
                var result = budgeted ? complete(scanner.beginScan(LIMITS)) : scanner.scan(LIMITS);
                assertEquals(RecipeScanner.Status.ADMITTED, result.status());
                assertSame(next.recipe(), host.activeProcesses().getFirst().recipe());
                assertEquals(1, input.availableAmount(B), "Admission probes retain the original rollback behavior");
            }
        }
    }

    @Test
    void rebuildableExternalIndexesUseNewKeysAndQuantitiesWhileUnknownOldEmptyOpinionsExpire() {
        for (boolean rebuild : List.of(false, true)) {
            var old = entry("external", A, 9);
            var next = entry("external", B, 1);
            var registry = registry(old.recipe());
            var owner = new MachineInstance();
            var input = Objects.requireNonNull(hatch(owner).input());
            input.insert(B, 1, Actionable.MODULATE);
            var host = new MachineWorkSlots(owner, List.of());
            int[] builds = {0};
            RecipeCandidateIndex empty = (_, _, _) -> List.of();
            var scanner = new RecipeScanner(host, registry.catalog(), RecipeProcessFactory.STANDARD,
                List.of(), List.of(empty), _ -> false, () -> 4);
            if (rebuild) scanner.follow(registry, null, catalog -> {
                builds[0]++;
                return List.of(ResourceRecipeIndex.forFixedInputs(catalog, _ -> true));
            });
            else scanner.follow(registry, null);
            assertEquals(RecipeScanner.Status.EXHAUSTED, scanner.scan(LIMITS).status());
            replace(registry, next.recipe(), "v2");
            assertEquals(RecipeScanner.Status.ADMITTED, complete(scanner.beginScan(LIMITS)).status());
            assertSame(next.recipe(), host.activeProcesses().getFirst().recipe());
            assertEquals(rebuild ? 2 : 0, builds[0]);
        }
        var old = recipe("explicit_empty");
        var registry = registry(old);
        var host = new MachineWorkSlots(new MachineInstance(), List.of());
        var scanner = new RecipeScanner(host, registry.catalog(), () -> 4)
            .follow(registry, null, _ -> List.of((_, _, _) -> List.of()));
        replace(registry, recipe("explicit_empty"), "v2");
        assertEquals(RecipeScanner.Status.EXHAUSTED, scanner.scan(LIMITS).status(), "A rebuilt empty opinion still rejects");
    }

    @Test
    void externalIndexRebuildingRejectsScanAttemptAndResetReentryDuringBindingAndRefresh() {
        var old = recipe("reentrant_index");
        var next = recipe("reentrant_index");
        var registry = registry(old);
        var host = new MachineWorkSlots(new MachineInstance(), List.of());
        var scanner = new RecipeScanner(host, registry.catalog(), () -> 4);
        int[] builds = {0};
        scanner.follow(registry, null, _ -> {
            builds[0]++;
            assertThrows(IllegalStateException.class, () -> scanner.scan(LIMITS));
            assertThrows(IllegalStateException.class, () -> scanner.beginScan(LIMITS));
            assertThrows(IllegalStateException.class, scanner::reset);
            assertNull(scanner.pendingScan());
            return List.of();
        });
        replace(registry, next, "v2");
        assertEquals(RecipeScanner.Status.ADMITTED, complete(scanner.beginScan(LIMITS)).status());
        assertEquals(2, builds[0]);
        assertSame(next, host.activeProcesses().getFirst().recipe());
    }

    @Test
    void oldPureFactoryProgressIsDiscardedAcrossActiveAndReleasedYieldsForBothControllers() {
        for (boolean release : List.of(false, true)) {
            for (RecipeController owner : List.of(new MultithreadController(), new CrossRecipeController())) {
                var old = recipe("yielded");
                var next = recipe("yielded");
                var registry = registry(old);
                var host = host(owner);
                int[] oldAdvances = {0};
                var factory = new RecipeProcessFactory() {
                    @Override
                    public RecipeProcess create(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                        return new RecipeProcess(machine, recipe, seed, tuning);
                    }

                    @Override
                    @SuppressWarnings("ReturnOfInnerClass") // Retains this exact unpublished fixture candidate.
                    public Creation beginCreation(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                        return new Creation() {
                            private RecipeProcess result;

                            @Override
                            public boolean advance(RecipeWorkBudget budget) {
                                if (recipe == old) {
                                    oldAdvances[0]++;
                                    return false;
                                }
                                if (result == null && budget.take()) result = create(machine, recipe, seed, tuning);
                                return result != null;
                            }

                            @Override
                            public RecipeProcess result() {
                                return Objects.requireNonNull(result);
                            }
                        };
                    }
                };
                var scanner = (owner instanceof CrossRecipeController
                    ? RecipeScanner.crossRecipe(host, registry.catalog(), factory, List.of(), List.of(), _ -> false, () -> 5, List.of())
                    : RecipeScanner.multithreaded(host, registry.catalog(), factory, List.of(), List.of(), _ -> false, () -> 5, List.of()))
                    .follow(registry, null);
                var first = scanner.beginScan(LIMITS);
                assertEquals(RecipeScanner.Status.YIELDED, step(first, 10000).status());
                assertFalse(first.hasObligations());
                if (release) first.releasePureYield();
                replace(registry, next, "v2");
                if (!release) assertEquals(RecipeScanner.Status.PAUSED, complete(first).status());
                assertEquals(RecipeScanner.Status.ADMITTED, complete(scanner.beginScan(LIMITS)).status());
                assertEquals(1, oldAdvances[0], "New generations must never resume the old pure factory");
                assertSame(next, host.activeProcesses().getFirst().recipe());
                assertFalse(owner.supportsStableFixedInputLowerBounds(), "An arbitrary factory remains unknown after replacement");
            }
        }
    }

    @Test
    void anOldPreparationIsCompensatedBeforeANewGenerationCanStart() {
        var old = recipe("preparation");
        var next = recipe("preparation");
        var registry = registry(old);
        var host = new MachineWorkSlots(new MachineInstance(), List.of());
        int[] acquired = {0};
        int[] undone = {0};
        RecipeScanPreparation preparation = process -> {
            if (process.recipe() != old) return RequirementTransaction.SUCCESS;
            acquired[0]++;
            return new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    throw new AssertionError("A probe cannot commit preparation");
                }

                @Override
                public void rollback() {
                    undone[0]++;
                }
            };
        };
        var scanner = new RecipeScanner(host, registry.catalog(), RecipeProcessFactory.STANDARD,
            List.of(), List.of(), _ -> false, () -> 6, List.of(preparation)).follow(registry, null);
        var attempt = scanner.beginScan(LIMITS);
        for (int slice = 0; slice < 1000 && acquired[0] == 0; slice++) step(attempt, 1);
        assertEquals(1, acquired[0]);
        assertTrue(attempt.hasObligations());
        replace(registry, next, "v2");
        assertThrows(IllegalStateException.class, scanner::reset);
        assertThrows(IllegalStateException.class, attempt::releasePureYield);
        assertEquals(RecipeScanner.Status.PAUSED, complete(attempt).status());
        assertEquals(1, undone[0]);
        assertTrue(host.activeProcesses().isEmpty());
        assertEquals(RecipeScanner.Status.ADMITTED, complete(scanner.beginScan(LIMITS)).status());
        assertSame(next, host.activeProcesses().getFirst().recipe());
        assertEquals(1, acquired[0]);
    }

    @Test
    void anUnknownOldRollbackKeepsItsHandleAndFaultEvenAfterDefinitionReplacement() {
        var old = recipe("unknown");
        var registry = registry(old);
        var host = new MachineWorkSlots(new MachineInstance(), List.of());
        int[] acquired = {0};
        int[] rollbackCalls = {0};
        var proof = new ReversibleTransaction.Resolution[]{ReversibleTransaction.Resolution.UNKNOWN};
        RecipeScanPreparation preparation = _ -> {
            acquired[0]++;
            return new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                }

                @Override
                public void rollback() {
                    rollbackCalls[0]++;
                    throw new IllegalStateException("Rollback confirmation pending");
                }

                @Override
                public Resolution resolution() {
                    return proof[0];
                }
            };
        };
        var scanner = new RecipeScanner(host, registry.catalog(), RecipeProcessFactory.STANDARD,
            List.of(), List.of(), _ -> false, () -> 7, List.of(preparation)).follow(registry, null);
        var attempt = scanner.beginScan(LIMITS);
        for (int slice = 0; slice < 1000 && acquired[0] == 0; slice++) step(attempt, 1);
        replace(registry, recipe("unknown"), "v2");
        assertThrows(IllegalStateException.class, () -> step(attempt, 10000));
        assertSame(attempt, scanner.pendingScan());
        assertTrue(attempt.hasObligations());
        assertEquals(RecipeScanner.Status.YIELDED, step(attempt, 10000).status());
        assertThrows(IllegalStateException.class, () -> scanner.beginScan(LIMITS));
        assertEquals(1, rollbackCalls[0]);
        proof[0] = ReversibleTransaction.Resolution.ROLLED_BACK;
        assertEquals(RecipeScanner.Status.PAUSED, complete(attempt).status());
        assertEquals(1, rollbackCalls[0]);
        assertEquals(1, acquired[0]);
        assertTrue(host.activeProcesses().isEmpty());
        assertThrows(IllegalStateException.class, scanner::reset, "Replacement cannot clear the original scanner fault");
    }

    @Test
    void replacementInsideFinalCapacityCaptureRejectsOldSynchronousAndBudgetedAdmission() {
        for (boolean budgeted : List.of(false, true)) {
            var old = recipe("final_gate");
            var next = recipe("final_gate");
            var registry = registry(old);
            var owner = new MachineInstance();
            var host = new MachineWorkSlots(owner, List.of());
            boolean[] created = {false};
            boolean[] published = {false};
            owner.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
            owner.attributes().addModifier(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, new AttributeModifier() {
                @Override
                public String id() {
                    return "publish_after_probe";
                }

                @Override
                public Operation operation() {
                    return Operation.ADDITION;
                }

                @Override
                public double apply(double base, double current) {
                    if (created[0] && !published[0]) {
                        published[0] = true;
                        replace(registry, next, "v2");
                    }
                    return current;
                }
            });
            var scanner = new RecipeScanner(host, registry.catalog(), (machine, recipe, seed, tuning) -> {
                created[0] = true;
                return new RecipeProcess(machine, recipe, seed, tuning);
            }, List.of(), List.of(), _ -> false, () -> 8).follow(registry, null);
            assertEquals(1, host.capacity(), "The fixture must reach a candidate and its final capacity capture");
            if (budgeted) complete(scanner.beginScan(LIMITS));
            else scanner.scan(LIMITS);
            assertTrue(created[0], "The factory must run before the final capacity callback publishes");
            assertTrue(published[0]);
            assertTrue(host.activeProcesses().isEmpty(), "The exact generation is rechecked after final extension callbacks");
            assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(LIMITS).status());
            assertSame(next, host.activeProcesses().getFirst().recipe());
        }
    }

    @Test
    void replacementFromTheLastOwnerPermissionCannotPassTheFinalDefinitionCheck() {
        for (boolean budgeted : List.of(false, true)) {
            var old = recipe("final_owner_gate");
            var next = recipe("final_owner_gate");
            var registry = registry(old);
            boolean[] created = {false};
            boolean[] finalCapacity = {false};
            boolean[] published = {false};
            int[] ownerChecks = {0};
            var owner = new MachineInstance() {
                @Override
                public boolean recipeWorkAllowed() {
                    if (finalCapacity[0] && ++ownerChecks[0] == 3) {
                        published[0] = true;
                        replace(registry, next, "v2");
                    }
                    return true;
                }
            };
            owner.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
            owner.attributes().addModifier(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, new AttributeModifier() {
                @Override
                public String id() {
                    return "mark_final_capacity";
                }

                @Override
                public Operation operation() {
                    return Operation.ADDITION;
                }

                @Override
                public double apply(double base, double current) {
                    if (created[0]) finalCapacity[0] = true;
                    return current;
                }
            });
            var host = new MachineWorkSlots(owner, List.of());
            var scanner = new RecipeScanner(host, registry.catalog(), (machine, recipe, seed, tuning) -> {
                created[0] = true;
                return new RecipeProcess(machine, recipe, seed, tuning);
            }, List.of(), List.of(), _ -> false, () -> 8).follow(registry, null);
            if (budgeted) {
                var attempt = scanner.beginScan(LIMITS);
                step(attempt, 10000);
                if (!attempt.finished()) complete(attempt);
            } else scanner.scan(LIMITS);
            assertTrue(created[0]);
            assertTrue(published[0], "Publish in the last owner gate after the candidate's final capacity capture");
            assertTrue(host.activeProcesses().isEmpty(), "An earlier generation check cannot authorize a later owner callback");
            assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(LIMITS).status());
            assertSame(next, host.activeProcesses().getFirst().recipe());
        }
    }

    @Test
    void persistentFollowingRequiresOnlyItsActualCandidateBeforeCallingTheFactory() {
        var saved = recipe("saved");
        var independent = recipe("independent");
        var codecs = new RecipeProcessCodecs(List.of(RecipeProcessNbt.builder(saved, "v1").build()));
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(id("saved_pool"), "generation.saved", List.of(saved)));
        registry.register(new DefaultRecipePool(id("independent_pool"), "generation.independent", List.of(independent)));
        registry.bindProcessCodecs(codecs);
        registry.freeze();
        for (boolean persistent : List.of(false, true)) {
            var owner = new MachineInstance() {
                @Override
                public List<RecipePool> getRecipePools(RecipeCatalog catalog) {
                    return catalog.pools().stream().filter(pool -> pool.id().equals(id("independent_pool"))).toList();
                }
            };
            var host = new MachineWorkSlots(owner, List.of());
            int[] factories = {0};
            var scanner = new RecipeScanner(host, registry.catalog(), (machine, recipe, seed, tuning) -> {
                factories[0]++;
                return new RecipeProcess(machine, recipe, seed, tuning);
            }, List.of(), List.of(), _ -> false, () -> 9).follow(registry, persistent ? codecs : null);
            if (persistent) {
                scanner.validateProcessCodecs(codecs);
                assertThrows(IllegalArgumentException.class, () -> scanner.scan(LIMITS));
                assertEquals(0, factories[0]);
            } else {
                assertThrows(IllegalArgumentException.class, () -> scanner.validateProcessCodecs(codecs));
                assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(LIMITS).status());
                assertSame(independent, host.activeProcesses().getFirst().recipe());
            }
        }
    }

    @Test
    void standardFollowingComponentRetainsTheControllerFactoryAndOldSameIdThreadOccupancy() {
        var old = new MachineRecipe(id("component"), 3, List.of(), Set.of(), 1);
        var next = new MachineRecipe(old.id(), 3, List.of(), Set.of(), 1);
        var codecs = new RecipeProcessCodecs(List.of(RecipeProcessNbt.builder(old, "v1").build()));
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(id("pool"), "generation.pool", List.of(old)));
        registry.bindProcessCodecs(codecs);
        registry.freeze();
        int[] factoryCalls = {0};
        var owner = new MultithreadController() {
            @Override
            public RecipeScanner createRecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
                factoryCalls[0]++;
                return super.createRecipeScanner(host, catalog, seeds);
            }
        };
        var host = host(owner);
        owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
        owner.components().put(MachineWorkSlots.TYPE, host);
        var component = RecipeScanning.component(id("scanner"), registry, LIMITS);
        var scanner = component.create(owner);
        assertEquals(1, factoryCalls[0]);
        scanner.validateProcessCodecs(codecs);
        assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(LIMITS).status());
        var oldProcess = host.activeProcesses().getFirst();
        registry.install(registry.prepareReplacement(registry.snapshot(), List.of(new RecipeRegistry.Definition(next, "v2")),
            List.of(RecipeProcessNbt.builder(next, "v2").build())));
        scanner.scan(LIMITS);
        assertEquals(1, host.activeProcesses().size(), "Same-ID thread occupancy spans definition generations");
        assertSame(old, oldProcess.recipe());
        assertTrue(host.detach(oldProcess));
        assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(LIMITS).status());
        assertSame(next, host.activeProcesses().getFirst().recipe());
        assertEquals(1, factoryCalls[0]);
        assertNull(scanner.pendingScan());
    }
}
