package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CentrifugeMobRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.CentrifugeMobRecipePool")
public final class ZenCentrifugeMobRecipePool extends ZenRecipePool {
    private ZenCentrifugeMobRecipePool(CentrifugeMobRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenCentrifugeMobRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenCentrifugeMobRecipePool create(String id, String titleKey, String revision, @Nullable ZenCentrifugeMobImportCallback onCreate) {
        CentrifugeMobRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, chances) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, chances);
        return new ZenCentrifugeMobRecipePool(new CentrifugeMobRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
