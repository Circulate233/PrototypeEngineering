package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorPlateRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.CompactorPlateRecipePool")
public final class ZenCompactorPlateRecipePool extends ZenRecipePool {
    private ZenCompactorPlateRecipePool(CompactorPlateRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenCompactorPlateRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenCompactorPlateRecipePool create(String id, String titleKey, String revision, @Nullable ZenCompactorPlateImportCallback onCreate) {
        CompactorPlateRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy) -> onCreate.create(new ZenRecipeBuilder(recipe), energy);
        return new ZenCompactorPlateRecipePool(new CompactorPlateRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
