package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thermalexpansion;

import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.EnchanterRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thermalexpansion.EnchanterRecipePool")
public final class ZenEnchanterRecipePool extends ZenRecipePool {
    private ZenEnchanterRecipePool(EnchanterRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenEnchanterRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenEnchanterRecipePool create(String id, String titleKey, String revision, @Nullable ZenEnchanterImportCallback onCreate) {
        EnchanterRecipePool.OnCreate handler = onCreate == null ? null
            : (recipe, energy, experience, enchantName) -> onCreate.create(new ZenRecipeBuilder(recipe), energy, experience, enchantName);
        return new ZenEnchanterRecipePool(new EnchanterRecipePool(new ResourceLocation(id), titleKey, handler), revision);
    }
}
