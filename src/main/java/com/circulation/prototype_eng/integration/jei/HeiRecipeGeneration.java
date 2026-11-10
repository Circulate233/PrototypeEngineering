package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.config.Config;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.recipes.RecipeRegistry;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/** The HEI 4.35.1 add/hide and tail-map contract, confined to this version-specific integration. */
final class HeiRecipeGeneration {
    private HeiRecipeGeneration() {}

    static boolean categoryDisabled(String uid) {
        return Config.isRecipeCategoryDisabled(uid);
    }

    interface Access<R> {
        void add(R recipe);
        void hide(R recipe);
        boolean confirmed(R recipe, boolean immediateTail);
    }

    /** Candidates are fresh objects: failed hidden objects must never be added again. */
    static <R> boolean install(Access<R> access, List<R> previous, List<R> next) {
        try {
            for (var recipe : next) {
                access.add(recipe);
                // Equal material hashes legitimately overwrite the tail. Confirm each insertion immediately.
                if (!access.confirmed(recipe, true)) {
                    hideAll(access, previous, next);
                    return false;
                }
            }
            for (var recipe : previous) access.hide(recipe);
            for (var recipe : next) if (!access.confirmed(recipe, false)) {
                hideAll(access, previous, next);
                return false;
            }
            return true;
        } catch (RuntimeException | Error failure) {
            try { hideAll(access, previous, next); }
            catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    static <R> void hideAll(Access<R> access, List<R> previous, List<R> next) {
        Throwable failure = null;
        var all = new ObjectArrayList<>(previous);
        all.addAll(next);
        for (var recipe : all) {
            try { access.hide(recipe); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
    }

    static void closeLayout(Minecraft minecraft, RecipesGui gui) {
        if (minecraft.currentScreen == gui) {
            gui.close();
            if (minecraft.currentScreen == gui) throw new IllegalStateException("HEI could not close the current recipe layout");
        }
    }

    static final class NativeAccess implements Access<RecipePoolJeiRecipe> {
        private final RecipeRegistry registry;
        NativeAccess(RecipeRegistry registry) { this.registry = registry; }

        @Override
        @SuppressWarnings("deprecation") // HEI 4.35.1 runtime wrapper/category add API.
        public void add(RecipePoolJeiRecipe recipe) { registry.addRecipe(recipe, recipe.page().getUid()); }

        @Override
        public void hide(RecipePoolJeiRecipe recipe) {
            if (!categoryDisabled(recipe.page().getUid())) registry.hideRecipe(recipe, recipe.page().getUid());
        }

        @Override
        public boolean confirmed(RecipePoolJeiRecipe recipe, boolean immediateTail) {
            var category = recipe.page();
            if (registry.getRecipeCategory(category.getUid()) != category
                || registry.getRecipeWrappers(category).stream().noneMatch(candidate -> candidate == recipe)) return false;
            if (immediateTail && registry.getRecipeById(registry.getRecipeId(recipe), category) != recipe) return false;
            var ingredients = registry.getIngredients(recipe);
            return focuses(recipe, ingredients.getInputIngredients(), IFocus.Mode.INPUT)
                && focuses(recipe, ingredients.getOutputIngredients(), IFocus.Mode.OUTPUT);
        }

        @SuppressWarnings("rawtypes") // HEI's Ingredients exposes flattened maps with erased ingredient types.
        private boolean focuses(RecipePoolJeiRecipe recipe, Map<IIngredientType, List> entries, IFocus.Mode mode) {
            for (var ingredients : entries.values()) for (Object ingredient : ingredients) {
                if (ingredient != null && registry.getRecipeWrappers(recipe.page(), registry.createFocus(mode, ingredient))
                    .stream().noneMatch(candidate -> candidate == recipe)) return false;
            }
            return true;
        }
    }
}
