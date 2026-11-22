package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.SaltMixerRecipePool;
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
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.SaltMixerRecipePool")
public final class ZenSaltMixerRecipePool extends ZenRecipePool {
    private ZenSaltMixerRecipePool(SaltMixerRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenSaltMixerRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenSaltMixerRecipePool create(String id, String titleKey, String revision, @Nullable ZenNuclearImportCallback onCreate) {
        return new ZenSaltMixerRecipePool(new SaltMixerRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, durationTicks, energyPerTick, radiation, extras) -> onCreate.create(new ZenRecipeBuilder(recipe), durationTicks, energyPerTick, radiation, ZenImportData.extras(extras))), revision);
    }
}
