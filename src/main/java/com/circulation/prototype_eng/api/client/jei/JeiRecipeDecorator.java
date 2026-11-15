package com.circulation.prototype_eng.api.client.jei;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;


/**
 * Native Java recipe drawing extension; independent custom pages can call the same decorations.
 */
@FunctionalInterface
@SideOnly(Side.CLIENT)
public interface JeiRecipeDecorator {
    void draw(Minecraft minecraft, RecipePoolJeiRecipe recipe, int x, int y);

    record Placement(int x, int y, JeiRecipeDecorator decorator) {

        public void draw(Minecraft minecraft, RecipePoolJeiRecipe recipe) {
            decorator.draw(minecraft, recipe, x, y);
        }
    }
}
