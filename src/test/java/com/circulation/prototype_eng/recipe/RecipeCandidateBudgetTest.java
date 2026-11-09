package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;

class RecipeCandidateBudgetTest {
    private static final ResourceLocation FIRST = id("first");
    private static final ResourceLocation SECOND = id("second");
    private static final KeyType TYPE = new KeyType("candidate_budget");
    private static final Key KEY = new Key(TYPE, "key");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("candidate_budget_test", path);
    }

    private static RecipeCatalog catalog(List<MachineRecipe> recipes) {
        return new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "budget.pool", recipes)));
    }

    private static List<MachineRecipe> recipes(int count) {
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < count; index++) {
            recipes.add(new MachineRecipe(id("recipe_" + index), 1, List.of(), Set.of(FIRST, SECOND)));
        }
        return recipes;
    }

    private static List<MachineRecipe> complete(RecipeCatalog.CandidatePass pass, int slice, int maximumCalls) {
        for (int call = 0; call < maximumCalls; call++) {
            if (pass.advance(new RecipeWorkBudget(slice))) return pass.result();
            assertTrue(pass.current());
            assertThrows(IllegalStateException.class, pass::result);
        }
        throw new AssertionError("Incremental candidate pass did not finish");
    }

    private static SlottedResourceBuffer buffer(long amount) {
        var limits = new Reference2LongArrayMap<AEKeyType>();
        limits.put(TYPE, Long.MAX_VALUE);
        var storage = new SlottedResourceBuffer(1, limits, () -> {});
        storage.insert(KEY, amount, Actionable.MODULATE);
        return storage;
    }

    @Test
    void oneUnitSlicesVisitOverlappingGroupsAndCanonicalOutputWithoutReplayingLegacyLookup() {
        var recipes = recipes(300);
        var catalog = catalog(recipes);
        var calls = new AtomicInteger();
        RecipeCandidateIndex index = (_, groups, scope) -> {
            calls.incrementAndGet();
            assertEquals(new ObjectLinkedOpenHashSet<>(List.of(SECOND, FIRST)), groups);
            assertEquals(recipes, scope);
            assertThrows(UnsupportedOperationException.class, scope::clear);
            return scope;
        };
        var pass = catalog.beginCandidates(new MachineInstance(), new ObjectLinkedOpenHashSet<>(List.of(SECOND, FIRST)), List.of(index));
        assertFalse(pass.advance(new RecipeWorkBudget(0)));
        assertEquals(0, calls.get());
        assertFalse(pass.advance(new RecipeWorkBudget(10)));
        assertEquals(0, calls.get());
        var result = complete(pass, 1, 4000);
        assertEquals(recipes, result);
        assertEquals(1, calls.get());
        for (int indexInList = 0; indexInList < recipes.size(); indexInList++) assertSame(recipes.get(indexInList), result.get(indexInList));
    }

    @Test
    @SuppressWarnings("ReturnOfInnerClass") // Fixture iterator counts the exact original callback visits.
    void aLargeLegacyOpinionIsConsumedOnlyUnderLibraryCreditsAndDuplicatesDoNotChangeIntersection() {
        var recipes = recipes(64);
        var consumed = new AtomicInteger();
        Collection<MachineRecipe> repeatedOpinion = new AbstractCollection<>() {
            @Override
            public @NotNull Iterator<MachineRecipe> iterator() {
                return new Iterator<>() {
                    private int cursor;
                    @Override public boolean hasNext() { return cursor < 1024; }
                    @Override public MachineRecipe next() {
                        consumed.incrementAndGet();
                        return recipes.get((cursor++ % 32) * 2);
                    }
                };
            }
            @Override public int size() { return 1024; }
        };
        RecipeCandidateIndex first = (_, _, _) -> repeatedOpinion;
        RecipeCandidateIndex second = (_, _, _) -> List.of(recipes.get(2), recipes.get(4), recipes.get(7));
        var pass = catalog(recipes).beginCandidates(new MachineInstance(), Set.of(), List.of(first, second));
        while (consumed.get() == 0) assertFalse(pass.advance(new RecipeWorkBudget(1)));
        assertEquals(1, consumed.get());
        assertFalse(pass.advance(new RecipeWorkBudget(0)));
        assertEquals(1, consumed.get());
        assertFalse(pass.advance(new RecipeWorkBudget(3)));
        assertEquals(4, consumed.get());
        assertEquals(List.of(recipes.get(2), recipes.get(4)), complete(pass, 1, 2000));
        assertEquals(1024, consumed.get());
    }

    @Test
    void emptyIndexOpinionRejectsTheWholeScopeWithoutFallbackWhileNullKeepsIt() {
        var recipes = recipes(128);
        var catalog = catalog(recipes);
        RecipeCandidateIndex noOpinion = (_, _, _) -> null;
        RecipeCandidateIndex reject = (_, _, _) -> List.of();
        assertEquals(recipes, complete(catalog.beginCandidates(new MachineInstance(), Set.of(), List.of(noOpinion)), 2, 500));
        assertTrue(complete(catalog.beginCandidates(new MachineInstance(), Set.of(FIRST), List.of(reject, noOpinion)), 2, 500).isEmpty());
        assertEquals(recipes, catalog.candidates(new MachineInstance(), Set.of(), List.of(noOpinion)));
        assertTrue(catalog.candidates(new MachineInstance(), Set.of(), List.of(reject)).isEmpty());
    }

    @Test
    void resourcePostingsShareTheCatalogSliceAndDeduplicateViewsBeforeSaturatingAcrossContexts() {
        var definitions = recipes(120);
        var unindexed = new MachineRecipe(id("free"), 1, List.of());
        definitions = new ObjectArrayList<>(definitions);
        definitions.add(unindexed);
        var catalog = catalog(definitions);
        var footprints = new ObjectArrayList<ResourceRecipeIndex.Footprint>();
        for (var recipe : definitions) {
            if (recipe != unindexed) footprints.add(new ResourceRecipeIndex.Footprint(recipe, List.of(new GenericStack(KEY, Long.MAX_VALUE))));
        }
        var index = new ResourceRecipeIndex(catalog, footprints);
        var first = buffer(Long.MAX_VALUE - 5);
        var second = buffer(10);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Input(first), new Input(first), new Input(second)), List.of(), List.of()));
        var pass = catalog.beginCandidates(machine, Set.of(), List.of(index));
        assertFalse(pass.advance(new RecipeWorkBudget(0)));
        assertFalse(pass.advance(new RecipeWorkBudget(100)));
        assertEquals(definitions, complete(pass, 1, 3000));
        assertEquals(Long.MAX_VALUE - 5, first.amount(KEY));
        assertEquals(Long.MAX_VALUE - 5, first.availableAmount(KEY));
        assertEquals(10, second.amount(KEY));
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Input(first), new Input(first)), List.of(), List.of()));
        assertEquals(List.of(unindexed), complete(catalog.beginCandidates(machine, Set.of(), List.of(index)), 1, 3000));
    }

    @Test
    void aSourceInvalidatedAfterQuantityCaptureCannotPublishItsPartialOpinion() {
        var definitions = recipes(100);
        var catalog = catalog(definitions);
        var footprints = new ObjectArrayList<ResourceRecipeIndex.Footprint>();
        for (var recipe : definitions) footprints.add(new ResourceRecipeIndex.Footprint(recipe, List.of(new GenericStack(KEY, 1))));
        var index = new ResourceRecipeIndex(catalog, footprints);
        var storage = buffer(5);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Input(storage)), List.of(), List.of()));
        var lookup = index.beginLookup(machine, Set.of(), definitions);
        // 100 scoped definitions, one hatch-list capture, one hatch and one real quantity entry.
        assertFalse(lookup.advance(new RecipeWorkBudget(103)));
        storage.context().invalidate();
        assertFalse(lookup.advance(new RecipeWorkBudget(1000)));
        assertFalse(lookup.current());
        assertThrows(IllegalStateException.class, lookup::result);
        assertTrue(index.lookup(machine, Set.of(), definitions).isEmpty());
    }

    private record Input(ResourceStorage input) implements ResourceHatch {
        @Override public ResourceStorage output() { return null; }
        @Override public boolean isValid() { return input.context().isValid(); }
    }
}
