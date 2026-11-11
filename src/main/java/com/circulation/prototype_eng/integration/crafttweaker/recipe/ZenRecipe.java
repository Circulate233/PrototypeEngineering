package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/** A concrete recipe/revision binding. Later name declarations never modify this recipe. */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Recipe")
public final class ZenRecipe {
    private final RecipeRegistry.Definition definition;
    private final RecipeProcessNbt codec;

    public ZenRecipe(MachineRecipe recipe, String revision) {
        definition = new RecipeRegistry.Definition(Objects.requireNonNull(recipe), revision);
        codec = RecipeProcessNbt.builder(recipe, revision).component(DataComponents.PROCESS, DataComponents.PROCESS_CODEC).build();
    }

    @ZenMethod
    public static ZenRecipe create(String id, String revision, int durationTicks, ZenRequirement[] requirements) {
        return new ZenRecipe(new MachineRecipe(ZenRecipeSupport.id(id), durationTicks, ZenRecipeSupport.requirements(requirements)), revision);
    }

    @ZenMethod
    public static ZenRecipe withGroups(String id, String revision, int durationTicks, ZenRequirement[] requirements,
                                        String[] groups, int maxConcurrentThreads) {
        var ids = new ObjectArrayList<ResourceLocation>(groups.length);
        for (var group : groups) ids.add(ZenRecipeSupport.id(group));
        return new ZenRecipe(new MachineRecipe(ZenRecipeSupport.id(id), durationTicks, ZenRecipeSupport.requirements(requirements),
            ids, maxConcurrentThreads), revision);
    }

    @ZenGetter("id")
    public String id() {
        return recipe().id().toString();
    }

    @ZenGetter("revision")
    public String revision() {
        return definition.revision();
    }

    public MachineRecipe recipe() {
        return definition.recipe();
    }

    public RecipeRegistry.Definition definition() {
        return definition;
    }

    public RecipeProcessNbt codec() {
        return codec;
    }
}
