package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.RecipeController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismConstraint;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeControllersTest {
    private static final ScanLimits LIMITS = new ScanLimits(32, 32);

    private static ResourceLocation id(String path) {
        return new ResourceLocation("controllers_test", path);
    }

    private static MachineRecipe recipe(String name, int quantity, int threads, Set<ResourceLocation> groups) {
        return new MachineRecipe(id(name), 4, List.of(), groups, threads, RecipeParallelismSelection.fixed(quantity));
    }

    private static RecipeCatalog catalog(MachineRecipe... recipes) {
        return new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "controller.pool", List.of(recipes))));
    }

    private static void allowance(MachineInstance owner, int parallel, int threads) {
        owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallel);
        owner.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, threads);
    }

    private static RecipeScanner.Result retained(RecipeScanner scanner) {
        var attempt = scanner.beginScan(LIMITS);
        RecipeScanner.Result result = null;
        for (int step = 0; step < 10000 && !attempt.finished(); step++) {
            result = attempt.advance(new RecipeWorkBudget(1), () -> true);
            assertTrue(attempt.sources().isEmpty(), "Free recipes cannot create source obligations");
        }
        assertTrue(attempt.finished(), "One-unit slices must finish the stable scan");
        return Objects.requireNonNull(result);
    }

    @Test
    void independentThreadsCaptureTheirOwnPassAndGroupInSynchronousAndRetainedPaths() {
        for (boolean sliced : List.of(false, true)) {
            int[] selections = {0};
            var owner = new MultithreadController() {
                @Override
                public List<RecipePool> getRecipePools(RecipeCatalog directory) {
                    selections[0]++;
                    return directory.pools();
                }
            };
            allowance(owner, 2, 2);
            var left = recipe("left", 1, 1, Set.of(id("left_group")));
            var right = recipe("right", 1, 1, Set.of(id("right_group")));
            var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("left", left.recipeGroups()),
                new WorkSlotDefinition("right", right.recipeGroups())), Set.of());
            long[] seed = {40};
            var scanner = owner.createRecipeScanner(host, catalog(left, right), () -> ++seed[0]);
            var outcome = sliced ? retained(scanner) : scanner.scan(LIMITS);
            assertEquals(RecipeScanner.Status.ADMITTED, outcome.status());
            assertSame(left, Objects.requireNonNull(host.requireSlot("left").process()).recipe());
            assertSame(right, Objects.requireNonNull(host.requireSlot("right").process()).recipe());
            assertEquals(41, Objects.requireNonNull(host.requireSlot("left").process()).seed());
            assertEquals(42, Objects.requireNonNull(host.requireSlot("right").process()).seed());
            assertEquals(2, selections[0]);
            assertEquals(0, host.sharedUsage(left).available());
        }
    }

    @Test
    void eachThreadMayRunTheSameRecipeWithinItsGlobalLogicalThreadLimit() {
        var owner = new MultithreadController();
        allowance(owner, 3, 3);
        var recipe = recipe("repeat", 1, 2, Set.of());
        var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("a"), new WorkSlotDefinition("b"),
            new WorkSlotDefinition("c")), Set.of());
        retained(owner.createRecipeScanner(host, catalog(recipe), () -> 1));
        assertEquals(2, host.activeProcesses().size());
        assertEquals(2, owner.recipeThreadCount(recipe.id()));
        assertNull(host.requireSlot("c").process());
    }

    @Test
    void completedThreadRetriesItsLastRecipeBeforeDiscoveryAndFallsBackAfterOneFailedProbe() {
        for (boolean sliced : List.of(false, true)) {
            var owner = new MultithreadController();
            allowance(owner, 1, 1);
            var first = recipe("first", 1, 1, Set.of());
            var previous = recipe("previous", 1, 1, Set.of());
            var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("main")), Set.of());
            var completed = new RecipeProcess(owner, previous, 1);
            assertTrue(host.start(completed));
            for (int tick = 0; tick < 4; tick++) host.tickProcesses();
            assertTrue(host.activeProcesses().isEmpty());
            assertEquals(previous.id(), host.requireSlot("main").lastCompletedRecipe());
            int[] discoveries = {0};
            var pool = new RecipePool() {
                @Override public ResourceLocation id() { return RecipeControllersTest.id("retry_pool"); }
                @Override public String translationKey() { return "retry.pool"; }
                @Override public List<MachineRecipe> recipes() { return List.of(first, previous); }
                @Override public List<MachineType> machines() { return List.of(); }
                @Override public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
                    throw new AssertionError("The fixture uses an already canonical directory");
                }
                @Override public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
                    discoveries[0]++;
                    return null;
                }
            };
            var created = new ObjectArrayList<MachineRecipe>();
            boolean[] rejectPrevious = {false};
            RecipeProcessFactory factory = (machine, recipe, seed, tuning) -> {
                created.add(recipe);
                return new RecipeProcess(machine, recipe, seed, tuning);
            };
            var constraint = new RecipeParallelismConstraint() {
                @Override
                public ProcessResult test(RecipeProcess process) {
                    return rejectPrevious[0] && process.recipe() == previous ? RecipeText.Cancelled.blocked() : ProcessResult.SUCCESS;
                }
            };
            var scanner = RecipeScanner.multithreaded(host, new RecipeCatalog(List.of(pool)), factory,
                List.of(constraint),
                List.of(), _ -> false, () -> 7, List.of());
            var retried = sliced ? retained(scanner) : scanner.scan(LIMITS);
            assertEquals(RecipeScanner.Status.ADMITTED, retried.status());
            assertSame(previous, Objects.requireNonNull(retried.process()).recipe());
            assertEquals(List.of(previous), created);
            assertEquals(0, discoveries[0]);
            assertTrue(host.cancel(Objects.requireNonNull(retried.process())));
            assertEquals(previous.id(), host.requireSlot("main").lastCompletedRecipe());
            rejectPrevious[0] = true;
            created.clear();
            var fallback = sliced ? retained(scanner) : scanner.scan(LIMITS);
            assertEquals(RecipeScanner.Status.ADMITTED, fallback.status());
            assertSame(first, Objects.requireNonNull(fallback.process()).recipe());
            assertEquals(List.of(previous, first), created);
            assertEquals(1, discoveries[0]);
        }
    }

    @Test
    void crossRecipeKeepsOnePassAndCreatesEnoughSlotsToSpendSharedBalance() {
        for (boolean sliced : List.of(false, true)) {
            int[] selections = {0};
            var owner = new CrossRecipeController() {
                @Override
                public List<RecipePool> getRecipePools(RecipeCatalog directory) {
                    selections[0]++;
                    return directory.pools();
                }
            };
            allowance(owner, 3, 0);
            var a = recipe("a", 1, 1, Set.of());
            var b = recipe("b", 1, 1, Set.of());
            var c = recipe("c", 1, 1, Set.of());
            var host = owner.createRecipeHost(List.of(), Set.of());
            assertTrue(host.slots().isEmpty());
            var scanner = owner.createRecipeScanner(host, catalog(a, b, c), () -> 7);
            assertEquals(RecipeScanner.Status.ADMITTED, (sliced ? retained(scanner) : scanner.scan(LIMITS)).status());
            assertEquals(List.of(a, b, c), host.activeProcesses().stream().map(RecipeProcess::recipe).toList());
            assertEquals(3, host.slots().size());
            assertEquals(1, selections[0]);
            assertEquals(0, host.sharedUsage(a).available());
        }
    }

    @Test
    void crossRecipeCompletesAnotherRoundWhenOneRecipeCanLegallyUseTheRemainder() {
        for (boolean sliced : List.of(false, true)) {
            var owner = new CrossRecipeController();
            allowance(owner, 2, 0);
            var recipe = recipe("another_round", 1, 2, Set.of());
            var host = owner.createRecipeHost(List.of(), Set.of());
            var scanner = owner.createRecipeScanner(host, catalog(recipe), () -> 5);
            if (sliced) retained(scanner);
            else scanner.scan(LIMITS);
            assertEquals(2, host.activeProcesses().size());
            assertEquals(2, owner.recipeThreadCount(recipe.id()));
            assertEquals(0, host.sharedUsage(recipe).available());
        }
    }

    @Test
    void finalEventQuantityMayExceedTheAllowanceAndBlocksLaterAdmissions() {
        for (boolean sliced : List.of(false, true)) {
            for (var owner : List.of(new CrossRecipeController(), new MultithreadController())) {
                allowance(owner, 2, 2);
                var over = recipe("over", 5, 1, Set.of());
                var later = recipe("later", 1, 1, Set.of());
                var host = owner.createRecipeHost(owner instanceof CrossRecipeController ? List.of()
                    : List.of(new WorkSlotDefinition("a"), new WorkSlotDefinition("b")), Set.of());
                var scanner = owner.createRecipeScanner(host, catalog(over, later), () -> 2);
                assertEquals(RecipeScanner.Status.ADMITTED, (sliced ? retained(scanner) : scanner.scan(LIMITS)).status());
                assertEquals(1, host.activeProcesses().size());
                assertEquals(5, RecipeParallelism.effective(host.activeProcesses().getFirst()));
                assertEquals(5, host.sharedUsage(later).occupied());
                assertEquals(0, host.sharedUsage(later).available());
            }
        }
    }

    @Test
    void separateHostsSpendTheSameRemainingParallelBalance() {
        for (boolean sliced : List.of(false, true)) {
            var owner = new CrossRecipeController();
            allowance(owner, 3, 1);
            var occupiedRecipe = recipe("occupied", 1, 1, Set.of());
            var existing = new MachineWorkSlots(owner, List.of(new WorkSlotDefinition("existing")));
            var occupied = new RecipeProcess(owner, occupiedRecipe, 1);
            occupied.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            assertTrue(existing.start(occupied));
            var candidate = new MachineRecipe(id("remaining"), 4, List.of());
            var scanning = owner.createRecipeHost(List.of(), Set.of());
            var scanner = owner.createRecipeScanner(scanning, catalog(candidate), () -> 2);
            if (sliced) retained(scanner);
            else scanner.scan(LIMITS);
            assertEquals(2, RecipeParallelism.effective(scanning.activeProcesses().getFirst()));
            assertEquals(3, scanning.sharedUsage(candidate).occupied());
            assertEquals(0, existing.sharedUsage(candidate).available());
        }
    }

    @Test
    void pureYieldLetsAnotherThreadRunWhileRetainingTheFirstThreadsCursorAndSeed() {
        for (boolean sliced : List.of(false, true)) {
            for (boolean bounded : List.of(false, true)) {
                var owner = new MultithreadController();
                allowance(owner, 2, 2);
                var blocked = recipe("blocked", 1, 1, Set.of(id("a")));
                var nextA = recipe("next_a", 1, 1, Set.of(id("a")));
                var ready = recipe("ready", 1, 1, Set.of(id("b")));
                var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("a", Set.of(id("a"))),
                    new WorkSlotDefinition("b", Set.of(id("b")))), Set.of());
                long[] seeds = {0};
                var constraint = new RecipeParallelismConstraint() {
                    @Override
                    public int upperBound(MachineInstance machine, MachineRecipe recipe, int currentLimit) {
                        return bounded ? Math.min(1, currentLimit) : currentLimit;
                    }

                    @Override
                    public ProcessResult test(RecipeProcess process) {
                        return process.recipe() == blocked ? RecipeText.Cancelled.blocked() : ProcessResult.SUCCESS;
                    }
                };
                var scanner = RecipeScanner.multithreaded(host, catalog(blocked, nextA, ready), RecipeProcessFactory.STANDARD,
                    List.of(constraint), List.of(), _ -> false, () -> ++seeds[0], List.of());
                for (int tick = 0; tick < (bounded ? 3 : 4); tick++) {
                    if (sliced) {
                        var attempt = scanner.beginScan(new ScanLimits(1, 1));
                        attempt.advance(new RecipeWorkBudget(10000), () -> true);
                        if (!attempt.finished()) {
                            assertFalse(attempt.hasObligations());
                            attempt.releasePureYield();
                        }
                    } else scanner.scan(new ScanLimits(1, 1));
                    if (tick == 0) assertTrue(host.activeProcesses().isEmpty());
                    if (tick == 1) assertSame(ready, Objects.requireNonNull(host.requireSlot("b").process()).recipe());
                }
                assertSame(nextA, Objects.requireNonNull(host.requireSlot("a").process()).recipe());
                assertEquals(2, Objects.requireNonNull(host.requireSlot("b").process()).seed());
                assertEquals(bounded ? 3 : 4, Objects.requireNonNull(host.requireSlot("a").process()).seed());
            }
        }
    }

    @Test
    void eventQuantityDrivesActualStartTickAndEndEnergy() {
        var owner = new CrossRecipeController();
        allowance(owner, 2, 1);
        var energy = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 1, 0, 3, false));
        var recipe = new MachineRecipe(id("energy_recipe"), 1, List.of(energy), Set.of(), 1, RecipeParallelismSelection.fixed(5));
        var input = new EnergyBuffer(200, () -> {
        });
        var output = new EnergyBuffer(200, () -> {
        });
        input.receiveEnergy(100, Actionable.MODULATE);
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(input), List.of(output)));
        var host = owner.createRecipeHost(List.of(), Set.of());
        assertEquals(RecipeScanner.Status.ADMITTED, owner.createRecipeScanner(host, catalog(recipe), () -> 1).scan(LIMITS).status());
        assertEquals(100, input.storedEnergy(), "Probe must restore all simulated energy");
        for (int tick = 0; tick < 3; tick++) host.tickProcesses();
        assertEquals(85, input.storedEnergy());
        assertEquals(15, output.storedEnergy());
        assertTrue(host.activeProcesses().isEmpty());
        assertEquals(0, owner.recipeThreadCount(recipe.id()));
    }

    @Test
    void speculativeAdmissionsAcrossHostsClaimTheRecipeLimitOnlyWhenCompleted() {
        var owner = new MultithreadController();
        allowance(owner, 4, 1);
        var recipe = recipe("one_thread", 1, 1, Set.of());
        var first = new MachineWorkSlots(owner, List.of(new WorkSlotDefinition("a")));
        var second = new MachineWorkSlots(owner, List.of(new WorkSlotDefinition("b")));
        var a = new RecipeProcess(owner, recipe, 1);
        var b = new RecipeProcess(owner, recipe, 2);
        var pendingA = first.beginAdmission("a", a);
        var pendingB = second.beginAdmission("b", b);
        assertFalse(pendingA.advance(new RecipeWorkBudget(1), () -> true));
        assertFalse(pendingB.advance(new RecipeWorkBudget(1), () -> true));
        assertEquals(0, owner.recipeThreadCount(recipe.id()));
        assertTrue(pendingB.advance(new RecipeWorkBudget(1000), () -> true));
        assertTrue(pendingB.admitted());
        assertTrue(pendingA.advance(new RecipeWorkBudget(1000), () -> true));
        assertFalse(pendingA.admitted());
        assertNull(a.host());
        assertSame(second, b.host());
        assertEquals(1, owner.recipeThreadCount(recipe.id()));
        assertTrue(second.cancel(b));
        assertEquals(0, owner.recipeThreadCount(recipe.id()));
        assertTrue(first.startIn("a", a));
    }

    @Test
    void dynamicCodecRoundTripsSeveralProcessesAndRejectsCounterWithoutPublishing() {
        var owner = new CrossRecipeController();
        allowance(owner, 3, 1);
        var a = recipe("saved_a", 1, 1, Set.of());
        var b = recipe("saved_b", 1, 1, Set.of());
        var host = owner.createRecipeHost(List.of(), Set.of());
        owner.createRecipeScanner(host, catalog(a, b), () -> 7).scan(LIMITS);
        var codec = new MachineWorkSlotsNbt("v1", List.of(RecipeProcessNbt.builder(a, "v1").build(), RecipeProcessNbt.builder(b, "v1").build()));
        var saved = codec.encode(host);
        var restoredOwner = new CrossRecipeController();
        allowance(restoredOwner, 1, 0);
        var restored = restoredOwner.createRecipeHost(List.of(), Set.of());
        codec.restore(restored, saved);
        assertEquals(2, restored.activeProcesses().size());
        assertNotSame(host.activeProcesses().getFirst(), restored.activeProcesses().getFirst());
        assertEquals(0, restored.sharedUsage(a).available(), "Reducing the cap preserves existing work");
        var before = restored.snapshot();
        var malformed = saved.copy();
        malformed.setLong("NextSlot", 0);
        assertThrows(IllegalArgumentException.class, () -> codec.restore(restored, malformed));
        assertEquals(before, restored.snapshot());
        restored.restore(restored.snapshot());
        var anotherOwner = new CrossRecipeController();
        var another = anotherOwner.createRecipeHost(List.of(), Set.of());
        codec.restore(another, codec.encode(restored));
        assertEquals(2, another.activeProcesses().size());
        assertEquals(2, another.nextRuntimeSlot());
        another.restoreRuntimeCounter(10);
        another.restoreRuntimeCounter(3);
        assertEquals(10, another.nextRuntimeSlot());
    }

    @Test
    void actualAssemblyFactoriesSelectControllersHostsAndScannersForCreateAndRestore() {
        for (Function<MachineType, MachineInstance> factory : List.<Function<MachineType, MachineInstance>>of(MultithreadController::new, CrossRecipeController::new)) {
            var recipe = recipe("assembled", 1, 2, Set.of());
            var second = recipe("assembled_second", 1, 2, Set.of());
            var directory = catalog(recipe, second);
            var scannerType = RecipeScanning.component(id("scanner"), () -> directory, LIMITS);
            var type = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape"))
                                  .component(MachineWorkSlots.TYPE).component(scannerType).recipePool(id("pool"))
                                  .workSlot(new WorkSlotDefinition("a")).workSlot(new WorkSlotDefinition("b")).build();
            var codec = new MachineWorkSlotsNbt("v1", List.of(RecipeProcessNbt.builder(recipe, "v1").build(), RecipeProcessNbt.builder(second, "v1").build()));
            var assembly = MachineAssembly.builder(type, "v1").instanceFactory(factory).workSlots(codec).recreated(scannerType).build();
            var original = assembly.create(new Host(), new MachineLifecycleRegistry());
            allowance(original, 2, 2);
            var saved = assembly.encode(original);
            var restored = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
            assertEquals(original.getClass(), restored.getClass());
            for (var machine : List.of(original, restored)) {
                var host = Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE));
                var scanner = Objects.requireNonNull(machine.components().get(scannerType));
                assertSame(host, scanner.host());
                assertInstanceOf(RecipeController.class, machine);
                assertEquals(machine instanceof CrossRecipeController, host.dynamicRoster());
                Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
                scanner.scan(LIMITS);
                assertEquals(2, host.activeProcesses().size());
            }
        }
    }

    @Test
    void scheduledTickPreservesEveryScannerAndRejectsSameCallbackOverwrite() {
        var type = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).build();
        var assembly = MachineAssembly.builder(type, "v1").instanceFactory(CrossRecipeController::new).build();
        var machine = assembly.create(new Host(), new MachineLifecycleRegistry());
        var lifecycle = Objects.requireNonNull(machine.lifecycle());
        lifecycle.form(new MachineHatches(List.of(), List.of(), List.of()));
        var directory = catalog(recipe("deferred", 1, 1, Set.of()));
        var first = machine.createRecipeScanner(machine.createRecipeHost(List.of(), Set.of()), directory, () -> 1);
        var second = machine.createRecipeScanner(machine.createRecipeHost(List.of(), Set.of()), directory, () -> 2);
        var execution = Objects.requireNonNull(lifecycle.beginScheduled());
        assertTrue(execution.runComponent(() -> {
            assertTrue(lifecycle.deferRecipeScan(first, LIMITS));
            assertThrows(IllegalStateException.class, () -> lifecycle.deferRecipeScan(second, LIMITS));
        }));
        var firstDeferred = Objects.requireNonNull(execution.deferredRecipeScan());
        assertSame(first, firstDeferred.scanner());
        execution.finishDeferredRecipeScan(firstDeferred);
        assertTrue(execution.runComponent(() -> assertTrue(lifecycle.deferRecipeScan(second, LIMITS))));
        var secondDeferred = Objects.requireNonNull(execution.deferredRecipeScan());
        assertSame(second, secondDeferred.scanner());
        execution.finishDeferredRecipeScan(secondDeferred);
        assertTrue(execution.runComponent(() -> assertTrue(lifecycle.deferRecipeScan(first, LIMITS))));
        assertNull(execution.deferredRecipeScan(), "One exact scanner remains deduplicated within the same tick");
        assertTrue(execution.tryFinish());
    }

    @Test
    void oneUnitNewTickYieldsRetainVacancyUsageAndLongPureFactoryCreation() {
        for (boolean multi : List.of(false, true)) {
            var owner = multi ? new MultithreadController() : new CrossRecipeController();
            allowance(owner, 1, 1);
            var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("a")), Set.of());
            var recipe = recipe("tiny_ticks", 1, 1, Set.of());
            int[] creations = {0};
            long[] seeds = {0};
            var factory = new RecipeProcessFactory() {
                @Override
                public RecipeProcess create(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                    return new RecipeProcess(machine, definition, seed, tuning);
                }

                @Override
                @SuppressWarnings("ReturnOfInnerClass")
                // The fixture intentionally returns its retained pure creation cursor.
                public Creation beginCreation(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                    creations[0]++;
                    return new Creation() {
                        private int units;
                        private RecipeProcess result;

                        @Override
                        public boolean advance(RecipeWorkBudget budget) {
                            while (units < 40) {
                                if (!budget.take()) return false;
                                units++;
                            }
                            if (result == null) result = create(machine, definition, seed, tuning);
                            return true;
                        }

                        @Override
                        public RecipeProcess result() {
                            return Objects.requireNonNull(result);
                        }
                    };
                }
            };
            var scanner = multi ? RecipeScanner.multithreaded(host, catalog(recipe), factory, List.of(), List.of(), _ -> false, () -> ++seeds[0], List.of())
                : RecipeScanner.crossRecipe(host, catalog(recipe), factory, List.of(), List.of(), _ -> false, () -> ++seeds[0], List.of());
            RecipeScanner.ScanAttempt pending = null;
            for (int tick = 0; tick < 1000 && host.activeProcesses().isEmpty(); tick++) {
                if (pending == null) pending = scanner.beginScan(LIMITS);
                pending.advance(new RecipeWorkBudget(1), () -> true);
                if (pending.finished()) pending = null;
                else if (!pending.hasObligations()) {
                    pending.releasePureYield();
                    pending = null;
                }
            }
            assertEquals(1, host.activeProcesses().size(), "One-unit ticks must reach admission: multi=" + multi
                + ", creations=" + creations[0] + ", seeds=" + seeds[0] + ", pending=" + (pending != null));
            if (pending != null && !pending.finished()) {
                while (!pending.finished()) pending.advance(new RecipeWorkBudget(1), () -> true);
            }
            assertEquals(1, creations[0], "Pure factory progress survives every new-tick yield");
            assertEquals(1, seeds[0]);
        }
    }

    @Test
    void siblingPureCandidatesDoNotContinuouslyRevokeEachOthersLongCreationAndChecks() {
        var owner = new MultithreadController();
        allowance(owner, 2, 2);
        var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("a"), new WorkSlotDefinition("b")), Set.of());
        var recipe = recipe("long_siblings", 1, 2, Set.of());
        int[] creations = {0};
        int[] checks = {0};
        var factory = new RecipeProcessFactory() {
            @Override
            public RecipeProcess create(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                return new RecipeProcess(machine, definition, seed, tuning);
            }

            @Override
            @SuppressWarnings("ReturnOfInnerClass") // Retained pure fixture creation deliberately spans ticks.
            public Creation beginCreation(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                creations[0]++;
                return new Creation() {
                    private int units;
                    private RecipeProcess result;

                    @Override
                    public boolean advance(RecipeWorkBudget budget) {
                        while (units < 40) {
                            if (!budget.take()) return false;
                            units++;
                        }
                        if (result == null) result = create(machine, definition, seed, tuning);
                        return true;
                    }

                    @Override
                    public RecipeProcess result() {
                        return Objects.requireNonNull(result);
                    }
                };
            }
        };
        var constraint = new RecipeParallelismConstraint() {
            @Override
            @SuppressWarnings("ReturnOfInnerClass") // Retained pure fixture check deliberately spans ticks.
            public Check beginCheck(RecipeProcess process) {
                checks[0]++;
                return new Check() {
                    private int units;
                    private boolean cancelled;

                    @Override
                    public boolean advance(RecipeWorkBudget budget, BooleanSupplier allowed) {
                        if (cancelled || !allowed.getAsBoolean()) {
                            cancelled = true;
                            return true;
                        }
                        while (units < 40) {
                            if (!budget.take()) return false;
                            units++;
                        }
                        return true;
                    }

                    @Override
                    public ProcessResult result() {
                        return cancelled ? RecipeText.Cancelled.blocked() : ProcessResult.SUCCESS;
                    }

                    @Override
                    public void cancel() {
                        cancelled = true;
                    }
                };
            }
        };
        var scanner = RecipeScanner.multithreaded(host, catalog(recipe), factory, List.of(constraint), List.of(), _ -> false, () -> 1, List.of());
        RecipeScanner.ScanAttempt pending = null;
        for (int tick = 0; tick < 5000 && host.activeProcesses().size() < 2; tick++) {
            if (pending == null) pending = scanner.beginScan(LIMITS);
            pending.advance(new RecipeWorkBudget(1), () -> true);
            if (pending.finished()) pending = null;
            else if (!pending.hasObligations()) {
                pending.releasePureYield();
                pending = null;
            }
        }
        assertEquals(2, host.activeProcesses().size());
        if (pending != null) while (!pending.finished()) pending.advance(new RecipeWorkBudget(1), () -> true);
        assertTrue(creations[0] <= 4, "Only an actual shared-balance change may rebuild a candidate");
        assertTrue(checks[0] <= 4);
        assertEquals(0, host.sharedUsage(recipe).available());
    }

    private static final class Host implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No World in controller fixtures");
        }

        @Override
        public void requireServerThread() {
        }
    }
}
