package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.FurnacePyrolysisRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.FurnacePyrolysisRecipePool")
public final class ZenFurnacePyrolysisRecipePool extends ZenRecipePool {
    private ZenFurnacePyrolysisRecipePool(FurnacePyrolysisRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenFurnacePyrolysisRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenFurnacePyrolysisRecipePool create(String id, String titleKey, String revision, @Nullable ZenFurnacePyrolysisImportCallback onCreate) {
        FurnacePyrolysisRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, creosote) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, creosote);
        return new ZenFurnacePyrolysisRecipePool(new FurnacePyrolysisRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
