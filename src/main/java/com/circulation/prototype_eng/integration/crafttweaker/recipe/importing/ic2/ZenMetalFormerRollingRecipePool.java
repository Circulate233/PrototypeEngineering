package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.api.recipe.importing.ic2.MetalFormerRollingRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.mc1120.data.NBTConverter;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.MetalFormerRollingRecipePool")
public final class ZenMetalFormerRollingRecipePool extends ZenRecipePool {
    private ZenMetalFormerRollingRecipePool(MetalFormerRollingRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenMetalFormerRollingRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenMetalFormerRollingRecipePool create(String id, String titleKey, String revision, @Nullable ZenIc2ImportCallback onCreate) {
        return new ZenMetalFormerRollingRecipePool(new MetalFormerRollingRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, metadata) -> onCreate.create(new ZenRecipeBuilder(recipe), NBTConverter.from(metadata, true))), revision);
    }
}
