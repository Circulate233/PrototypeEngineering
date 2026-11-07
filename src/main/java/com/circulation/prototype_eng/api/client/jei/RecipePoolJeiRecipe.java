package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Objects;
import javax.annotation.ParametersAreNonnullByDefault;

import mcp.MethodsReturnNonnullByDefault;

/**
 * Native wrapper extension; immutable source data is shared by default and independent custom pages.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class RecipePoolJeiRecipe implements IRecipeWrapper {
    private final RecipePoolJeiPage page;
    private final MachineRecipe recipe;
    private final JeiRecipeDisplay display;
    private final JeiRecipeIngredients ingredients;

    public RecipePoolJeiRecipe(RecipePoolJeiPage page, MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
        this.page = Objects.requireNonNull(page);
        this.recipe = Objects.requireNonNull(recipe);
        this.display = Objects.requireNonNull(display);
        this.ingredients = Objects.requireNonNull(ingredients);
        if (page.pool().recipes().stream().noneMatch(member -> member == recipe)) {
            throw new IllegalArgumentException("JEI wrapper recipe is outside its canonical pool");
        }
    }

    public RecipePoolJeiPage page() {
        return page;
    }

    public MachineRecipe recipe() {
        return recipe;
    }

    public JeiRecipeDisplay display() {
        return display;
    }

    public JeiRecipeIngredients ingredients() {
        return ingredients;
    }

    @Override
    public void getIngredients(IIngredients target) {
        ingredients.index(target);
    }
}
