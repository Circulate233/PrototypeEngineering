package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MultiblockInfiltratorRecipePool;
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
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.MultiblockInfiltratorRecipePool")
public final class ZenMultiblockInfiltratorRecipePool extends ZenRecipePool {
    private ZenMultiblockInfiltratorRecipePool(MultiblockInfiltratorRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenMultiblockInfiltratorRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenMultiblockInfiltratorRecipePool create(String id, String titleKey, String revision, @Nullable ZenMultiblockInfiltratorImportCallback onCreate) {
        return new ZenMultiblockInfiltratorRecipePool(new MultiblockInfiltratorRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, durationTicks, energyPerTick, radiation, extras, heatingFactor) -> onCreate.create(new ZenRecipeBuilder(recipe), durationTicks, energyPerTick, radiation, ZenImportData.extras(extras), heatingFactor)), revision);
    }
}
