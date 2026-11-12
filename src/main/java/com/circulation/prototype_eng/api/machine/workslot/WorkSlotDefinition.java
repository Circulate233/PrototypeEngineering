package com.circulation.prototype_eng.api.machine.workslot;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.Set;

/**
 * Static work slot metadata; empty allowedRecipeGroups accepts every recipe, including ungrouped recipes.
 */
public record WorkSlotDefinition(String name, Set<ResourceLocation> allowedRecipeGroups, boolean isCore,
                                 WorkSlotRestartPolicy restartPolicy) {
    public WorkSlotDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Work slot name is required");
        }
        Objects.requireNonNull(allowedRecipeGroups, "allowedRecipeGroups");
        Objects.requireNonNull(restartPolicy, "restartPolicy");
        var groups = new ObjectLinkedOpenHashSet<ResourceLocation>(allowedRecipeGroups.size());
        for (var group : allowedRecipeGroups) {
            groups.add(Objects.requireNonNull(group, "recipe group"));
        }
        allowedRecipeGroups = ObjectSets.unmodifiable(groups);
    }

    public WorkSlotDefinition(String name) {
        this(name, ObjectSets.emptySet());
    }

    public WorkSlotDefinition(String name, Set<ResourceLocation> allowedRecipeGroups) {
        this(name, allowedRecipeGroups, true, WorkSlotRestartPolicy.LOCKED_RETRY);
    }

    /**
     * Shared compatibility rule for scanning, direct admission and restoration.
     */
    public boolean accepts(MachineRecipe recipe) {
        Objects.requireNonNull(recipe, "recipe");
        if (allowedRecipeGroups.isEmpty()) {
            return true;
        }
        for (var group : recipe.recipeGroups()) {
            if (allowedRecipeGroups.contains(group)) {
                return true;
            }
        }
        return false;
    }
}
