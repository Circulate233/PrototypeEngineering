package com.circulation.prototype_eng.api.client.jei;

import net.minecraft.client.Minecraft;

import java.util.Objects;

/** Native Java recipe drawing extension; independent custom pages can call the same decorations. */
@FunctionalInterface
public interface JeiRecipeDecorator {
    void draw(Minecraft minecraft, RecipePoolJeiRecipe recipe, int x, int y);

    record Placement(int x, int y, JeiRecipeDecorator decorator) {
        public Placement {
            Objects.requireNonNull(decorator);
        }

        public void draw(Minecraft minecraft, RecipePoolJeiRecipe recipe) {
            decorator.draw(minecraft, recipe, x, y);
        }
    }
}
