package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.InsolatorRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.InsolatorRecipePool")
public final class ZenInsolatorRecipePool extends ZenRecipePool {
    private ZenInsolatorRecipePool(InsolatorRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenInsolatorRecipePool create(String id, String titleKey, String revision) {
        return new ZenInsolatorRecipePool(new InsolatorRecipePool(new ResourceLocation(id), titleKey), revision);
    }

    @ZenMethod
    public static ZenInsolatorRecipePool create(String id, String titleKey, String revision, ZenInsolatorImportCallback onCreate) {
        return new ZenInsolatorRecipePool(new InsolatorRecipePool(new ResourceLocation(id), titleKey,
            (recipe, context) -> onCreate.create(new ZenRecipeBuilder(recipe), new ZenInsolatorImportContext(context))), revision);
    }
}
