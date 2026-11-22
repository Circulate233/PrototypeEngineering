package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.BrewerRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.BrewerRecipePool")
public final class ZenBrewerRecipePool extends ZenRecipePool {
    private ZenBrewerRecipePool(BrewerRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenBrewerRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenBrewerRecipePool create(String id, String titleKey, String revision, @Nullable ZenBrewerImportCallback onCreate) {
        BrewerRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy) -> onCreate.create(new ZenRecipeBuilder(recipe), energy);
        return new ZenBrewerRecipePool(new BrewerRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
