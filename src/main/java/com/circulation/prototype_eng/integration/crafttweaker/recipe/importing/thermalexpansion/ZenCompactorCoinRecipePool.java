package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorCoinRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.CompactorCoinRecipePool")
public final class ZenCompactorCoinRecipePool extends ZenRecipePool {
    private ZenCompactorCoinRecipePool(CompactorCoinRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenCompactorCoinRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenCompactorCoinRecipePool create(String id, String titleKey, String revision, @Nullable ZenCompactorCoinImportCallback onCreate) {
        CompactorCoinRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy) -> onCreate.create(new ZenRecipeBuilder(recipe), energy);
        return new ZenCompactorCoinRecipePool(new CompactorCoinRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
