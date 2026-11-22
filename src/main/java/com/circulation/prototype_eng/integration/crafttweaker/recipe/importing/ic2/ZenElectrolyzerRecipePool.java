package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.ic2;

import com.circulation.prototype_eng.api.recipe.importing.ic2.ElectrolyzerRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Arrays;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.ic2.ElectrolyzerRecipePool")
public final class ZenElectrolyzerRecipePool extends ZenRecipePool {
    private ZenElectrolyzerRecipePool(ElectrolyzerRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenElectrolyzerRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenElectrolyzerRecipePool create(String id, String titleKey, String revision, @Nullable ZenElectrolyzerImportCallback onCreate) {
        return new ZenElectrolyzerRecipePool(new ElectrolyzerRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : (recipe, energyPerTickEu, outputDirections) -> onCreate.create(new ZenRecipeBuilder(recipe), energyPerTickEu,
                Arrays.stream(outputDirections).map(Enum::name).toArray(String[]::new))), revision);
    }
}
