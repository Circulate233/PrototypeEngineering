package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MultiblockElectrolyzerRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ZenImportData;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.MultiblockElectrolyzerRecipePool")
public final class ZenMultiblockElectrolyzerRecipePool extends ZenRecipePool {
    private ZenMultiblockElectrolyzerRecipePool(MultiblockElectrolyzerRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenMultiblockElectrolyzerRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenMultiblockElectrolyzerRecipePool create(String id, String titleKey, String revision, @Nullable ZenMultiblockElectrolyzerImportCallback onCreate) {
        return new ZenMultiblockElectrolyzerRecipePool(new MultiblockElectrolyzerRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, durationTicks, energyPerTick, radiation, extras, electrolyteGroup) -> onCreate.create(new ZenRecipeBuilder(recipe), durationTicks, energyPerTick, radiation, ZenImportData.extras(extras), electrolyteGroup)), revision);
    }
}
