package com.circulation.prototype_eng.recipe;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCatalogTest {
    private static final ResourceLocation FIRST_GROUP = id("first_group");
    private static final ResourceLocation SECOND_GROUP = id("second_group");

    private static MachineRecipe recipe(String name, ResourceLocation... groups) {
        return new MachineRecipe(id(name), 1, List.of(), List.of(groups));
    }

    private static RecipePool pool(String name, MachineRecipe... recipes) {
        return new DefaultRecipePool(id(name), "catalog.title", List.of(recipes));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("catalog_test", path);
    }

    @Test
    void poolAndDeclarationOrderWinsOverGroupOrderAndSharedCanonicalRecipesAppearOnce() {
        var first = recipe("z_first", FIRST_GROUP);
        var shared = recipe("shared", FIRST_GROUP, SECOND_GROUP);
        var last = recipe("a_last", SECOND_GROUP);
        var outside = recipe("outside", id("other_group"));
        var ungrouped = recipe("ungrouped");
        var pools = new ObjectArrayList<>(List.of(pool("first_pool", first, shared), pool("second_pool", shared, last, outside, ungrouped)));
        var catalog = new RecipeCatalog(pools);
        pools.clear();
        var reverseGroups = new ObjectLinkedOpenHashSet<>(List.of(SECOND_GROUP, FIRST_GROUP));
        var result = catalog.candidates(new MachineInstance(), reverseGroups, List.of());
        assertEquals(List.of(first, shared, last), result);
        assertSame(shared, result.get(1));
        reverseGroups.clear();
        assertEquals(List.of(first, shared, last), result);
    }

    @Test
    void unknownGroupsAndNoOpinionDifferFromAnExplicitEmptyIndexInBothGlobalAndGroupedScopes() {
        var first = recipe("first", FIRST_GROUP);
        var outside = recipe("outside", SECOND_GROUP);
        var catalog = new RecipeCatalog(List.of(pool("pool", first, outside)));
        var machine = new MachineInstance();
        RecipeCandidateIndex shouldNotRun = (_, _, _) -> {
            throw new AssertionError("Empty scope must not invoke indices");
        };
        assertTrue(new RecipeCatalog(List.of()).candidates(machine, Set.of(), List.of(shouldNotRun)).isEmpty());
        assertTrue(catalog.candidates(machine, Set.of(id("unknown")), List.of(shouldNotRun)).isEmpty());
        RecipeCandidateIndex noOpinion = (_, _, _) -> null;
        RecipeCandidateIndex reject = (_, _, _) -> List.of();
        assertEquals(List.of(first, outside), catalog.candidates(machine, Set.of(), List.of(noOpinion)));
        assertTrue(catalog.candidates(machine, Set.of(), List.of(reject, noOpinion)).isEmpty());
        assertEquals(List.of(first), catalog.candidates(machine, Set.of(FIRST_GROUP), List.of(noOpinion)));
        assertTrue(catalog.candidates(machine, Set.of(FIRST_GROUP), List.of(reject, noOpinion)).isEmpty());
    }

    @Test
    void globalIndexesSeeTheSameCompleteImmutableCanonicalScopeIncludingUngroupedRecipes() {
        var first = recipe("global_first", FIRST_GROUP);
        var ungrouped = recipe("global_ungrouped");
        var last = recipe("global_last", SECOND_GROUP);
        var catalog = new RecipeCatalog(List.of(pool("first_pool", first, ungrouped), pool("second_pool", first, last)));
        var expected = List.of(first, ungrouped, last);
        var observed = new ObjectArrayList<List<MachineRecipe>>();
        RecipeCandidateIndex firstIndex = (_, groups, scope) -> {
            assertTrue(groups.isEmpty());
            assertEquals(expected, scope);
            observed.add(scope);
            return List.of(last, ungrouped);
        };
        RecipeCandidateIndex secondIndex = (_, groups, scope) -> {
            assertTrue(groups.isEmpty());
            assertSame(observed.getFirst(), scope);
            assertEquals(expected, scope);
            return List.of(first, ungrouped, ungrouped);
        };
        var machine = new MachineInstance();
        assertEquals(List.of(ungrouped), catalog.candidates(machine, Set.of(), List.of(firstIndex, secondIndex)));
        assertEquals(expected, catalog.candidates(machine, Set.of(), List.of()));
        for (var foreign : List.of(recipe("global_first", FIRST_GROUP), recipe("foreign"))) {
            RecipeCandidateIndex index = (_, _, _) -> List.of(foreign);
            assertThrows(IllegalArgumentException.class, () -> catalog.candidates(machine, Set.of(), List.of(index)));
        }
    }

    @Test
    void indexIntersectionRetainsEnergyAndResourceFreeRecipesAndRestoresCatalogOrder() {
        var key = new Key(new KeyType("catalog"), "material");
        var io = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(List.of(new GenericStack(key, 1)), List.of()));
        var itemFirst = new MachineRecipe(id("item_first"), 1, List.of(io), Set.of(FIRST_GROUP));
        var energy = new MachineRecipe(id("energy"), 1,
            List.of(RecipeRequirements.ENERGY.requirement(id("energy_io"), new EnergyRequirement(0, 1, 0, 0, false))), Set.of(FIRST_GROUP));
        var itemSecond = new MachineRecipe(id("item_second"), 1, List.of(io), Set.of(FIRST_GROUP));
        var universal = recipe("universal", FIRST_GROUP);
        var catalog = new RecipeCatalog(List.of(pool("pool", itemFirst, energy, itemSecond, universal)));
        RecipeCandidateIndex itemIndex = (_, _, scope) -> {
            var matching = new ObjectArrayList<MachineRecipe>();
            for (var candidate : scope) {
                if (!candidate.requirements().containsKey(RecipeRequirements.RESOURCES) || candidate == itemSecond) {
                    matching.add(candidate);
                }
            }
            matching.add(itemSecond);
            Collections.reverse(matching);
            return matching;
        };
        RecipeCandidateIndex independent = (_, _, scope) -> {
            assertEquals(List.of(itemFirst, energy, itemSecond, universal), scope);
            return List.of(universal, itemFirst, energy, energy);
        };
        assertEquals(List.of(energy, universal), catalog.candidates(new MachineInstance(), Set.of(FIRST_GROUP), List.of(itemIndex, independent)));
    }

    @Test
    void duplicateIdsForeignDefinitionsAndOutOfScopeRecipesAreRejected() {
        var first = recipe("first", FIRST_GROUP);
        var counterfeit = recipe("first", FIRST_GROUP);
        var outside = recipe("outside", SECOND_GROUP);
        assertThrows(IllegalArgumentException.class, () -> new RecipeCatalog(List.of(pool("first_pool", first), pool("second_pool", counterfeit))));
        var catalog = new RecipeCatalog(List.of(pool("pool", first, outside)));
        var machine = new MachineInstance();
        for (var rejected : List.of(counterfeit, outside, recipe("foreign", FIRST_GROUP))) {
            RecipeCandidateIndex index = (_, _, _) -> List.of(rejected);
            assertThrows(IllegalArgumentException.class, () -> catalog.candidates(machine, Set.of(FIRST_GROUP), List.of(index)));
            RecipeCandidateIndex empty = (_, _, _) -> List.of();
            assertThrows(IllegalArgumentException.class, () -> catalog.candidates(machine, Set.of(FIRST_GROUP), List.of(empty, index)));
        }
    }
}
