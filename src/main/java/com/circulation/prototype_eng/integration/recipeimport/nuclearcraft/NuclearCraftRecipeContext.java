package com.circulation.prototype_eng.integration.recipeimport.nuclearcraft;

import nc.recipe.BasicRecipe;

import java.util.List;

public record NuclearCraftRecipeContext(String group, int sourceIndex, int variantIndex, BasicRecipe source,
                                        double durationTicks, double energyPerTick, double radiation,
                                        List<Object> extras) {
}
