package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.integration.jei.AutoLayoutRecipePage;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/** Headless access to real default-page measurement and an independent legacy page's identity cache. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class StaticJeiTestViews {
    private StaticJeiTestViews() {}

    public static AutoLayoutRecipePage page(RecipePool pool, RecipeJeiDisplays.Snapshot descriptions) {
        return new AutoLayoutRecipePage(pool, new JeiTestViews.Helper(), descriptions, JeiRenderOptions.defaults(), JeiTestViews.FONT);
    }

    public static JeiRecipeIngredients ingredients(JeiRecipeDisplay display) {
        return new JeiRecipeIngredients(display, List.of(), List.of(), new JeiQueryIdentityIndex());
    }

    public static RecipePoolJeiPage legacyPage(RecipePool pool, RecipeJeiDisplays.Snapshot descriptions) {
        return new LegacyPage(pool, descriptions);
    }

    private static final class LegacyPage implements RecipePoolJeiPage {
        private final RecipePool pool;
        private final ReferenceOpenHashSet<JeiRecipeDisplay> measured = new ReferenceOpenHashSet<>();

        private LegacyPage(RecipePool pool, RecipeJeiDisplays.Snapshot descriptions) {
            this.pool = pool;
            for (var recipe : pool.recipes()) measured.add(descriptions.describe(recipe));
        }

        @Override public RecipePool pool() { return pool; }
        @Override public IDrawable getBackground() { return new JeiTestViews.Blank(100, 100); }
        @Override public void setRecipe(IRecipeLayout ignoredLayout, RecipePoolJeiRecipe ignoredWrapper, IIngredients ignoredIngredients) {}

        @Override public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            if (!measured.contains(display)) throw new IllegalArgumentException("Legacy page received an unmeasured display");
            return new RecipePoolJeiRecipe(this, recipe, display, ingredients);
        }
    }
}
