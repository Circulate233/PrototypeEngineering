package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.localization.RecipeText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.resources.JeiTestLocale;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;

class MachineRecipePoolJeiTest {
    private static ResourceLocation id(String value) { return new ResourceLocation("pool_jei_test", value); }

    @Test
    void actualDefaultPageTooltipConsumesStaticBindingsWithoutPhysicalItemsOrGroupGuessing() {
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
        JeiTestLocale.withTranslations(Map.of(JeiText.Machines.getTranslationKey(), "Machines (%s)",
            RecipeText.Cancelled.getTranslationKey(), "Unavailable"), () -> {
            var tooltip = wrapper.getTooltipStrings(50, 5);
            assertSame(pool, page.pool());
            assertEquals(List.of(user), page.pool().machines());
            assertEquals("Machines (1)", tooltip.getFirst());
            assertEquals("Unavailable (" + user.id() + ")", tooltip.get(1));
            assertFalse(tooltip.stream().anyMatch(line -> line.contains(unrelated.id().toString())));
            assertTrue(wrapper.getTooltipStrings(0, 5).isEmpty());
        });
    }

}
