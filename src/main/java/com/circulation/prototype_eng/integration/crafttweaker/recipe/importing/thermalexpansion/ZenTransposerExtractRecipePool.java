package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.TransposerExtractRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.TransposerExtractRecipePool")
public final class ZenTransposerExtractRecipePool extends ZenRecipePool {
    private ZenTransposerExtractRecipePool(TransposerExtractRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenTransposerExtractRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenTransposerExtractRecipePool create(String id, String titleKey, String revision, @Nullable ZenTransposerExtractImportCallback onCreate) {
        TransposerExtractRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, secondaryChance) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, secondaryChance);
        return new ZenTransposerExtractRecipePool(new TransposerExtractRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
