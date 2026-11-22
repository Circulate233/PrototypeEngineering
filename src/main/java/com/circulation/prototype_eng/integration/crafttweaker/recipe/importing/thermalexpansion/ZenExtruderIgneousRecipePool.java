package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.ExtruderIgneousRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.ExtruderIgneousRecipePool")
public final class ZenExtruderIgneousRecipePool extends ZenRecipePool {
    private ZenExtruderIgneousRecipePool(ExtruderIgneousRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenExtruderIgneousRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenExtruderIgneousRecipePool create(String id, String titleKey, String revision, @Nullable ZenExtruderIgneousImportCallback onCreate) {
        ExtruderIgneousRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, hotAmount, coldAmount) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, hotAmount, coldAmount);
        return new ZenExtruderIgneousRecipePool(new ExtruderIgneousRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
