package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.CompositeRecipePool;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCatalogCompositionTest {
    private static final ResourceLocation GROUP = id("group");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("catalog_composition_test", path);
    }

    private static MachineRecipe free(String path) {
        return new MachineRecipe(id(path), 1, List.of(), Set.of(GROUP), 1, RecipeParallelismSelection.fixed(1));
    }

    private static void complete(RecipeCatalog.CandidatePass pass) {
        for (int step = 0; step < 1000; step++) if (pass.advance(new RecipeWorkBudget(1))) return;
        throw new AssertionError("Combined catalog pass did not finish");
    }

    @Test
    @SuppressWarnings("resource")
        // Bounded finally proves this real discovery worker exits after success or failure.
    void selectedMixedProvidersReuseTheirCombinationWithoutReenumeratingRecipesAndPreserveLiveProofs() throws Exception {
        var first = free("first");
        var shared = free("shared");
        var last = free("last");
        var general = new DefaultRecipePool(id("general"), "pool.general", List.of(first, shared));
        var custom = new Provider(id("custom"), List.of(last, shared), List.of(last, shared), 7, new int[1], new boolean[]{true}, new Thread[1]);
        var catalog = new RecipeCatalog(List.of(general, custom));
        var machine = new MachineInstance() {
            @Override
            public List<RecipePool> getRecipePools(RecipeCatalog actual) {
                assertSame(catalog, actual);
                return List.of(custom, general);
            }
        };
        int enumerations = custom.enumerations[0];
        var capture = catalog.beginSelection(machine);
        assertTrue(capture.advance(new RecipeWorkBudget(2)));
        var selected = capture.result();
        assertEquals(enumerations, custom.enumerations[0]);
        RecipeCandidateIndex external = (_, _, scope) -> {
            assertEquals(List.of(first, shared, last), scope);
            return List.of(shared, last);
        };
        var pass = catalog.beginCandidates(selected, Set.of(GROUP), List.of(external));
        assertEquals(enumerations, custom.enumerations[0]);
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> complete(pass)).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertNotSame(Thread.currentThread(), custom.enumerationThread[0]);
        var combined = Objects.requireNonNull(assertInstanceOf(CompositeRecipePool.class, selected.combinedPool()));
        assertEquals(List.of(custom, general), combined.pools());
        enumerations = custom.enumerations[0];
        assertSame(combined, catalog.select(machine).combinedPool());
        assertEquals(enumerations, custom.enumerations[0]);
        assertEquals(List.of(shared, last), pass.result());
        assertEquals(Integer.MAX_VALUE, pass.inputParallelismLimit(shared));
        assertEquals(7, pass.inputParallelismLimit(last));
        custom.valid[0] = false;
        var validation = pass.beginValidation();
        for (int step = 0; step < 1000 && !validation.advance(new RecipeWorkBudget(1)); step++) {
            assertThrows(IllegalStateException.class, validation::current);
        }
        assertFalse(validation.current());
        assertThrows(IllegalStateException.class, pass::result);
    }

    @Test
    void actualCrossControllerScansGeneralSimpleAndCustomProvidersThroughTheirCombinedSelection() {
        var type = new KeyType("combined_controller");
        var input = new Key(type, "input");
        var output = new Key(type, "output");
        var first = free("general_recipe");
        var last = free("custom_recipe");
        var definition = SimpleRecipePool.Entry.create(id("simple_recipe"), 1, new GenericStack(input, 1), new GenericStack(output, 1), Set.of(GROUP));
        var simpleRecipe = new MachineRecipe(definition.recipe().id(), 1, definition.recipe().orderedRequirements(), Set.of(GROUP), 1, RecipeParallelismSelection.fixed(1));
        var general = new DefaultRecipePool(id("general_actual"), "pool.general", List.of(first));
        var simple = new SimpleRecipePool(id("simple_actual"), "pool.simple", List.of(SimpleRecipePool.Entry.of(simpleRecipe)));
        var custom = new Provider(id("custom_actual"), List.of(last, first), List.of(last, first), 1, new int[1], new boolean[]{true}, new Thread[1]);
        var catalog = new RecipeCatalog(List.of(general, simple, custom));
        var machine = new CrossRecipeController();
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 3);
        var hatch = new BufferedResourceHatch(HatchMode.MIXED, 2, Reference2LongMaps.singleton(type, 100L), () -> {
        });
        Objects.requireNonNull(hatch.input()).insert(input, 1, Actionable.MODULATE);
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        var host = machine.createRecipeHost(List.of(), Set.of(GROUP));
        var scanner = machine.createRecipeScanner(host, catalog, () -> 7);
        assertInstanceOf(CompositeRecipePool.class, catalog.select(machine).combinedPool());
        assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(new ScanLimits(32, 32)).status());
        assertEquals(List.of(first, simpleRecipe, last), host.activeProcesses().stream().map(RecipeProcess::recipe).toList());
        host.tickProcesses();
        assertTrue(host.activeProcesses().isEmpty());
        assertEquals(0, Objects.requireNonNull(hatch.input()).availableAmount(input));
        assertEquals(1, Objects.requireNonNull(hatch.output()).availableAmount(output));
    }

    private record Provider(ResourceLocation id, List<MachineRecipe> definitions,
                            @Nullable Collection<MachineRecipe> opinion,
                            int bound, int[] enumerations, boolean[] valid,
                            Thread[] enumerationThread) implements RecipePool {
        @Override
        public String translationKey() {
            return "pool.provider";
        }

        @Override
        public List<MachineRecipe> recipes() {
            enumerations[0]++;
            enumerationThread[0] = Thread.currentThread();
            return definitions;
        }

        @Override
        public List<MachineType> machines() {
            return List.of();
        }

        @Override
        public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
            throw new AssertionError("The fixture uses an already canonical catalog");
        }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // Actual retained provider cursor and its mutable liveness fixture.
        public RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
            return new RecipeCandidateIndex.Cursor() {
                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    return budget.take();
                }

                @Override
                public @Nullable Collection<MachineRecipe> result() {
                    return opinion;
                }

                @Override
                public int inputParallelismLimit(MachineRecipe recipe) {
                    return bound;
                }

                @Override
                public boolean current() {
                    return valid[0];
                }
            };
        }
    }
}
