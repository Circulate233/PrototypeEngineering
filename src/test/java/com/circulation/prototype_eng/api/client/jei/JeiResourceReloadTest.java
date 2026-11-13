package com.circulation.prototype_eng.api.client.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class JeiResourceReloadTest {
    private static final KeyType TYPE = new KeyType("reload_resource");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("jei_reload_test", name);
    }

    private static MachineRecipe recipe(String name) {
        return new MachineRecipe(id(name), 20, List.of(
            RecipeRequirements.RESOURCES.requirement(id(name + "_io"), new ResourceRequirement(List.of(), List.of())),
            RecipeRequirements.ENERGY.requirement(id(name + "_fe"), new EnergyRequirement(0, 1, 0, 0, false))));
    }

    private static RecipePool pool(String name, MachineRecipe recipe) {
        return new DefaultRecipePool(id(name), "reload.test.pool", List.of(recipe));
    }

    @Test
    void repeatedCategoryEventsRebuildEventPagesAndWrappersAndKeepClientDeclarations() {
        var recipe = recipe("recipe");
        var defaultPool = pool("default", recipe);
        var eventPool = pool("event", recipe);
        var clientPool = pool("client", recipe);
        var pools = List.of(defaultPool, eventPool, clientPool);
        var pages = new RecipePoolJeiRegistry();
        var displays = new RecipeJeiDisplays();
        var clientPage = new Page(clientPool, new JeiTestViews.Helper());
        pages.register(clientPool.id(), clientPage);
        displays.register(RecipeRequirements.ENERGY, (_, _, builder) ->
            builder.information(JeiRecipeDisplay.Text.of(JeiText.End)));
        RecipePoolJeiPage previousDefault = null;
        RecipePoolJeiRecipe previousWrapper = null;
        for (int cycle = 1; cycle <= 3; cycle++) {
            pages.beginRegistration();
            displays.beginRegistration();
            var helper = new JeiTestViews.Helper();
            var event = new RecipePoolJeiRegistrationEvent(pages, displays, helper, pools);
            var eventPage = new Page(eventPool, event.guiHelper());
            event.pages().register(eventPool.id(), eventPage);
            long amount = cycle;
            event.displays().register(RecipeRequirements.RESOURCES, (_, _, builder) ->
                builder.resource(new GenericStack(new Key(TYPE, "output"), amount), JeiRecipeDisplay.Role.OUTPUT));
            event.displays().freeze();
            event.pages().freeze(pools, current -> JeiTestViews.page(current, helper, displays));
            assertEquals(3, pages.pages().size());
            assertSame(clientPage, pages.pages().get(2));
            assertSame(eventPage, pages.pages().get(1));
            assertSame(helper, eventPage.helper);
            var currentDefault = pages.pages().getFirst();
            assertNotSame(previousDefault, currentDefault);
            var display = displays.describe(recipe);
            assertEquals(cycle, display.resources().getFirst().stack().amount());
            assertTrue(display.information().stream().anyMatch(line -> line.text() == JeiText.End));
            var ingredients = new JeiRecipeIngredients(display, List.of(), List.of(), new JeiQueryIdentityIndex());
            var wrapper = eventPage.createRecipe(recipe, display, ingredients);
            assertSame(eventPage, wrapper.page());
            if (previousWrapper != null) {
                assertNotSame(previousWrapper.page(), wrapper.page());
                assertNotSame(previousWrapper.display(), wrapper.display());
                assertNotSame(previousWrapper.ingredients(), wrapper.ingredients());
            }
            assertThrows(IllegalStateException.class, () -> pages.register(eventPool.id(), eventPage));
            previousDefault = currentDefault;
            previousWrapper = wrapper;
        }
    }

    @Test
    void failedCategoryEventRetryRestoresSeedsAndRejectsDuplicatesAndBindingReentry() {
        var recipe = recipe("retry");
        var clientPool = pool("client", recipe);
        var defaultPool = pool("default", recipe);
        var pools = List.of(clientPool, defaultPool);
        var pages = new RecipePoolJeiRegistry();
        var displays = new RecipeJeiDisplays();
        var helper = new JeiTestViews.Helper();
        var clientPage = new Page(clientPool, helper);
        pages.register(clientPool.id(), clientPage);
        pages.beginRegistration();
        displays.beginRegistration();
        var unknown = pool("unknown", recipe);
        pages.register(unknown.id(), new Page(unknown, helper));
        displays.register(RecipeRequirements.RESOURCES, (_, _, _) -> {
            throw new AssertionError("Failed event provider survived a retry");
        });
        assertThrows(IllegalArgumentException.class, () -> pages.freeze(pools, current -> new Page(current, helper)));
        pages.beginRegistration();
        displays.beginRegistration();
        assertThrows(IllegalArgumentException.class, () -> pages.register(clientPool.id(), clientPage));
        displays.register(RecipeRequirements.RESOURCES, (_, _, builder) -> {
            assertThrows(IllegalStateException.class, displays::beginRegistration);
            builder.information(JeiRecipeDisplay.Text.of(JeiText.Start));
        });
        assertThrows(IllegalArgumentException.class, () -> displays.register(RecipeRequirements.RESOURCES, (_, _, _) -> {
        }));
        displays.freeze();
        pages.freeze(pools, current -> {
            assertThrows(IllegalStateException.class, pages::beginRegistration);
            return JeiTestViews.page(current, helper, displays);
        });
        assertSame(clientPage, pages.pages().getFirst());
        assertEquals(2, pages.pages().size());
        assertTrue(displays.describe(recipe).information().stream().anyMatch(line -> line.text() == JeiText.Start));
    }

    @ParametersAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    private static final class Page implements RecipePoolJeiPage {
        private final RecipePool pool;
        private final IGuiHelper helper;

        private Page(RecipePool pool, IGuiHelper helper) {
            this.pool = pool;
            this.helper = helper;
        }

        @Override
        public RecipePool pool() {
            return pool;
        }

        @Override
        public IDrawable getBackground() {
            return helper.createBlankDrawable(174, 160);
        }

        @Override
        public void setRecipe(IRecipeLayout layout, RecipePoolJeiRecipe recipe, IIngredients ingredients) {
        }
    }
}
