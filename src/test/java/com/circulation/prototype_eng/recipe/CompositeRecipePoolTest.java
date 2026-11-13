package com.circulation.prototype_eng.recipe;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.CompositeRecipePool;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeInventoryMatch;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeRecipePoolTest {
    private static final KeyType TYPE = new KeyType("composite_pool");
    private static final Key INPUT = new Key(TYPE, "input");
    private static final Key OUTPUT = new Key(TYPE, "output");
    private static final ResourceLocation GROUP = id("group");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("composite_pool_test", name);
    }

    private static MachineRecipe recipe(String name) {
        return new MachineRecipe(id(name), 2, List.of(), Set.of(GROUP));
    }

    private static SimpleRecipePool.Entry entry(String name, long amount) {
        return SimpleRecipePool.Entry.create(id(name), 2, new GenericStack(INPUT, amount), new GenericStack(OUTPUT, 1), Set.of(GROUP));
    }

    private static void complete(RecipeCandidateIndex.Cursor cursor) {
        assertFalse(cursor.advance(new RecipeWorkBudget(0)));
        assertThrows(IllegalStateException.class, cursor::result);
        for (int slice = 0; slice < 1000; slice++) {
            if (cursor.advance(new RecipeWorkBudget(1))) return;
            assertTrue(cursor.current());
            assertThrows(IllegalStateException.class, cursor::result);
        }
        throw new AssertionError("Composite lookup did not finish under one-credit slices");
    }

    private static TestPool testPool(String name, List<MachineRecipe> recipes, List<RecipeInventoryMatch> matches) {
        return new TestPool(id(name), recipes, List.of(), matches, List.of(), 1, new int[1], new boolean[]{true});
    }

    @Test
    void mixedSnapshotProvidersPreserveGlobalPriorityAndTakeTheWidestSharedBound() {
        var first = entry("first", 5);
        var second = entry("second", 3);
        var third = recipe("third");
        var simple = new SimpleRecipePool(id("simple"), "pool.simple", List.of(first, second));
        var custom = new TestPool(id("custom"), List.of(third, first.recipe()), List.of(),
            List.of(new RecipeInventoryMatch(third, 1), new RecipeInventoryMatch(first.recipe(), 7)), List.of(), 1, new int[1], new boolean[]{true});
        var composite = new CompositeRecipePool(id("combined"), "pool.combined", List.of(simple, custom, simple));
        var inventory = new AEKey2LongMap.OpenHashMap();
        inventory.put(INPUT, 12);
        assertEquals(List.of(simple, custom), composite.pools());
        assertEquals(List.of(first.recipe(), second.recipe(), third), composite.recipes());
        var matches = composite.match(inventory, 10);
        assertEquals(List.of(first.recipe(), second.recipe(), third), matches.stream().map(RecipeInventoryMatch::recipe).toList());
        assertEquals(7, matches.get(0).maximumParallelism());
        assertEquals(4, matches.get(1).maximumParallelism());
        assertEquals(1, matches.get(2).maximumParallelism());
        var lookup = composite.beginMatch(inventory, 10, Set.of(GROUP));
        complete(lookup);
        assertEquals(matches.stream().map(RecipeInventoryMatch::recipe).toList(), lookup.result());
        assertEquals(7, lookup.inputParallelismLimit(first.recipe()));
        assertEquals(12, inventory.getLong(INPUT));
        var excluded = composite.beginMatch(inventory, 1, Set.of(id("absent")));
        complete(excluded);
        assertEquals(List.of(), excluded.result());
    }

    @Test
    void firstMatchHandlesNonMonotonicSharedRecipesAndSafelySkipsLaterProviders() {
        var first = recipe("first");
        var second = recipe("second");
        var third = recipe("third");
        var a = testPool("a", List.of(first, second), List.of(new RecipeInventoryMatch(second, 1)));
        var b = testPool("b", List.of(third, first), List.of(new RecipeInventoryMatch(third, 1), new RecipeInventoryMatch(first, 1)));
        var skippedCalls = new int[1];
        var skipped = new TestPool(id("skipped"), List.of(recipe("skipped")), List.of(), List.of(), List.of(), 1, skippedCalls, new boolean[]{true});
        var composite = new CompositeRecipePool(id("first_match"), "pool.title", List.of(a, b, skipped));
        var inventory = new AEKey2LongMap.OpenHashMap();
        assertSame(first, composite.match(inventory));
        assertEquals(1, a.calls[0]);
        assertEquals(1, b.calls[0]);
        assertEquals(0, skippedCalls[0]);
        assertSame(first, composite.match(inventory, 1).getFirst().recipe());
        assertNull(new CompositeRecipePool(id("empty"), "pool.title", List.of()).match(inventory));
    }

    @Test
    void liveChildFallbackUnionIsBudgetedAndNestedFreshnessInvalidatesTheWholeOpinion() {
        var first = recipe("live_first");
        var second = recipe("live_second");
        var third = recipe("live_third");
        var live = new TestPool(id("live"), List.of(first, second), List.of(), List.of(), List.of(first), 2, new int[1], new boolean[]{true});
        var rejected = new TestPool(id("rejected"), List.of(second), List.of(), List.of(), List.of(), 1, new int[1], new boolean[]{true});
        var fallback = new TestPool(id("fallback"), List.of(first, third), List.of(), List.of(), null, 1, new int[1], new boolean[]{true});
        var nested = new CompositeRecipePool(id("nested"), "pool.title", List.of(rejected, fallback));
        var combined = new CompositeRecipePool(id("live_combined"), "pool.title", List.of(live, nested));
        var cursor = combined.beginCandidates(new MachineInstance(), Set.of(GROUP));
        complete(cursor);
        assertEquals(List.of(first, third), cursor.result());
        assertEquals(Integer.MAX_VALUE, cursor.inputParallelismLimit(first));
        live.valid[0] = false;
        var validation = cursor.beginValidation();
        for (int slice = 0; slice < 1000 && !validation.advance(new RecipeWorkBudget(1)); slice++) {
            assertThrows(IllegalStateException.class, validation::current);
        }
        assertFalse(validation.current());
        assertFalse(cursor.current());
        assertThrows(IllegalStateException.class, cursor::result);
    }

    @Test
    void registryFreezeKeepsChildStrategiesAndBindsCanonicalReplacementsAndMachineUsers() {
        var first = entry("frozen_first", 5);
        var second = entry("frozen_second", 3);
        var simple = new SimpleRecipePool(id("frozen_simple"), "pool.simple", List.of(first)).withExistingJeiPage("existing:simple");
        var general = new DefaultRecipePool(id("frozen_general"), "pool.general", List.of(first.recipe(), second.recipe()));
        var combined = new CompositeRecipePool(id("frozen_combined"), "pool.combined", List.of(simple, general));
        var machine = MachineType.builder(id("machine"), RecipeText.Cancelled, id("structure")).recipePool(combined.id()).build();
        var registry = new RecipeRegistry();
        registry.register(combined);
        registry.freeze(List.of(machine));
        var frozen = Objects.requireNonNull(assertInstanceOf(CompositeRecipePool.class, registry.get(combined.id())));
        assertEquals(List.of(machine), frozen.machines());
        assertInstanceOf(SimpleRecipePool.class, frozen.pools().getFirst());
        assertEquals("existing:simple", frozen.pools().getFirst().existingJeiPageId());
        assertEquals(List.of(machine), frozen.pools().getFirst().machines());
        var replacement = entry("frozen_first", 4).recipe();
        var replaced = frozen.freeze(List.of(replacement, second.recipe()), List.of(machine));
        assertSame(replacement, replaced.pools().getFirst().recipes().getFirst());
        assertSame(replacement, replaced.pools().getLast().recipes().getFirst());
        assertThrows(IllegalArgumentException.class, () -> frozen.freeze(List.of(replacement, second.recipe(), recipe("extra")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CompositeRecipePool(id("collision"), "pool.title", List.of(
            general, new DefaultRecipePool(id("foreign"), "pool.title", List.of(entry("frozen_first", 2).recipe())))));
    }

    @Test
    void providersCannotReturnRecipesOwnedOnlyByAnotherChild() {
        var first = recipe("owned_first");
        var second = recipe("owned_second");
        var invalid = testPool("invalid", List.of(first), List.of(new RecipeInventoryMatch(second, 1)));
        var other = new DefaultRecipePool(id("owner"), "pool.title", List.of(second));
        var combined = new CompositeRecipePool(id("invalid_combined"), "pool.title", List.of(invalid, other));
        var inventory = new AEKey2LongMap.OpenHashMap();
        assertThrows(IllegalArgumentException.class, () -> combined.match(inventory, 1));
        assertThrows(IllegalArgumentException.class, () -> combined.beginMatch(inventory, 1, Set.of()).advance(new RecipeWorkBudget(100)));
    }

    @Test
    void aSingleRecipeReadsOnlyItsRequiredKeyWithoutEnumeratingTheInventory() {
        var definition = entry("one_key", 5);
        var simple = new SimpleRecipePool(id("one_simple"), "pool.simple", List.of(definition));
        var general = new DefaultRecipePool(id("one_general"), "pool.general", List.of(definition.recipe()));
        var stored = new AEKey2LongMap.OpenHashMap();
        stored.put(INPUT, 12);
        for (int index = 0; index < 1000; index++) stored.put(new Key(TYPE, "irrelevant_" + index), 1);
        var inventory = new DirectInventory(stored);
        for (RecipePool pool : List.of(simple, general)) {
            var cursor = pool.beginMatch(inventory, 1000, Set.of());
            complete(cursor);
            assertEquals(List.of(definition.recipe()), cursor.result());
            assertEquals(2, cursor.inputParallelismLimit(definition.recipe()));
        }
    }

    private static final class DirectInventory extends AbstractObject2LongMap<AEKey> implements AEKey2LongMap {
        private final AEKey2LongMap quantities;

        private DirectInventory(AEKey2LongMap quantities) {
            this.quantities = quantities;
        }

        @Override
        public long getLong(Object key) {
            return quantities.getLong(key);
        }

        @Override
        public int size() {
            return quantities.size();
        }

        @Override
        public ObjectSet<Object2LongMap.Entry<AEKey>> object2LongEntrySet() {
            throw new AssertionError("A single-recipe index must not enumerate unrelated inventory entries");
        }

        @Override
        public long addTo(AEKey key, long amount) {
            throw new AssertionError("Matching cannot modify the snapshot");
        }
    }

    private record TestPool(ResourceLocation id, List<MachineRecipe> recipes, List<MachineType> machines,
                            List<RecipeInventoryMatch> inventoryMatches, @Nullable Collection<MachineRecipe> live,
                            int bound, int[] calls, boolean[] valid) implements RecipePool {
        @Override
        public String translationKey() {
            return "pool.test";
        }

        @Override
        public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
            calls[0]++;
            var output = new ObjectArrayList<RecipeInventoryMatch>();
            for (var match : inventoryMatches)
                output.add(new RecipeInventoryMatch(match.recipe(), Math.min(parallelism, match.maximumParallelism())));
            return output;
        }

        @Override
        public TestPool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
            return new TestPool(id, recipes, machines, inventoryMatches, live, bound, calls, valid);
        }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // Test provider exposes an actual retained live opinion.
        public RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
            return new RecipeCandidateIndex.Cursor() {
                private boolean complete;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    if (complete) return true;
                    if (!budget.take()) return false;
                    complete = true;
                    return true;
                }

                @Override
                public @Nullable Collection<MachineRecipe> result() {
                    if (!complete) throw new IllegalStateException("Incomplete test opinion");
                    return live;
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
