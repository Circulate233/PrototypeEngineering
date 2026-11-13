package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.RecipeController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePoolLiveScannerTest {
    private static final KeyType TYPE = new KeyType("live_pool_scanner");
    private static final Key A = new Key(TYPE, "a");
    private static final Key B = new Key(TYPE, "b");
    private static final Key OUTPUT = new Key(TYPE, "output");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("live_pool_scanner_test", path);
    }

    private static BufferedResourceHatch hatch() {
        return new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(TYPE, 100L), () -> {
        });
    }

    private static SimpleRecipePool.Entry entry(String path, Key input) {
        return SimpleRecipePool.Entry.create(id(path), 2, new GenericStack(input, 1), new GenericStack(OUTPUT, 1), Set.of());
    }

    /**
     * Drives the real retained kernel and source-exit protocol, as in RecipeProbeBudgetTest.
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

    @Test
    void actualDefaultControllersRecaptureChangedPoolCandidatesAfterPureYieldWithNoExternalIndex() {
        for (RecipeController owner : List.of(new MultithreadController(), new CrossRecipeController())) {
            owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
            owner.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
            var old = entry("old", A);
            var fresh = entry("fresh", B);
            var catalog = new RecipeCatalog(List.of(new SimpleRecipePool(id("pool"), "live.pool", List.of(old, fresh))));
            var hatch = hatch();
            var input = Objects.requireNonNull(hatch.input());
            assertEquals(1, input.insert(A, 1, Actionable.MODULATE));
            owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
            var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("slot")), Set.of());
            var scanner = owner.createRecipeScanner(host, catalog, () -> 17);
            assertTrue(owner.supportsStableFixedInputLowerBounds());
            var first = scanner.beginScan(new ScanLimits(1, 1));
            assertEquals(RecipeScanner.Status.YIELDED, step(first, 10000).status());
            for (int slice = 0; slice < 10000 && first.hasObligations(); slice++) step(first, 1);
            assertTrue(host.activeProcesses().isEmpty());
            if (!first.finished()) {
                assertFalse(first.hasObligations());
                first.releasePureYield();
            }
            assertEquals(1, input.extract(A, 1, Actionable.MODULATE));
            assertEquals(2, input.insert(B, 2, Actionable.MODULATE));
            assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(new ScanLimits(32, 32)).status());
            assertSame(fresh.recipe(), host.activeProcesses().getFirst().recipe());
        }
    }

    @Test
    void partialDiscoverySurvivesRepeatedNewTickYieldsAndOneUnitValidationCanFinish() {
        int[] selections = {0};
        var owner = new CrossRecipeController() {
            @Override
            public List<RecipePool> getRecipePools(RecipeCatalog catalog) {
                selections[0]++;
                return catalog.pools();
            }
        };
        owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        var ports = new ObjectArrayList<BufferedResourceHatch>();
        for (int index = 0; index < 32; index++) ports.add(hatch());
        assertEquals(1, Objects.requireNonNull(ports.getLast().input()).insert(A, 1, Actionable.MODULATE));
        owner.components().put(MachineHatches.TYPE, new MachineHatches(ports, List.of(), List.of()));
        var recipe = entry("sliced", A);
        var catalog = new RecipeCatalog(List.of(new SimpleRecipePool(id("sliced_pool"), "live.pool", List.of(recipe))));
        var host = owner.createRecipeHost(List.of(), Set.of());
        var scanner = owner.createRecipeScanner(host, catalog, () -> 1);
        RecipeScanner.ScanAttempt attempt = null;
        int pureYields = 0;
        for (int tick = 0; tick < 1000; tick++) {
            attempt = scanner.beginScan(new ScanLimits(32, 32));
            var result = step(attempt, 8);
            if (result.candidates() > 0 || attempt.finished()) break;
            assertFalse(attempt.hasObligations(), "Pool discovery has no START obligations to discard");
            attempt.releasePureYield();
            pureYields++;
            attempt = null;
        }
        assertTrue(pureYields >= 4, "The fixture must span multiple genuine discovery ticks");
        assertNotNull(attempt, "Repeated new-tick validation must finish and allow candidate discovery to progress");
        for (int slice = 0; slice < 10000 && !attempt.finished(); slice++) step(attempt, 1);
        assertTrue(attempt.finished(), "Stable partial discovery and its nested validation must progress under one-unit slices");
        assertSame(recipe.recipe(), host.activeProcesses().getFirst().recipe());
        assertEquals(1, selections[0], "Yield must retain the canonical selection of this unfinished pass");
    }

    @Test
    void removingAnEmptyRuntimeSlotDuringPureFactoryYieldReselectsTheCurrentPublicLayout() {
        var owner = new CrossRecipeController();
        owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        var recipe = new MachineRecipe(id("restored_slot"), 2, List.of());
        var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("restored_pool"), "live.pool", List.of(recipe))));
        var host = owner.createRecipeHost(List.of(), Set.of());
        boolean[] entered = {false};
        boolean[] allowCreation = {false};
        var factory = new RecipeProcessFactory() {
            @Override
            public RecipeProcess create(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                return new RecipeProcess(machine, definition, seed, tuning);
            }

            @Override
            @SuppressWarnings("ReturnOfInnerClass")
            // Retains this real fixture's unpublished factory candidate across its controlled pure yield.
            public Creation beginCreation(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                entered[0] = true;
                return new Creation() {
                    private RecipeProcess process;

                    @Override
                    public boolean advance(RecipeWorkBudget budget) {
                        if (process != null) return true;
                        if (!budget.take() || !allowCreation[0]) return false;
                        process = create(machine, definition, seed, tuning);
                        return true;
                    }

                    @Override
                    public RecipeProcess result() {
                        return Objects.requireNonNull(process);
                    }
                };
            }
        };
        var scanner = RecipeScanner.crossRecipe(host, catalog, factory, List.of(), List.of(), _ -> false, () -> 29, List.of());
        var first = scanner.beginScan(new ScanLimits(32, 32));
        assertEquals(RecipeScanner.Status.YIELDED, step(first, 10000).status());
        assertTrue(entered[0], "The first candidate must retain a selected slot while its pure factory yields");
        assertEquals(1, host.slots().size());
        assertEquals("pe_cross/0", host.slots().getFirst().name());
        assertTrue(host.activeProcesses().isEmpty());
        assertFalse(first.hasObligations());
        first.releasePureYield();
        host.restore(List.of(), host.nextRuntimeSlot());
        assertTrue(host.slots().isEmpty());
        assertEquals(1, host.nextRuntimeSlot());
        allowCreation[0] = true;
        var resumed = scanner.beginScan(new ScanLimits(32, 32));
        for (int slice = 0; slice < 10000 && !resumed.finished(); slice++) step(resumed, 1);
        assertTrue(resumed.finished());
        assertEquals(1, host.activeProcesses().size());
        var admitted = host.activeProcesses().getFirst();
        assertSame(recipe, admitted.recipe());
        assertSame(host, admitted.host());
        assertSame(admitted, host.requireSlot("pe_cross/1").process());
        assertEquals(2, host.nextRuntimeSlot());
    }
}
