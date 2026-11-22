package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.api.recipe.importing.thaumcraft.InfusionRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thaumcraft.InfusionRecipePool")
public final class ZenInfusionRecipePool extends ZenRecipePool {
    private ZenInfusionRecipePool(InfusionRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenInfusionRecipePool create(String id, String titleKey, String revision) {
        return new ZenInfusionRecipePool(new InfusionRecipePool(new ResourceLocation(id), titleKey), revision);
    }

    @ZenMethod
    public static ZenInfusionRecipePool create(String id, String titleKey, String revision, ZenInfusionImportCallback onCreate) {
        return new ZenInfusionRecipePool(new InfusionRecipePool(new ResourceLocation(id), titleKey,
            (recipe, context) -> onCreate.create(new ZenRecipeBuilder(recipe), new ZenInfusionImportContext(context))), revision);
    }
}
