package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.api.recipe.importing.ic2.FermenterRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.FermenterRecipePool")
public final class ZenFermenterRecipePool extends ZenRecipePool {
    private ZenFermenterRecipePool(FermenterRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenFermenterRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenFermenterRecipePool create(String id, String titleKey, String revision, @Nullable ZenFermenterImportCallback onCreate) {
        return new ZenFermenterRecipePool(new FermenterRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, heat, biomassPerFertilizer, fertilizer) -> onCreate.create(new ZenRecipeBuilder(recipe), heat,
                biomassPerFertilizer, CraftTweakerMC.getIItemStack(fertilizer))), revision);
    }
}
