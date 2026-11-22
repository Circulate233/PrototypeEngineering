package com.circulation.prototype_eng.api.recipe.importing.nuclearcraft;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import com.circulation.prototype_eng.integration.recipeimport.nuclearcraft.NuclearCraftRecipeContext;
import com.circulation.prototype_eng.integration.recipeimport.nuclearcraft.NuclearCraftRecipeSources;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.function.BiPredicate;

public final class MelterRecipePool extends ImportedRecipePool<NuclearCraftRecipeContext> {
    public MelterRecipePool(ResourceLocation id, String title) {
        this(id, title, (_, _) -> true);
    }

    public MelterRecipePool(ResourceLocation id, String title,
        BiPredicate<RecipeBuilder, NuclearCraftRecipeContext> onCreate) {
        super(id, title, () -> {
            RecipeImportSupport.requireMod("nuclearcraft");
            return NuclearCraftRecipeSources.readMelter(id);
        }, onCreate);
    }

    private MelterRecipePool(MelterRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        super(original, recipes, machines);
    }

    @Override
    public MelterRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new MelterRecipePool(this, recipes, machines);
    }
}
