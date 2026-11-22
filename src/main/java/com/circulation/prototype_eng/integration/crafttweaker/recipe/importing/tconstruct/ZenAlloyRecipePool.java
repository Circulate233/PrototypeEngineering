package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.recipe.importing.tconstruct.AlloyRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeCreateCallback;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.tconstruct.AlloyRecipePool")
public final class ZenAlloyRecipePool extends ZenRecipePool {
    private ZenAlloyRecipePool(AlloyRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenAlloyRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenAlloyRecipePool create(String id, String titleKey, String revision, @Nullable ZenRecipeCreateCallback onCreate) {
        return new ZenAlloyRecipePool(new AlloyRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe) -> onCreate.create(new ZenRecipeBuilder(recipe))), revision);
    }
}
