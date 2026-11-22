package com.circulation.prototype_eng.integration.recipeimport.ic2;

import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipe;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Collection;

public record Ic2RecipeContext(String group, int sourceIndex,
                               MachineRecipe<IRecipeInput, Collection<ItemStack>> source,
                               NBTTagCompound metadata) {
}
