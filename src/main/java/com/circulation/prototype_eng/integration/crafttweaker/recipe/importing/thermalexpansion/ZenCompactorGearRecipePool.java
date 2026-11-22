package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorGearRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.CompactorGearRecipePool")
public final class ZenCompactorGearRecipePool extends ZenRecipePool {
    private ZenCompactorGearRecipePool(CompactorGearRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenCompactorGearRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenCompactorGearRecipePool create(String id, String titleKey, String revision, @Nullable ZenCompactorGearImportCallback onCreate) {
        CompactorGearRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy) -> onCreate.create(new ZenRecipeBuilder(recipe), energy);
        return new ZenCompactorGearRecipePool(new CompactorGearRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
