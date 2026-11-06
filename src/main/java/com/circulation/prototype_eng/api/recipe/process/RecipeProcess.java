package com.circulation.prototype_eng.api.recipe.process;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;

import java.util.Objects;
import java.util.Random;

/**
 * Isolated requirement/extension state for one work slot. A scheduler may run different processes
 * concurrently but must never execute the same process concurrently. Attribute overlays, component
 * persistence and planned operation bindings are still required migration work.
 */
public final class RecipeProcess {
    private final MachineInstance owner;
    private final MachineRecipe recipe;
    private final long seed;
    private final ComponentMap<RecipeProcess> components = new ComponentMap<>();

    public RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed) {
        this.owner = Objects.requireNonNull(owner);
        this.recipe = Objects.requireNonNull(recipe);
        this.seed = seed;
    }

    public MachineInstance owner() { return owner; }
    public MachineRecipe recipe() { return recipe; }
    public long seed() { return seed; }
    public ComponentMap<RecipeProcess> components() { return components; }

    /** Repeatable phase/context selection, matching PM's seed XOR salt convention. */
    public Random random(String salt) { return new Random(seed ^ salt.hashCode()); }
}
