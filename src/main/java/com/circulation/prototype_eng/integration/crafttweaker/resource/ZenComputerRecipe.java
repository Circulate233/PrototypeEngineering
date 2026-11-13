package com.circulation.prototype_eng.integration.crafttweaker.resource;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.network.resource.ComputerRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenResource;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputerRecipe")
@SuppressWarnings("unused")
public record ZenComputerRecipe(ComputerRecipe value) {
    @ZenMethod
    public static ZenComputerRecipe create(String id, ZenResource[] inputsPerTick, ZenComputingResources provided) {
        var inputs = new ObjectArrayList<GenericStack>(inputsPerTick.length);
        for (var resource : inputsPerTick) inputs.add(resource.stack());
        return new ZenComputerRecipe(new ComputerRecipe(new ResourceLocation(id), inputs, provided.value()));
    }
}
