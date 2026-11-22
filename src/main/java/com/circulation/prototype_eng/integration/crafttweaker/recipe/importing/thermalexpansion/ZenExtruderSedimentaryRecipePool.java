package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.ExtruderSedimentaryRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.ExtruderSedimentaryRecipePool")
public final class ZenExtruderSedimentaryRecipePool extends ZenRecipePool {
    private ZenExtruderSedimentaryRecipePool(ExtruderSedimentaryRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenExtruderSedimentaryRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenExtruderSedimentaryRecipePool create(String id, String titleKey, String revision, @Nullable ZenExtruderSedimentaryImportCallback onCreate) {
        ExtruderSedimentaryRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, hotAmount, coldAmount) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, hotAmount, coldAmount);
        return new ZenExtruderSedimentaryRecipePool(new ExtruderSedimentaryRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
