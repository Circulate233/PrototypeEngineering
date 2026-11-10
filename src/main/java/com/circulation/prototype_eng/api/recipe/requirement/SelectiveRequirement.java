package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Ordered optional alternatives. The first successful START is retained for this process's lifetime.
 */
public record SelectiveRequirement(List<RecipeRequirement<?>> candidates, List<SelectiveModifierDescriptor> modifiers) {
    public SelectiveRequirement {
        var candidateCopy = new ObjectArrayList<RecipeRequirement<?>>();
        var candidateIds = new ObjectOpenHashSet<RequirementPath>();
        for (var candidate : candidates) {
            Objects.requireNonNull(candidate);
            if (!candidateIds.add(candidate.path(null))) {
                throw new IllegalArgumentException("Duplicate selective candidate identity");
            }
            candidateCopy.add(candidate);
        }
        var modifierCopy = new ObjectArrayList<SelectiveModifierDescriptor>();
        var modifierIds = new ObjectOpenHashSet<ResourceLocation>();
        for (var modifier : modifiers) {
            Objects.requireNonNull(modifier);
            if (!modifierIds.add(modifier.id())) {
                throw new IllegalArgumentException("Duplicate selective modifier id: " + modifier.id());
            }
            modifierCopy.add(modifier);
        }
        candidates = ObjectLists.unmodifiable(candidateCopy);
        modifiers = ObjectLists.unmodifiable(modifierCopy);
    }

    public SelectiveRequirement(List<RecipeRequirement<?>> candidates) {
        this(candidates, ObjectLists.emptyList());
    }
}
