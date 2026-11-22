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
    public ZenRequirement requirement(String id) {
        return new ZenRequirement(value.requirement(ZenRecipeSupport.id(id)));
    }

    @ZenMethod
    public ZenRequirement resources(String id) {
        var requirementId = ZenRecipeSupport.id(id);
        value.resources(requirementId);
        return new ZenRequirement(value.requirement(requirementId));
    }

    @ZenMethod
    public ZenRequirement energy(String id) {
        var requirementId = ZenRecipeSupport.id(id);
        value.energy(requirementId);
        return new ZenRequirement(value.requirement(requirementId));
    }

    @ZenMethod
    public ZenRecipeBuilder replaceRequirement(ZenRequirement requirement) {
        value.replaceRequirement(requirement.value());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder removeRequirement(String id) {
        value.removeRequirement(ZenRecipeSupport.id(id));
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder addInput(String id, ZenResource input) {
        value.addInput(ZenRecipeSupport.id(id), input.stack());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder addOutput(String id, ZenResource output) {
        value.addOutput(ZenRecipeSupport.id(id), output.stack());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder addInputPerTick(String id, ZenResource input) {
        value.addInputPerTick(ZenRecipeSupport.id(id), input.stack());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder addOutputPerTick(String id, ZenResource output) {
        value.addOutputPerTick(ZenRecipeSupport.id(id), output.stack());
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder removeInput(String id, int index) {
        value.removeInput(ZenRecipeSupport.id(id), index);
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder removeOutput(String id, int index) {
        value.removeOutput(ZenRecipeSupport.id(id), index);
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder removeInputPerTick(String id, int index) {
        value.removeInputPerTick(ZenRecipeSupport.id(id), index);
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder removeOutputPerTick(String id, int index) {
        value.removeOutputPerTick(ZenRecipeSupport.id(id), index);
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder clearInputs(String id) {
        value.clearInputs(ZenRecipeSupport.id(id));
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder clearInputs() {
        value.clearInputs();
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder clearOutputs(String id) {
        value.clearOutputs(ZenRecipeSupport.id(id));
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder clearOutputs() {
        value.clearOutputs();
        return this;
    }

    @ZenMethod
    public ZenRecipeBuilder energy(String id, long input, long inputPerTick, long outputPerTick, long output) {
        value.energy(ZenRecipeSupport.id(id), input, inputPerTick, outputPerTick, output);
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
