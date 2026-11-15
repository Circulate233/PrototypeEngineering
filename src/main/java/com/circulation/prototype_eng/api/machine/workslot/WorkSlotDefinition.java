package com.circulation.prototype_eng.api.machine.workslot;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Set;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * Static work slot metadata; empty allowedRecipeGroups accepts every recipe, including ungrouped recipes.
 * The supplied group set must remain unchanged for this definition's lifetime.
 */
public record WorkSlotDefinition(String name, Set<ResourceLocation> allowedRecipeGroups, boolean isCore,
                                 WorkSlotRestartPolicy restartPolicy) {
    public WorkSlotDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Work slot name is required");
        }
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
        return accepts(allowedRecipeGroups, recipe);
    }

    static boolean accepts(Set<ResourceLocation> allowedRecipeGroups, MachineRecipe recipe) {
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
