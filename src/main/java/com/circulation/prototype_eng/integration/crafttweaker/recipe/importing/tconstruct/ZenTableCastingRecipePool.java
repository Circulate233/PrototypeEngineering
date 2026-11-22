package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.tconstruct;

import com.circulation.prototype_eng.api.recipe.importing.tconstruct.TableCastingRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.tconstruct.TableCastingRecipePool")
public final class ZenTableCastingRecipePool extends ZenRecipePool {
    private ZenTableCastingRecipePool(TableCastingRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenTableCastingRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenTableCastingRecipePool create(String id, String titleKey, String revision, @Nullable ZenCastingImportCallback onCreate) {
        return new ZenTableCastingRecipePool(new TableCastingRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, consumesCast, switchOutputs) -> onCreate.create(new ZenRecipeBuilder(recipe), consumesCast, switchOutputs)), revision);
    }
}
