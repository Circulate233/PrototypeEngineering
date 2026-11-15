package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.integration.jei.JeiRecipeInputs;
import com.circulation.prototype_eng.integration.jei.RecipePanelLayout;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Native wrapper extension; immutable source data is shared by default and independent custom pages.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class RecipePoolJeiRecipe implements IRecipeWrapper {
    private final RecipePoolJeiPage page;
    private final RecipePool pool;
    private final MachineRecipe recipe;
    private final JeiRecipeDisplay display;
    private final JeiRecipeIngredients ingredients;
    private DisplayToken token;

    public RecipePoolJeiRecipe(RecipePoolJeiPage page, MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
        this(page, page.pool(), recipe, display, ingredients);
    }

    /**
     * The prepared pool is explicit because its category still owns the old data.
     */
    public RecipePoolJeiRecipe(RecipePoolJeiPage page, RecipePool pool, MachineRecipe recipe,
                               JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
        this.page = page;
        this.pool = pool;
        this.recipe = recipe;
        this.display = display;
        this.ingredients = ingredients;
    }

    public RecipePoolJeiPage page() {
        return page;
    }

    public MachineRecipe recipe() {
        return recipe;
    }

    public RecipePool pool() {
        return pool;
    }

    public final boolean isCurrent() {
        return token == null || token.current();
    }

    public final void bindDisplayToken(DisplayToken token) {
        if (this.token != null) throw new IllegalStateException("JEI wrapper already belongs to a generation");
        this.token = token;
    }

    public JeiRecipeDisplay display() {
        return display;
    }

    public JeiRecipeIngredients ingredients() {
        return ingredients;
    }

    /**
     * Complete displayed resources, independent of the visible page; custom wrappers can supply their own grouping.
     */
    public @Nullable JeiPatternRecipe patternRecipe() {
        return new RecipePanelLayout(display, JeiRenderOptions.defaults(), ingredients).patternRecipe(recipe.durationTicks());
    }

    @Override
    public void getIngredients(IIngredients target) {
        ingredients.index(target);
        JeiEnergyIngredients.index(target, display);
    }

    /**
     * Call super when overriding drawInfo so the native Forge scroll bridge sees the drawn bounds.
     */
    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        if (!isCurrent()) return;
        JeiRecipeInputs.drawn(minecraft, this, recipeWidth, recipeHeight, mouseX, mouseY);
    }

    /**
     * Current JEI has no native wrapper wheel callback; the Forge bridge forwards drawn bounds here.
     */
    public boolean handleMouseScroll(int mouseX, int mouseY, int scrollDelta) {
        return false;
    }

    public static final class DisplayToken {
        private boolean current;
        private boolean revoked;

        public boolean current() {
            return current && !revoked;
        }

        public void activate() {
            if (revoked) throw new IllegalStateException("JEI generation has been revoked");
            current = true;
        }

        public void revoke() {
            revoked = true;
            current = false;
        }
    }
}
