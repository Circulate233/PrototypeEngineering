package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.api.recipe.importing.ic2.MaceratorRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.MaceratorRecipePool")
public final class ZenMaceratorRecipePool extends ZenRecipePool {
    private ZenMaceratorRecipePool(MaceratorRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenMaceratorRecipePool create(String id, String titleKey, String revision) {
        return new ZenMaceratorRecipePool(new MaceratorRecipePool(new ResourceLocation(id), titleKey), revision);
    }

    @ZenMethod
    public static ZenMaceratorRecipePool create(String id, String titleKey, String revision, ZenIc2ImportCallback onCreate) {
        return new ZenMaceratorRecipePool(new MaceratorRecipePool(new ResourceLocation(id), titleKey,
            (recipe, context) -> onCreate.create(new ZenRecipeBuilder(recipe), new ZenIc2ImportContext(context))), revision);
    }
}
