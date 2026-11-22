package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.CompactorRecipePool")
public final class ZenCompactorRecipePool extends ZenRecipePool {
    private ZenCompactorRecipePool(CompactorRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenCompactorRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenCompactorRecipePool create(String id, String titleKey, String revision, @Nullable ZenCompactorImportCallback onCreate) {
        CompactorRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy) -> onCreate.create(new ZenRecipeBuilder(recipe), energy);
        return new ZenCompactorRecipePool(new CompactorRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
