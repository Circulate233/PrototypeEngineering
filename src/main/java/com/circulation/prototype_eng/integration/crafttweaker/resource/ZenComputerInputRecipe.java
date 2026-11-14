package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.ComputerInputRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRequirement;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenComputerRecipeCallback;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenComputerRecipeEvent;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.*;
import net.minecraft.util.ResourceLocation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputerInputRecipe")
@SuppressWarnings("unused")
public record ZenComputerInputRecipe(ComputerInputRecipe value) {
    @ZenMethod
    public static ZenRequirement endInput(ZenRequirement input) {
        return new ZenRequirement(ComputerInputRecipe.endInput(input.value()));
    }

    @ZenMethod
    public static ZenComputerInputRecipe create(String id, String revision, int duration, ZenRequirement[] inputs, ZenComputerRecipeCallback callback) {
        var requirements = new ObjectArrayList<RecipeRequirement<?>>(inputs.length);
        for (var input : inputs) requirements.add(input.value());
        return new ZenComputerInputRecipe(new ComputerInputRecipe(new ResourceLocation(id), revision, duration, requirements, event -> callback.apply(new ZenComputerRecipeEvent(event))));
    }
}
