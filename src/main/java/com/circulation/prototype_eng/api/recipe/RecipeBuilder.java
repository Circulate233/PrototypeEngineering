package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeEventHandler;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeEventRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable creation state. Built recipes retain independent collections and an unchanged identity.
 */
public final class RecipeBuilder {
    private final ResourceLocation id;
    private int durationTicks;
    private final ObjectArrayList<RecipeRequirement<?>> requirements = new ObjectArrayList<>();
    private final ObjectLinkedOpenHashSet<ResourceLocation> groups = new ObjectLinkedOpenHashSet<>();
    private int maxConcurrentThreads = Integer.MAX_VALUE;
    private RecipeParallelismSelection parallelismSelection = RecipeParallelismSelection.NONE;

    public RecipeBuilder(ResourceLocation id, int durationTicks) {
        this.id = Objects.requireNonNull(id, "Recipe identity");
        durationTicks(durationTicks);
    }

    public static RecipeBuilder from(MachineRecipe recipe) {
        return new RecipeBuilder(recipe.id(), recipe.durationTicks()).requirements(recipe.orderedRequirements())
                                                                     .recipeGroups(recipe.recipeGroups()).maxConcurrentThreads(recipe.maxConcurrentThreads())
                                                                     .parallelismSelection(recipe.parallelismSelection());
    }

    public ResourceLocation id() {
        return id;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public RecipeBuilder durationTicks(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative recipe duration");
        durationTicks = value;
        return this;
    }

    public List<RecipeRequirement<?>> requirements() {
        return requirements;
    }

    public RecipeBuilder requirements(Collection<? extends RecipeRequirement<?>> values) {
        if (values == requirements) return this;
        requirements.clear();
        return addRequirements(values);
    }

    public RecipeBuilder addRequirement(RecipeRequirement<?> value) {
        requirements.add(Objects.requireNonNull(value, "Recipe requirement"));
        return this;
    }

    public RecipeBuilder addRequirements(Collection<? extends RecipeRequirement<?>> values) {
        for (var value : values) addRequirement(value);
        return this;
    }

    public Set<ResourceLocation> recipeGroups() {
        return groups;
    }

    public RecipeBuilder recipeGroups(Collection<ResourceLocation> values) {
        if (values == groups) return this;
        groups.clear();
        for (var value : values) groups.add(Objects.requireNonNull(value, "Recipe group"));
        return this;
    }

    public int maxConcurrentThreads() {
        return maxConcurrentThreads;
    }

    public RecipeBuilder maxConcurrentThreads(int value) {
        if (value < 1) throw new IllegalArgumentException("Recipe thread limit must be positive");
        maxConcurrentThreads = value;
        return this;
    }

    @SuppressWarnings("unused")
    public RecipeParallelismSelection parallelismSelection() {
        return parallelismSelection;
    }

    public RecipeBuilder parallelismSelection(RecipeParallelismSelection value) {
        parallelismSelection = Objects.requireNonNull(value, "Recipe parallelism selection");
        return this;
    }

    public RecipeBuilder event(ResourceLocation eventId, RecipeStage stage, RecipeEventHandler handler) {
        return addRequirement(RecipeRequirements.EVENTS.requirement(eventId, new RecipeEventRequirement(stage, handler)));
    }

    public MachineRecipe build() {
        return new MachineRecipe(id, durationTicks, new ObjectArrayList<>(requirements),
            new ObjectArrayList<>(groups), maxConcurrentThreads, parallelismSelection);
    }
}
