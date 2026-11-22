package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Arrays;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Builder")
public record ZenRecipeBuilder(RecipeBuilder value) {

    @ZenMethod
    public static ZenRecipeBuilder create(String id, int durationTicks) {
        return new ZenRecipeBuilder(new RecipeBuilder(ZenRecipeSupport.id(id), durationTicks));
    }

    @ZenMethod
    public static ZenRecipeBuilder from(ZenRecipe recipe) {
        return new ZenRecipeBuilder(RecipeBuilder.from(recipe.recipe()));
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

    @ZenGetter("duration")
    public int duration() {
        return value.durationTicks();
    }

    @ZenGetter("requirements")
    public ZenRequirement[] requirements() {
        return value.requirements().stream().map(ZenRequirement::new).toArray(ZenRequirement[]::new);
    }

    @ZenMethod
    public ZenRecipeBuilder durationTicks(int ticks) {
        value.durationTicks(ticks);
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder setRequirements(ZenRequirement[] requirements) {
        value.requirements(ZenRecipeSupport.requirements(requirements));
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder addRequirement(ZenRequirement requirement) {
        value.addRequirement(requirement.value());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder groups(String[] groups) {
        value.recipeGroups(Arrays.stream(groups).map(ZenRecipeSupport::id).toList());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder maxThreads(int maxThreads) {
        value.maxConcurrentThreads(maxThreads);
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder parallelism(ZenParallelismSelection selection) {
        value.parallelismSelection(selection.value());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder event(String id, String stage, ZenRecipeEventCallback callback) {
        value.event(ZenRecipeSupport.id(id), RecipeStage.valueOf(stage),
            (process, currentStage) -> callback.acquire(new ZenRecipeEventContext(process, currentStage)).value());
        return this;
    }

    @ZenMethod
    public ZenRecipe build(String revision) {
        return new ZenRecipe(value.build(), revision);
    }
}
