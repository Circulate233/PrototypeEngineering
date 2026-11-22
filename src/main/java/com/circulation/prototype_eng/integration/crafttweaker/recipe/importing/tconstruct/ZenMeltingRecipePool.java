package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.recipe.importing.tconstruct.MeltingRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.tconstruct.MeltingRecipePool")
public final class ZenMeltingRecipePool extends ZenRecipePool {
    private ZenMeltingRecipePool(MeltingRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenMeltingRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenMeltingRecipePool create(String id, String titleKey, String revision, @Nullable ZenMeltingImportCallback onCreate) {
        return new ZenMeltingRecipePool(new MeltingRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, temperature, usableTemperature) -> onCreate.create(new ZenRecipeBuilder(recipe), temperature, usableTemperature)), revision);
    }
}
