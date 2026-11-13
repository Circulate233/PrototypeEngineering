package com.circulation.prototype_eng.api.client.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.integration.jei.ExternalRecipePoolPages;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExistingRecipePoolPagesTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("existing_jei_page", name);
    }

    @Test
    void resolvesLaterPluginCategoryOnceWithoutCreatingOrDescribingOwnRecipes() {
        var recipe = new MachineRecipe(id("foreign"), 2, List.of());
        var pool = new DefaultRecipePool(id("pool"), "pool.title", List.of(recipe)).withExistingJeiPage("foreign:late");
        var pages = new RecipePoolJeiRegistry();
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        var registry = new Object();
        var categories = new ObjectOpenHashSet<String>();
        var calls = new int[1];
        ExternalRecipePoolPages.reset();
        ExternalRecipePoolPages.defer(registry, known -> {
            calls[0]++;
            pages.freeze(List.of(pool), _ -> {
                    throw new AssertionError("External category must not create a page");
                },
                displays.snapshot(RecipePoolJeiRegistry.recipesForOwnPages(List.of(pool), known)), known);
        });
        ExternalRecipePoolPages.finish(new Object(), categories);
        assertEquals(0, calls[0]);
        categories.add("foreign:late");
        ExternalRecipePoolPages.finish(registry, categories);
        ExternalRecipePoolPages.finish(registry, categories);
        assertEquals(1, calls[0]);
        assertTrue(pages.pages().isEmpty());
        assertEquals("foreign:late", pages.externalPages().get(pool.id()));
        assertTrue(pages.ownedRecipes(List.of(pool)).isEmpty());
        assertTrue(pages.catalysts(pool.id()).isEmpty());
    }

    @Test
    void missingBlankAndAbsentExternalIdsKeepOrdinaryPages() {
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        for (var target : List.of("foreign:missing", " ", "")) {
            var pool = new DefaultRecipePool(id("fallback"), "pool.title", List.of()).withExistingJeiPage(target);
            var pages = new RecipePoolJeiRegistry();
            var calls = new int[1];
            pages.freeze(List.of(pool), source -> {
                calls[0]++;
                return JeiTestViews.page(source, new JeiTestViews.Helper(), displays);
            }, null, Set.of("foreign:other"));
            assertEquals(1, calls[0]);
            assertSame(pool, pages.pages().getFirst().pool());
            assertTrue(pages.externalPages().isEmpty());
        }
        var ordinary = new DefaultRecipePool(id("ordinary"), "pool.title", List.of());
        var pages = new RecipePoolJeiRegistry();
        pages.freeze(List.of(ordinary), source -> JeiTestViews.page(source, new JeiTestViews.Helper(), displays), null, Set.of("foreign:other"));
        assertSame(ordinary, pages.pages().getFirst().pool());
    }

    @Test
    void canonicalFreezeAndReplacementRetainMachineBindingsAndSkipExternalUpdates() {
        var type = new KeyType("external_pool");
        var input = new GenericStack(new Key(type, "input"), 3);
        var output = new GenericStack(new Key(type, "output"), 4);
        var first = SimpleRecipePool.Entry.create(id("recipe"), 2, input, output, Set.of());
        var next = SimpleRecipePool.Entry.create(id("recipe"), 4, input, output, Set.of());
        RecipePool simple = new SimpleRecipePool(id("simple"), "pool.title", List.of(first)).withExistingJeiPage("foreign:late");
        RecipePool general = new DefaultRecipePool(id("general"), "pool.title", List.of(first.recipe())).withExistingJeiPage("foreign:late");
        var machine = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape"))
                                 .recipePool(simple.id()).recipePool(general.id()).build();
        var registry = new RecipeRegistry();
        registry.register(simple);
        registry.register(general);
        registry.declareRevision(first.recipe(), "v1");
        registry.freeze(List.of(machine));
        var before = registry.snapshot();
        var pages = new RecipePoolJeiRegistry();
        pages.freeze(before.orderedPools(), _ -> {
            throw new AssertionError("External pool must not create an owned page");
        }, null, Set.of("foreign:late"));
        var prepared = registry.prepareReplacement(before, List.of(new RecipeRegistry.Definition(next.recipe(), "v2")));
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        var pools = prepared.next().orderedPools();
        assertTrue(pages.acceptsPools(pools));
        assertTrue(Objects.requireNonNull(pages.tryPrepareUpdates(pools, displays.snapshot(pages.ownedRecipes(pools)))).isEmpty());
        for (var pool : pools) {
            assertEquals(List.of(machine), pool.machines());
            assertSame(next.recipe(), pool.recipes().getFirst());
            assertEquals("foreign:late", pages.externalPages().get(pool.id()));
        }
        registry.install(prepared);
        var changed = ((DefaultRecipePool) Objects.requireNonNull(registry.get(general.id()))).withExistingJeiPage("foreign:other");
        var changedPools = List.of(Objects.requireNonNull(registry.get(simple.id())), changed);
        assertFalse(pages.acceptsPools(changedPools));
        assertNull(pages.tryPrepareUpdates(changedPools, displays.snapshot(List.of())));
    }
}
