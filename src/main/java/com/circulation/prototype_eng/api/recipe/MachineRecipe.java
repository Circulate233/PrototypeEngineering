package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recipe definition migrated from PM: extensible requirements grouped by execution responsibility.
 * Resource identities belong in requirement payloads (AE2 keys); FE is a separate requirement.
 * Neither the definition nor the phase executor assumes a fixed set of resource kinds.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class MachineRecipe {
    private final ResourceLocation id;
    private final int durationTicks;
    private final Set<ResourceLocation> recipeGroups;
    private final Map<RecipeRequirementType<?>, List<RecipeRequirement<?>>> requirements;
    private final List<RecipeRequirement<?>> orderedRequirements;
    private final int maxConcurrentThreads;
    private final RecipeParallelismSelection parallelismSelection;

    public MachineRecipe(ResourceLocation id, int durationTicks, Collection<? extends RecipeRequirement<?>> requirements) {
        this(id, durationTicks, requirements, ObjectSets.emptySet());
    }

    public MachineRecipe(ResourceLocation id, int durationTicks, Collection<? extends RecipeRequirement<?>> requirements,
                         Collection<ResourceLocation> recipeGroups) {
        this(id, durationTicks, requirements, recipeGroups, Integer.MAX_VALUE);
    }

    /**
     * Logical running processes of this canonical recipe on one machine, independent of parallel quantities.
     */
    public MachineRecipe(ResourceLocation id, int durationTicks, Collection<? extends RecipeRequirement<?>> requirements,
                         Collection<ResourceLocation> recipeGroups, int maxConcurrentThreads) {
        this(id, durationTicks, requirements, recipeGroups, maxConcurrentThreads, RecipeParallelismSelection.NONE);
    }

    public MachineRecipe(ResourceLocation id, int durationTicks, Collection<? extends RecipeRequirement<?>> requirements,
                         Collection<ResourceLocation> recipeGroups, int maxConcurrentThreads, RecipeParallelismSelection parallelismSelection) {
        this.id = id;
        if (durationTicks < 0) {
            throw new IllegalArgumentException("Negative recipe duration");
        }
        this.durationTicks = durationTicks;
        if (maxConcurrentThreads < 1) throw new IllegalArgumentException("Recipe thread limit must be positive");
        this.maxConcurrentThreads = maxConcurrentThreads;
        this.parallelismSelection = parallelismSelection;
        var groups = new ObjectLinkedOpenHashSet<ResourceLocation>(recipeGroups.size());
        groups.addAll(recipeGroups);
        this.recipeGroups = groups;
        var typesById = new Object2ObjectOpenHashMap<ResourceLocation, RecipeRequirementType<?>>(requirements.size());
        var requirementIds = new ObjectOpenHashSet<ResourceLocation>(requirements.size());
        var sorted = new ObjectArrayList<RecipeRequirement<?>>(requirements.size());
        for (var requirement : requirements) {
            var previousType = typesById.putIfAbsent(requirement.type().id(), requirement.type());
            if (previousType != null && previousType != requirement.type()) {
                throw new IllegalArgumentException("Different requirement types share id " + requirement.type().id());
            }
            if (!requirementIds.add(requirement.id())) {
                throw new IllegalArgumentException("Duplicate requirement " + requirement.id());
            }
            sorted.add(requirement);
        }
        sorted.sort(Comparator.comparing(requirement -> requirement.type().id()));
        orderedRequirements = sorted;
        var grouped = new Object2ObjectLinkedOpenHashMap<RecipeRequirementType<?>, List<RecipeRequirement<?>>>(typesById.size());
        for (int index = 0; index < sorted.size(); index++) {
            var requirement = sorted.get(index);
            grouped.computeIfAbsent(requirement.type(), ignored -> new ObjectArrayList<>(6)).add(requirement);
        }
        this.requirements = grouped;
    }

    public ResourceLocation id() {
        return id;
    }

    /**
     * Declared work ticks, advanced by the process speed attribute.
     */
    public int durationTicks() {
        return durationTicks;
    }

    /**
     * Downstream mods use group membership to select recipe definitions.
     */
    @SuppressWarnings("unused")
    public Set<ResourceLocation> recipeGroups() {
        return recipeGroups;
    }

    /**
     * Downstream mods inspect requirements by type through this immutable view.
     */
    @SuppressWarnings("unused")
    public Map<RecipeRequirementType<?>, List<RecipeRequirement<?>>> requirements() {
        return requirements;
    }

    public List<RecipeRequirement<?>> orderedRequirements() {
        return orderedRequirements;
    }

    public int maxConcurrentThreads() {
        return maxConcurrentThreads;
    }

    public RecipeParallelismSelection parallelismSelection() {
        return parallelismSelection;
    }
}
