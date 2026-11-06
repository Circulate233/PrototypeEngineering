package com.circulation.prototype_eng.api.recipe.requirement;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/** Identity and typed dispatch binding for a requirement responsibility, not a resource type. */
public final class RecipeRequirementType<C> {
    private final ResourceLocation id;
    private final RecipeRequirementSystem<C> system;

    public RecipeRequirementType(ResourceLocation id, RecipeRequirementSystem<C> system) {
        this.id = Objects.requireNonNull(id);
        this.system = Objects.requireNonNull(system);
    }

    public ResourceLocation id() { return id; }
    public RecipeRequirementSystem<C> system() { return system; }

    /** The payload must be immutable; runtime choices/locks belong to the process's components. */
    public RecipeRequirement<C> requirement(ResourceLocation id, C payload) { return new RecipeRequirement<>(id, this, payload); }
}
