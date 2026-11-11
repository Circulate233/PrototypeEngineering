package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsynchronousRecipeDiscoveryTest {
    private static final KeyType TYPE = new KeyType("asynchronous_discovery");
    private static final Key FIRST = new Key(TYPE, "first");
    private static final Key SECOND = new Key(TYPE, "second");
    private static final Key OUTPUT = new Key(TYPE, "output");
    private static final ScanLimits LIMITS = new ScanLimits(16, 16);

    private static ResourceLocation id(String path) {
        return new ResourceLocation("asynchronous_discovery_test", path);
    }

    private static BufferedResourceHatch hatch() {
        return new BufferedResourceHatch(HatchMode.MIXED, 3, Reference2LongMaps.singleton(TYPE, 100L), () -> { });
    }

    private static MachineRecipe recipe(String path, Key input, long amount, RecipeParallelismSelection selection) {
        var entry = SimpleRecipePool.Entry.create(id(path), 1, new GenericStack(input, amount), new GenericStack(OUTPUT, 1), Set.of());
        return new MachineRecipe(entry.recipe().id(), 1, entry.recipe().orderedRequirements(), Set.of(), 1, selection);
    }

    private static MachineWorkSlots install(MultithreadController machine, BufferedResourceHatch hatch) {
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        return machine.createRecipeHost(List.of(new WorkSlotDefinition("main")), Set.of());
    }

    private static boolean worker(RecipeCatalog.CandidatePass pass, ExecutorService executor) throws Exception {
        return executor.submit(() -> pass.advance(new RecipeWorkBudget(128))).get(5, TimeUnit.SECONDS);
    }

    private static RecipeScanner.Result drive(RecipeScanner.ScanAttempt attempt, ExecutorService executor) throws Exception {
        for (int step = 0; step < 10000; step++) {
            var discovery = attempt.discoveryWork();
            if (discovery != null) attempt.finishDiscovery(discovery, worker(discovery, executor));
            var budget = new RecipeWorkBudget(128);
            for (var source : attempt.sources()) {
                if (attempt.resolvingSources()) attempt.finishSourceResolution(source, source.resolveWithinBudget(budget));
                else if (source.state() == ResourceBatch.SourceState.RESERVED) source.applyWithinBudget(budget);
            }
            var computation = attempt.computation();
            if (computation != null && computation.advance(budget)) attempt.finishComputation(computation);
            var result = attempt.advance(budget, () -> true);
            if (attempt.finished()) return result;
        }
        throw new AssertionError("Asynchronous discovery did not settle");
    }

    @Test
    void emptyMixedPoolsCreateNoCandidatesAndLaterInventoryIsFoundOnlyByTheWorker() throws Exception {
        var owner = Thread.currentThread();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var machine = new MultithreadController();
            var hatch = hatch();
            var host = install(machine, hatch);
            int[] selections = {0};
            var selector = new RecipeParallelismSelection() {
                @Override
                public void select(RecipeProcess process) {
                    assertSame(owner, Thread.currentThread());
                    selections[0]++;
                }

                @Override
                public boolean stableFixedInputLowerBounds() { return true; }
            };
            var first = recipe("first", FIRST, 1, selector);
            var second = recipe("second", SECOND, 1, selector);
            var a = new ObservedPool(new DefaultRecipePool(id("general"), "pool.general", List.of(first)), owner, new int[1]);
            var b = new ObservedPool(new SimpleRecipePool(id("simple"), "pool.simple", List.of(SimpleRecipePool.Entry.of(second))), owner, new int[1]);
            var scanner = machine.createRecipeScanner(host, new RecipeCatalog(List.of(a, b)), () -> 7);
            var creations = new ObjectArrayList<MachineRecipe>();
            scanner.decorateProcessFactory(original -> recordingFactory(original, owner, creations), true);
            assertTrue(machine.supportsStableFixedInputLowerBounds());
            assertEquals(RecipeScanner.Status.EXHAUSTED, drive(scanner.beginScan(LIMITS, true), executor).status());
            assertTrue(creations.isEmpty());
            assertEquals(0, selections[0]);
            assertTrue(host.activeProcesses().isEmpty());
            assertEquals(1, Objects.requireNonNull(hatch.input()).insert(SECOND, 1, Actionable.MODULATE));
            var result = drive(scanner.beginScan(LIMITS, true), executor);
            assertEquals(RecipeScanner.Status.ADMITTED, result.status());
            assertSame(second, Objects.requireNonNull(result.process()).recipe());
            assertEquals(List.of(second), creations);
            assertEquals(1, selections[0]);
            assertTrue(a.calls[0] >= 2 && b.calls[0] >= 2);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void ownerRejectsAWorkerCandidateWhoseInventoryChangedBeforeReceiptAndRediscoversTheNewRecipe() throws Exception {
        var owner = Thread.currentThread();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var machine = new MultithreadController();
            var hatch = hatch();
            var host = install(machine, hatch);
            var first = recipe("stale", FIRST, 1, RecipeParallelismSelection.NONE);
            var second = recipe("fresh", SECOND, 1, RecipeParallelismSelection.NONE);
            var a = new ObservedPool(new DefaultRecipePool(id("stale_general"), "pool.general", List.of(first)), owner, new int[1]);
            var b = new ObservedPool(new SimpleRecipePool(id("fresh_simple"), "pool.simple", List.of(SimpleRecipePool.Entry.of(second))), owner, new int[1]);
            var scanner = machine.createRecipeScanner(host, new RecipeCatalog(List.of(a, b)), () -> 7);
            var creations = new ObjectArrayList<MachineRecipe>();
            scanner.decorateProcessFactory(original -> recordingFactory(original, owner, creations), true);
            var input = Objects.requireNonNull(hatch.input());
            assertEquals(1, input.insert(FIRST, 1, Actionable.MODULATE));
            var attempt = scanner.beginScan(LIMITS, true);
            attempt.advance(new RecipeWorkBudget(1000), () -> true);
            var discovery = Objects.requireNonNull(attempt.discoveryWork());
            assertTrue(worker(discovery, executor));
            assertEquals(List.of(first), discovery.result());
            assertTrue(creations.isEmpty());
            assertEquals(1, input.extract(FIRST, 1, Actionable.MODULATE));
            assertEquals(1, input.insert(SECOND, 1, Actionable.MODULATE));
            attempt.finishDiscovery(discovery, true);
            var result = drive(attempt, executor);
            assertEquals(RecipeScanner.Status.ADMITTED, result.status());
            assertSame(second, Objects.requireNonNull(result.process()).recipe());
            assertEquals(List.of(second), creations);
            assertTrue(a.calls[0] >= 2 && b.calls[0] >= 2);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void lastCompletedRetryUsesItsInputCeilingInOneProbeWithoutAnyWholePoolDiscovery() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (boolean simple : List.of(false, true)) {
                var machine = new MultithreadController();
                var hatch = hatch();
                var host = install(machine, hatch);
                var definition = recipe("last", FIRST, 5, RecipeParallelismSelection.NONE);
                var input = Objects.requireNonNull(hatch.input());
                assertEquals(5, input.insert(FIRST, 5, Actionable.MODULATE));
                assertTrue(host.start(new RecipeProcess(machine, definition, 1)));
                host.tickProcesses();
                assertTrue(host.activeProcesses().isEmpty());
                assertEquals(definition.id(), host.requireSlot("main").lastCompletedRecipe());
                assertEquals(10, input.insert(FIRST, 10, Actionable.MODULATE));
                machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1_000_000);
                RecipePool pool = simple ? new SimpleRecipePool(id("last_simple"), "pool.title", List.of(SimpleRecipePool.Entry.of(definition)))
                    : new DefaultRecipePool(id("last_general"), "pool.title", List.of(definition));
                var observed = new ObservedPool(pool, Thread.currentThread(), new int[1]);
                var scanner = machine.createRecipeScanner(host, new RecipeCatalog(List.of(observed)), () -> 11);
                var attempt = scanner.beginScan(new ScanLimits(1, 1), true);
                var result = drive(attempt, executor);
                assertEquals(RecipeScanner.Status.ADMITTED, result.status());
                assertEquals(1, result.probes());
                assertEquals(2, RecipeParallelism.effective(Objects.requireNonNull(result.process())));
                assertEquals(0, observed.calls[0]);
                assertNull(attempt.discoveryWork());
                assertEquals(10, input.availableAmount(FIRST));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static RecipeProcessFactory recordingFactory(RecipeProcessFactory original, Thread owner, List<MachineRecipe> creations) {
        return new RecipeProcessFactory() {
            @Override
            public RecipeProcess create(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                assertSame(owner, Thread.currentThread());
                creations.add(recipe);
                return original.create(machine, recipe, seed, tuning);
            }

            @Override
            public Creation beginCreation(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                assertSame(owner, Thread.currentThread());
                creations.add(recipe);
                return original.beginCreation(machine, recipe, seed, tuning);
            }
        };
    }

    private record ObservedPool(RecipePool delegate, Thread owner, int[] calls) implements RecipePool {
        @Override public ResourceLocation id() { return delegate.id(); }
        @Override public String translationKey() { return delegate.translationKey(); }
        @Override public List<MachineRecipe> recipes() { return delegate.recipes(); }
        @Override public List<MachineType> machines() { return delegate.machines(); }
        @Override public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) { return delegate.freeze(recipes, machines); }

        @Override
        public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
            assertNotSame(owner, Thread.currentThread());
            calls[0]++;
            return delegate.beginCandidates(machine, groups);
        }
    }
}
