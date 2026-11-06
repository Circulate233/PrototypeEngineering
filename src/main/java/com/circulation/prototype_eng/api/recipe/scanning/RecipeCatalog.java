package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Frozen canonical recipes in pool/declaration order, queried only through explicit group scopes. */
public final class RecipeCatalog {
    private final Map<ResourceLocation, MachineRecipe> recipes;
    private final List<MachineRecipe> orderedRecipes;
    private final Map<ResourceLocation, List<MachineRecipe>> byGroup;
    private final Reference2IntOpenHashMap<MachineRecipe> order = new Reference2IntOpenHashMap<>();

    public RecipeCatalog(Collection<RecipePool> pools) {
        Objects.requireNonNull(pools, "pools");
        var canonical = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        var groups = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<MachineRecipe>>();
        for (var pool : pools) {
            for (var recipe : Objects.requireNonNull(pool, "pool").recipes()) {
                var previous = canonical.putIfAbsent(recipe.id(), recipe);
                if (previous != null) {
                    if (previous != recipe) {
                        throw new IllegalArgumentException("Different canonical recipes share catalog ID: " + recipe.id());
                    }
                    continue;
                }
                order.put(recipe, order.size());
                for (var group : recipe.recipeGroups()) {
                    groups.computeIfAbsent(group, _ -> new ObjectArrayList<>()).add(recipe);
                }
            }
        }
        recipes = Object2ObjectMaps.unmodifiable(canonical);
        orderedRecipes = ObjectLists.unmodifiable(new ObjectArrayList<>(canonical.values()));
        var frozenGroups = new Object2ObjectOpenHashMap<ResourceLocation, List<MachineRecipe>>();
        groups.forEach((group, members) -> frozenGroups.put(group, ObjectLists.unmodifiable(members)));
        byGroup = Object2ObjectMaps.unmodifiable(frozenGroups);
    }

    /** Canonical definitions in frozen pool/declaration order, for building external indexes. */
    public List<MachineRecipe> recipes() {
        return orderedRecipes;
    }

    /** Each index sees the same full scope; its nonnull result intersects the other index results. */
    public List<MachineRecipe> candidates(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(indexes, "indexes");
        var groupCopy = new ObjectLinkedOpenHashSet<ResourceLocation>();
        for (var group : groups) {
            groupCopy.add(Objects.requireNonNull(group, "group"));
        }
        var frozenGroups = ObjectSets.unmodifiable(groupCopy);
        var indexCopy = new ObjectArrayList<RecipeCandidateIndex>(indexes.size());
        for (var index : indexes) {
            indexCopy.add(Objects.requireNonNull(index, "index"));
        }
        var scoped = new ReferenceOpenHashSet<MachineRecipe>();
        var orderedScope = new ObjectArrayList<MachineRecipe>();
        for (var group : frozenGroups) {
            var members = byGroup.get(group);
            if (members == null) {
                continue;
            }
            for (var recipe : members) {
                if (scoped.add(recipe)) {
                    orderedScope.add(recipe);
                }
            }
        }
        if (orderedScope.isEmpty()) {
            return ObjectLists.emptyList();
        }
        orderedScope.sort(Comparator.comparingInt(order::getInt));
        var scope = ObjectLists.unmodifiable(orderedScope);
        var remaining = new ReferenceOpenHashSet<>(scoped);
        for (var index : indexCopy) {
            var candidates = index.lookup(machine, frozenGroups, scope);
            if (candidates == null) {
                continue;
            }
            var accepted = new ReferenceOpenHashSet<MachineRecipe>();
            for (var candidate : candidates) {
                Objects.requireNonNull(candidate, "indexed candidate");
                if (recipes.get(candidate.id()) != candidate || !scoped.contains(candidate)) {
                    throw new IllegalArgumentException("Indexed recipe is foreign, counterfeit or outside its group scope: " + candidate.id());
                }
                accepted.add(candidate);
            }
            remaining.retainAll(accepted);
        }
        var result = new ObjectArrayList<MachineRecipe>(remaining.size());
        for (var recipe : scope) {
            if (remaining.contains(recipe)) {
                result.add(recipe);
            }
        }
        return ObjectLists.unmodifiable(result);
    }
}
