package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.TransposerFillRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.TransposerFillRecipePool")
public final class ZenTransposerFillRecipePool extends ZenRecipePool {
    private ZenTransposerFillRecipePool(TransposerFillRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenTransposerFillRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenTransposerFillRecipePool create(String id, String titleKey, String revision, @Nullable ZenTransposerFillImportCallback onCreate) {
        TransposerFillRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, secondaryChance) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, secondaryChance);
        return new ZenTransposerFillRecipePool(new TransposerFillRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
