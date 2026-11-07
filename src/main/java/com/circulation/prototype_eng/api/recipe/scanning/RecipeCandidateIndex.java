package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pure optional candidate filtering inside an immutable scope. Empty groups denote the complete
 * global catalog, including ungrouped recipes; nonempty groups denote their union. A nonnull result must include
 * every recipe that could succeed under this index's responsibility; other indices may narrow it
 * further. It must retain scope recipes with no requirement for its own resource type, so an item
 * index cannot discard energy-only or resource-free recipes. Null expresses no opinion; an empty
 * collection rejects the entire scope and must never trigger a global fallback.
 *
 * <p>Results contain only canonical instances from scope. Input groups and scope are immutable.
 * Lookup must not execute START, mutate the machine, consume resources or publish world effects.
 * This contract does not bound arbitrary external lookup CPU work or replace scheduler budgets.
 */
@FunctionalInterface
public interface RecipeCandidateIndex {
    @Nullable
    Collection<MachineRecipe> lookup(MachineInstance machine, Set<ResourceLocation> groups, List<MachineRecipe> scope);
}
