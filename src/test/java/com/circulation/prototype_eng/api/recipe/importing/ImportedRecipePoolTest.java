package com.circulation.prototype_eng.api.recipe.importing;

import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportedRecipePoolTest {
    private static final ResourceLocation ID = new ResourceLocation("imports_test", "pool");

    private static Supplier<List<ImportedRecipe<String>>> source(AtomicInteger reads) {
        return () -> {
            reads.incrementAndGet();
            return List.of(
                new ImportedRecipe<>(new RecipeBuilder(new ResourceLocation("imports_test", "accepted"), 5), "accept"),
                new ImportedRecipe<>(new RecipeBuilder(new ResourceLocation("imports_test", "rejected"), 5), "reject"));
        };
    }

    @Test
    void configurationAndMetadataDeferSourceUntilRegistrationAndRetainCanonicalDefinitions() {
        var reads = new AtomicInteger();
        var calls = new AtomicInteger();
        var imported = new ImportedRecipePool<>(ID, "pool.title", source(reads), (recipe, context) -> {
            calls.incrementAndGet();
            recipe.durationTicks(17);
            return context.equals("accept");
        });
        var energy = RecipeRequirements.ENERGY.requirement(new ResourceLocation("imports_test", "common_energy"),
            new EnergyRequirement(0, 2, 0, 0, false));
        var configured = imported.withRequirements(List.of(energy)).withExistingJeiPage("source:category");
        assertEquals(ID, configured.id());
        assertEquals("pool.title", configured.translationKey());
        assertEquals("source:category", configured.existingJeiPageId());
        assertEquals(List.of(), configured.machines());
        assertEquals(0, reads.get());
        assertEquals(0, calls.get());

        var registry = new RecipeRegistry();
        registry.register(configured);
        var recipe = configured.recipes().getFirst();
        registry.declareRevision(recipe, "v1");
        registry.freeze();
        assertEquals(1, reads.get());
        assertEquals(2, calls.get());
        assertEquals(1, registry.catalog().recipes().size());
        assertSame(recipe, registry.catalog().recipes().getFirst());
        assertSame(energy, recipe.orderedRequirements().getFirst());
        assertEquals(17, recipe.durationTicks());
        assertEquals(1, imported.recipes().size());
        assertNotSame(recipe, imported.recipes().getFirst());
    }

    @Test
    void independentInstancesSnapshotAtTheirOwnFirstAccessAndRunFiltersOnce() {
        var reads = new AtomicInteger();
        var firstCalls = new AtomicInteger();
        BiPredicate<RecipeBuilder, String> fast = (recipe, context) -> {
            firstCalls.incrementAndGet();
            recipe.durationTicks(3);
            return context.equals("accept");
        };
        var first = new ImportedRecipePool<>(ID, "pool.title", source(reads), fast);
        var second = new ImportedRecipePool<>(new ResourceLocation("imports_test", "other"), "pool.other", source(reads),
            (recipe, context) -> {
                recipe.durationTicks(29);
                return context.equals("reject");
            });
        var firstRecipes = first.recipes();
        assertEquals(1, reads.get());
        assertEquals(2, firstCalls.get());
        assertSame(firstRecipes, first.recipes());
        var secondRecipes = second.recipes();
        assertEquals(2, reads.get());
        assertEquals(3, firstRecipes.getFirst().durationTicks());
        assertEquals(29, secondRecipes.getFirst().durationTicks());
        assertEquals("imports_test:accepted", firstRecipes.getFirst().id().toString());
        assertEquals("imports_test:rejected", secondRecipes.getFirst().id().toString());
    }

    @Test
    void concurrentFirstReadsShareOneCompleteSnapshot() throws Exception {
        var reads = new AtomicInteger();
        var pool = new ImportedRecipePool<>(ID, "pool.title", source(reads));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ObjectArrayList<Future<List<MachineRecipe>>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(pool::recipes));
            var expected = tasks.getFirst().get();
            for (var task : tasks) assertSame(expected, task.get());
        }
        assertEquals(1, reads.get());
    }

    @Test
    void failedOrRecursiveImportsNeverPublishOrRerunCallbacks() {
        var reads = new AtomicInteger();
        var expected = new IllegalArgumentException("source failed");
        var failed = ImportedRecipePool.withoutContext(ID, "pool.title", () -> {
            reads.incrementAndGet();
            throw expected;
        }, _ -> true);
        assertSame(expected, assertThrows(IllegalArgumentException.class, failed::recipes));
        assertSame(expected, assertThrows(IllegalStateException.class, failed::recipes).getCause());
        assertEquals(1, reads.get());
        var holder = new RecipePool[1];
        holder[0] = ImportedRecipePool.withoutContext(ID, "pool.title", () -> {
            holder[0].recipes();
            return List.of();
        }, _ -> true);
        assertThrows(IllegalStateException.class, holder[0]::recipes);
        assertThrows(IllegalStateException.class, holder[0]::recipes);
    }

    @Test
    void noContextCallbackEditsDraftAndCanRejectBeforeDefinitionValidation() {
        var pool = ImportedRecipePool.withoutContext(ID, "pool.title", () -> List.of(
            new RecipeBuilder(new ResourceLocation("imports_test", "keep"), 1),
            new RecipeBuilder(new ResourceLocation("imports_test", "drop"), 1)), recipe -> {
                recipe.durationTicks(8);
                return recipe.id().getPath().equals("keep");
            });
        assertEquals(1, pool.recipes().size());
        assertEquals(8, pool.recipes().getFirst().durationTicks());
    }
}
