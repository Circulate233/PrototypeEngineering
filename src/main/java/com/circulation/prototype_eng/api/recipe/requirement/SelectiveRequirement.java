package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Ordered optional alternatives. The first successful START is retained for this process's lifetime.
 * Supplied lists and entries must remain unchanged for the definition's lifetime.
 */
public record SelectiveRequirement(List<RecipeRequirement<?>> candidates, List<SelectiveModifierDescriptor> modifiers) {
    public SelectiveRequirement {
        var candidateIds = new ObjectOpenHashSet<RequirementPath>(candidates.size());
        for (int index2 = 0; index2 < candidates.size(); index2++) {
            var candidate = candidates.get(index2);
            if (!candidateIds.add(candidate.path(null))) {
                throw new IllegalArgumentException("Duplicate selective candidate identity");
            }
        }
        var modifierIds = new ObjectOpenHashSet<ResourceLocation>(modifiers.size());
        for (int index = 0; index < modifiers.size(); index++) {
            var modifier = modifiers.get(index);
            if (!modifierIds.add(modifier.id())) {
                throw new IllegalArgumentException("Duplicate selective modifier id: " + modifier.id());
            }
        }
    }

    public SelectiveRequirement(List<RecipeRequirement<?>> candidates) {
        this(candidates, ObjectLists.emptyList());
    }
}
