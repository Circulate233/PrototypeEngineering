package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MelterRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.nuclearcraft.MelterRecipePool")
public final class ZenMelterRecipePool extends ZenRecipePool {
    private ZenMelterRecipePool(MelterRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenMelterRecipePool create(String id, String titleKey, String revision) {
        return new ZenMelterRecipePool(new MelterRecipePool(new ResourceLocation(id), titleKey), revision);
    }

    @ZenMethod
    public static ZenMelterRecipePool create(String id, String titleKey, String revision, ZenNuclearImportCallback onCreate) {
        return new ZenMelterRecipePool(new MelterRecipePool(new ResourceLocation(id), titleKey,
            (recipe, context) -> onCreate.create(new ZenRecipeBuilder(recipe), new ZenNuclearImportContext(context))), revision);
    }
}
