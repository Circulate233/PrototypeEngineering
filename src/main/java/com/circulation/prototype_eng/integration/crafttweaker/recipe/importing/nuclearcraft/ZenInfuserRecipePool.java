package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.InfuserRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.InfuserRecipePool")
public final class ZenInfuserRecipePool extends ZenRecipePool {
    private ZenInfuserRecipePool(InfuserRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenInfuserRecipePool create(String id, String titleKey, String revision) {
        return new ZenInfuserRecipePool(new InfuserRecipePool(new ResourceLocation(id), titleKey), revision);
    }

    @ZenMethod
    public static ZenInfuserRecipePool create(String id, String titleKey, String revision, ZenNuclearImportCallback onCreate) {
        return new ZenInfuserRecipePool(new InfuserRecipePool(new ResourceLocation(id), titleKey,
            (recipe, context) -> onCreate.create(new ZenRecipeBuilder(recipe), new ZenNuclearImportContext(context))), revision);
    }
}
