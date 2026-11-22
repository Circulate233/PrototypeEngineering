package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.minecraft;

import com.circulation.prototype_eng.api.recipe.importing.minecraft.FurnaceRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.minecraft.FurnaceRecipePool")
public final class ZenFurnaceRecipePool extends ZenRecipePool {
    private ZenFurnaceRecipePool(FurnaceRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenFurnaceRecipePool create(String id, String titleKey, String revision) {
        return new ZenFurnaceRecipePool(new FurnaceRecipePool(new ResourceLocation(id), titleKey), revision);
    }

    @ZenMethod
    public static ZenFurnaceRecipePool create(String id, String titleKey, String revision, ZenFurnaceImportCallback onCreate) {
        return new ZenFurnaceRecipePool(new FurnaceRecipePool(new ResourceLocation(id), titleKey,
            (recipe, context) -> onCreate.create(new ZenRecipeBuilder(recipe), new ZenFurnaceImportContext(context))), revision);
    }
}
