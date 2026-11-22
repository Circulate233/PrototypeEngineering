package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.SmelterRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.SmelterRecipePool")
public final class ZenSmelterRecipePool extends ZenRecipePool {
    private ZenSmelterRecipePool(SmelterRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenSmelterRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenSmelterRecipePool create(String id, String titleKey, String revision, @Nullable ZenSmelterImportCallback onCreate) {
        SmelterRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, secondaryChance, hasFlux) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, secondaryChance, hasFlux);
        return new ZenSmelterRecipePool(new SmelterRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
