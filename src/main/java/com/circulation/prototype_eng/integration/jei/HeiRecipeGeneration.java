package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.config.Config;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.recipes.RecipeRegistry;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/**
 * The HEI 4.35.1 add/hide and tail-map contract, confined to this version-specific integration.
 */
final class HeiRecipeGeneration {
    private HeiRecipeGeneration() {
    }

    static boolean categoryDisabled(String uid) {
        return Config.isRecipeCategoryDisabled(uid);
    }

    /**
     * Candidates are fresh objects: failed hidden objects must never be added again.
     */
    static <R> boolean install(Access<R> access, List<R> previous, List<R> next) {
        try {
            for (int index4 = 0; index4 < next.size(); index4++) {
                var recipe = next.get(index4);
                access.add(recipe);
                // Equal material hashes legitimately overwrite the tail. Confirm each insertion immediately.
                if (!access.confirmed(recipe, true)) {
                    hideAll(access, previous, next);
                    return false;
                }
            }
            for (int index1 = 0; index1 < previous.size(); index1++) {
                var recipe = previous.get(index1);
                access.hide(recipe);
            }
            for (int index2 = 0; index2 < next.size(); index2++) {
                var recipe = next.get(index2);
                if (!access.confirmed(recipe, false)) {
                    hideAll(access, previous, next);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException | Error failure) {
            try {
                hideAll(access, previous, next);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static <R> void hideAll(Access<R> access, List<R> previous, List<R> next) {
        var failure = hide(access, previous, null);
        failure = hide(access, next, failure);
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
    }

    private static <R> Throwable hide(Access<R> access, List<R> recipes, Throwable failure) {
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            try {
                access.hide(recipe);
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        return failure;
    }

    static void closeLayout(Minecraft minecraft, RecipesGui gui) {
        if (minecraft.currentScreen == gui) {
            gui.close();
        }
    }

    interface Access<R> {
        void add(R recipe);

        void hide(R recipe);

        boolean confirmed(R recipe, boolean immediateTail);
    }

    static final class NativeAccess implements Access<RecipePoolJeiRecipe> {
        private final RecipeRegistry registry;

        NativeAccess(RecipeRegistry registry) {
            this.registry = registry;
        }

        private static boolean containsRecipe(Iterable<?> candidates, RecipePoolJeiRecipe recipe) {
            for (var candidate : candidates) if (candidate == recipe) return true;
            return false;
        }

        @Override
        @SuppressWarnings("deprecation") // HEI 4.35.1 runtime wrapper/category add API.
        public void add(RecipePoolJeiRecipe recipe) {
            registry.addRecipe(recipe, recipe.page().getUid());
        }

        @Override
        public void hide(RecipePoolJeiRecipe recipe) {
            if (!categoryDisabled(recipe.page().getUid())) registry.hideRecipe(recipe, recipe.page().getUid());
        }

        @Override
        public boolean confirmed(RecipePoolJeiRecipe recipe, boolean immediateTail) {
            var category = recipe.page();
            if (registry.getRecipeCategory(category.getUid()) != category
                || !containsRecipe(registry.getRecipeWrappers(category), recipe)) return false;
            if (immediateTail && registry.getRecipeById(registry.getRecipeId(recipe), category) != recipe) return false;
            var ingredients = registry.getIngredients(recipe);
            return focuses(recipe, ingredients.getInputIngredients(), IFocus.Mode.INPUT)
                && focuses(recipe, ingredients.getOutputIngredients(), IFocus.Mode.OUTPUT);
        }

        @SuppressWarnings("rawtypes") // HEI's Ingredients exposes flattened maps with erased ingredient types.
        private boolean focuses(RecipePoolJeiRecipe recipe, Map<IIngredientType, List> entries, IFocus.Mode mode) {
            for (var ingredients : entries.values())
                for (int index3 = 0; index3 < ingredients.size(); index3++) {
                    Object ingredient = ingredients.get(index3);
                    if (ingredient != null && !containsRecipe(registry.getRecipeWrappers(recipe.page(), registry.createFocus(mode, ingredient)), recipe))
                        return false;
                }
            return true;
        }
    }
}
