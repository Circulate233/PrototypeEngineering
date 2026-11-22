package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.InsolatorTreeRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.InsolatorTreeRecipePool")
public final class ZenInsolatorTreeRecipePool extends ZenRecipePool {
    private ZenInsolatorTreeRecipePool(InsolatorTreeRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenInsolatorTreeRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenInsolatorTreeRecipePool create(String id, String titleKey, String revision, @Nullable ZenInsolatorImportCallback onCreate) {
        return new ZenInsolatorTreeRecipePool(new InsolatorTreeRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, energy, water, secondaryChance, hasFertilizer) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, water, secondaryChance, hasFertilizer)), revision);
    }
}
