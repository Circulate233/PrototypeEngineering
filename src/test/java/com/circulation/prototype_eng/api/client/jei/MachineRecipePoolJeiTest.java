package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.localization.RecipeText;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineRecipePoolJeiTest {
    private static ResourceLocation id(String value) {
        return new ResourceLocation("pool_jei_test", value);
    }

    @Test
    void defaultPageKeepsStaticBindingsWithoutADuplicateMachineList() {
        var recipe = new MachineRecipe(id("recipe"), 10, List.of(), Set.of(id("group")));
        var user = MachineType.builder(id("bound"), RecipeText.Cancelled, id("shape")).recipePool(id("pool")).build();
        var unrelated = MachineType.builder(id("same_group"), RecipeText.Cancelled, id("shape")).recipeGroup(id("group")).build();
        var recipes = new RecipeRegistry();
        recipes.register(new DefaultRecipePool(id("pool"), "pool.jei.test", List.of(recipe)));
        recipes.freeze(List.of(user, unrelated));
        var pool = Objects.requireNonNull(recipes.get(id("pool")));
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        var pages = new RecipePoolJeiRegistry();
        pages.freeze(List.of(pool), current -> JeiTestViews.page(current, new JeiTestViews.Helper(), displays));
        var page = pages.pages().getFirst();
        var display = displays.describe(recipe);
        var wrapper = page.createRecipe(recipe, display, new JeiRecipeIngredients(display, List.of(), List.of(), new JeiQueryIdentityIndex()));
        assertSame(pool, page.pool());
        assertEquals(List.of(user), page.pool().machines());
        assertTrue(wrapper.getTooltipStrings(50, 5).isEmpty());
        assertFalse(wrapper.handleMouseScroll(50, 5, -1));
    }

}
