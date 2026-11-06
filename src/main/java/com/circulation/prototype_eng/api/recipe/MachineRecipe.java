package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recipe definition migrated from PM: extensible requirements grouped by execution responsibility.
 * Resource identities belong in requirement payloads (AE2 keys); FE is a separate requirement.
 * Neither the definition nor the phase executor assumes a fixed set of resource kinds.
 */
public final class MachineRecipe {
    private final ResourceLocation id;
    private final int durationTicks;
    private final Set<ResourceLocation> recipeGroups;
    private final Map<RecipeRequirementType<?>, List<RecipeRequirement<?>>> requirements;
    private final List<RecipeRequirement<?>> orderedRequirements;

    public MachineRecipe(ResourceLocation id, int durationTicks, Collection<? extends RecipeRequirement<?>> requirements) {
        this(id, durationTicks, requirements, ObjectSets.emptySet());
    }

    public MachineRecipe(ResourceLocation id, int durationTicks, Collection<? extends RecipeRequirement<?>> requirements,
                         Collection<ResourceLocation> recipeGroups) {
        this.id = Objects.requireNonNull(id);
        if (durationTicks < 0) {
            throw new IllegalArgumentException("Negative recipe duration");
        }
        this.durationTicks = durationTicks;
        var groups = new ObjectLinkedOpenHashSet<ResourceLocation>();
        for (var group : recipeGroups) {
            groups.add(Objects.requireNonNull(group));
        }
        this.recipeGroups = ObjectSets.unmodifiable(groups);
        var typesById = new Object2ObjectOpenHashMap<ResourceLocation, RecipeRequirementType<?>>();
        var requirementIds = new ObjectOpenHashSet<ResourceLocation>();
        var sorted = new ObjectArrayList<RecipeRequirement<?>>();
        for (var requirement : requirements) {
            Objects.requireNonNull(requirement);
            var previousType = typesById.putIfAbsent(requirement.type().id(), requirement.type());
            if (previousType != null && previousType != requirement.type()) {
                throw new IllegalArgumentException("Different requirement types share id " + requirement.type().id());
            }
            if (!requirementIds.add(requirement.id())) {
                throw new IllegalArgumentException("Duplicate requirement " + requirement.id());
            }
            sorted.add(requirement);
        }
        // Stable sort keeps declaration order inside each type, as in PM's executeStage.
        sorted.sort(Comparator.comparing(requirement -> requirement.type().id().toString()));
        orderedRequirements = ObjectLists.unmodifiable(sorted);
        var grouped = new Object2ObjectLinkedOpenHashMap<RecipeRequirementType<?>, List<RecipeRequirement<?>>>();
        for (var requirement : sorted) {
            grouped.computeIfAbsent(requirement.type(), ignored -> new ObjectArrayList<>()).add(requirement);
        }
        grouped.replaceAll((_, list) -> ObjectLists.unmodifiable(new ObjectArrayList<>(list)));
        this.requirements = Object2ObjectMaps.unmodifiable(grouped);
    }

    public ResourceLocation id() {
        return id;
    }

    /** Declared work ticks, advanced by the process speed attribute. */
    public int durationTicks() {
        return durationTicks;
    }

    /** Downstream mods use group membership to select recipe definitions. */
    @SuppressWarnings("unused")
    public Set<ResourceLocation> recipeGroups() {
        return recipeGroups;
    }

    /** Downstream mods inspect requirements by type through this immutable view. */
    @SuppressWarnings("unused")
    public Map<RecipeRequirementType<?>, List<RecipeRequirement<?>>> requirements() {
        return requirements;
    }

    public List<RecipeRequirement<?>> orderedRequirements() {
        return orderedRequirements;
    }
}
