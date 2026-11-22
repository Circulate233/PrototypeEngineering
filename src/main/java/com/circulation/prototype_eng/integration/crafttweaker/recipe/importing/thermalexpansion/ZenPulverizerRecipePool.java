package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.PulverizerRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.PulverizerRecipePool")
public final class ZenPulverizerRecipePool extends ZenRecipePool {
    private ZenPulverizerRecipePool(PulverizerRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenPulverizerRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenPulverizerRecipePool create(String id, String titleKey, String revision, @Nullable ZenPulverizerImportCallback onCreate) {
        PulverizerRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, secondaryChance) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, secondaryChance);
        return new ZenPulverizerRecipePool(new PulverizerRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
