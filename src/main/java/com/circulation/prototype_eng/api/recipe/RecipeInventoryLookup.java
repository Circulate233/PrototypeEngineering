package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/**
 * Adapts the public cooperative inventory callback without manufacturing runtime processes.
 */
final class RecipeInventoryLookup implements RecipeCandidateIndex.Cursor {
    private final RecipePool pool;
    private final AEKey2LongMap inventory;
    private final int parallelism;
    private final Iterator<ResourceLocation> inputGroups;
    private final ObjectLinkedOpenHashSet<ResourceLocation> groups = new ObjectLinkedOpenHashSet<>();
    private final ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
    private final Reference2IntOpenHashMap<MachineRecipe> bounds = new Reference2IntOpenHashMap<>();
    private Iterator<RecipeInventoryMatch> matches;
    private RecipeInventoryMatch pending;
    private Iterator<ResourceLocation> pendingGroups;
    private boolean accepted;
    private boolean complete;

    RecipeInventoryLookup(RecipePool pool, AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        this.pool = pool;
        this.inventory = inventory;
        if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
        this.parallelism = parallelism;
        inputGroups = groups.iterator();
    }

    @Override
    public boolean advance(RecipeWorkBudget budget) {
        if (complete) return true;
        while (inputGroups.hasNext()) {
            if (!budget.take()) return false;
            groups.add(inputGroups.next());
        }
        if (matches == null) {
            if (!budget.take()) return false;
            matches = pool.match(inventory, parallelism).iterator();
        }
        while (pending != null || matches.hasNext()) {
            if (pending == null) {
                if (!budget.take()) return false;
                pending = matches.next();
                if (pending.maximumParallelism() > parallelism)
                    throw new IllegalArgumentException("Inventory ceiling exceeds requested parallelism");
                pendingGroups = pending.recipe().recipeGroups().iterator();
                accepted = groups.isEmpty();
            }
            while (!accepted && pendingGroups.hasNext()) {
                if (!budget.take()) return false;
                accepted = groups.contains(pendingGroups.next());
            }
            if (accepted) {
                var recipe = pending.recipe();
                if (!bounds.containsKey(recipe)) result.add(recipe);
                bounds.put(recipe, Math.max(bounds.getInt(recipe), pending.maximumParallelism()));
            }
            pending = null;
        }
        complete = true;
        return true;
    }

    @Override
    public Collection<MachineRecipe> result() {
        requireComplete();
        return result;
    }

    @Override
    public int inputParallelismLimit(MachineRecipe recipe) {
        requireComplete();
        return bounds.getOrDefault(recipe, Integer.MAX_VALUE);
    }

    private void requireComplete() {
        if (!complete) throw new IllegalStateException("Inventory opinion is incomplete");
    }
}
