package com.circulation.prototype_eng.api.client.jei;

import ae2.api.integrations.hei.IngredientConverter;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.jei.AutoLayoutRecipePage;
import com.circulation.prototype_eng.integration.jei.RecipeInformationLayout;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IIngredientType;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import mcp.MethodsReturnNonnullByDefault;
import javax.annotation.ParametersAreNonnullByDefault;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class RecipeJeiGenerationTest {
    private static final KeyType TYPE = new KeyType("display_generation");
    private static ResourceLocation id(String value) { return new ResourceLocation("jei_generation", value); }
    private static GenericStack stack(String name, long amount) { return new GenericStack(new Key(TYPE, name), amount); }
    private static MachineRecipe recipe(String name, int count) {
        return new MachineRecipe(id(name), count, List.of(RecipeRequirements.RESOURCES.requirement(id("io"),
            new ResourceRequirement(IntStream.range(0, count).mapToObj(i -> stack("input_" + i, i + 1)).toList(), List.of(stack("out", 2))))));
    }
    private static RecipePool pool(String name, MachineRecipe recipe) { return new DefaultRecipePool(id(name), "jei.generation.pool", List.of(recipe)); }
    private static JeiRecipeIngredients emptyIngredients(JeiRecipeDisplay display) {
        return new JeiRecipeIngredients(display, List.of(), List.of(), new JeiQueryIdentityIndex());
    }

    @Test
    void defaultAndIndependentPagesPrepareNewWrappersBeforeInstallingData() {
        var oldRecipe = recipe("default", 1);
        var nextRecipe = recipe("default", 35);
        var oldCustomRecipe = recipe("custom", 2);
        var nextCustomRecipe = recipe("custom", 3);
        var oldPool = pool("default", oldRecipe);
        var nextPool = pool("default", nextRecipe);
        var oldCustomPool = pool("custom", oldCustomRecipe);
        var nextCustomPool = pool("custom", nextCustomRecipe);
        var displays = measuredDisplays();
        var font = new RecipeInformationLayout.Font(11, (text, _) -> text.arguments().contains(35)
            ? IntStream.range(0, 10).mapToObj(i -> "Line " + i).toList() : List.of("Short"));
        var defaultPage = new AutoLayoutRecipePage(oldPool, new JeiTestViews.Helper(), displays, JeiRenderOptions.defaults(), font);
        var customPage = new UpdatingPage(oldCustomPool);
        var pages = new RecipePoolJeiRegistry();
        pages.register(oldCustomPool.id(), customPage);
        pages.freeze(List.of(oldPool, oldCustomPool), ignored -> defaultPage);
        int oldHeight = defaultPage.getBackground().getHeight();
        var oldDisplay = displays.describe(oldRecipe);
        var oldWrapper = defaultPage.createRecipe(oldRecipe, oldDisplay, emptyIngredients(oldDisplay));
        var oldToken = new RecipePoolJeiRecipe.DisplayToken();
        oldWrapper.bindDisplayToken(oldToken);
        oldToken.activate();
        var descriptions = displays.snapshot(List.of(nextRecipe, nextCustomRecipe));
        var updates = pages.prepareUpdates(List.of(nextPool, nextCustomPool), descriptions);
        var nextDisplay = descriptions.describe(nextRecipe);
        var nextWrapper = updates.getFirst().createRecipe(nextRecipe, nextDisplay, emptyIngredients(nextDisplay));
        var customDisplay = descriptions.describe(nextCustomRecipe);
        var customWrapper = updates.getLast().createRecipe(nextCustomRecipe, customDisplay, emptyIngredients(customDisplay));
        assertSame(oldPool, defaultPage.pool());
        assertSame(oldCustomPool, customPage.pool());
        assertSame(nextPool, nextWrapper.pool());
        assertSame(nextCustomPool, customWrapper.pool());
        assertEquals(oldHeight, defaultPage.getBackground().getHeight());
        assertThrows(IllegalArgumentException.class, () -> new RecipePoolJeiRecipe(defaultPage, nextRecipe, nextDisplay, emptyIngredients(nextDisplay)));
        oldToken.revoke();
        for (var update : updates) update.apply();
        assertThrows(IllegalStateException.class, updates.getFirst()::apply);
        var nextToken = new RecipePoolJeiRecipe.DisplayToken();
        nextWrapper.bindDisplayToken(nextToken);
        customWrapper.bindDisplayToken(nextToken);
        nextToken.activate();
        assertSame(defaultPage, pages.pages().getFirst());
        assertSame(customPage, pages.pages().getLast());
        assertSame(nextPool, defaultPage.pool());
        assertTrue(defaultPage.getBackground().getHeight() > oldHeight);
        assertFalse(oldWrapper.handleMouseScroll(10, 10, -1));
        assertTrue(nextWrapper.handleMouseScroll(10, 10, -1));
        assertEquals(35, Objects.requireNonNull(nextWrapper.patternRecipe()).inputs().size());
        assertTrue(customWrapper.isCurrent());
        assertSame(nextCustomPool, customPage.pool());
    }

    private static RecipeJeiDisplays measuredDisplays() {
        var displays = new RecipeJeiDisplays();
        displays.register(RecipeRequirements.RESOURCES, (recipe, requirement, builder) -> {
            for (var resource : requirement.payload().inputs()) builder.resource(resource, JeiRecipeDisplay.Role.INPUT);
            for (var resource : requirement.payload().outputs()) builder.resource(resource, JeiRecipeDisplay.Role.OUTPUT);
            builder.information(JeiRecipeDisplay.Text.of(JeiText.CustomRequirement, recipe.durationTicks()));
        });
        displays.freeze();
        return displays;
    }

    @Test
    void unchangedPoolGetsTheSameGlobalCollisionDecisionAsReplacedPoolAndRecovers() {
        var first = new MachineRecipe(id("first"), 10, List.of(RecipeRequirements.RESOURCES.requirement(id("first_io"),
            new ResourceRequirement(List.of(stack("alpha", Long.MAX_VALUE)), List.of()))));
        var replacement = new MachineRecipe(id("first"), 10, List.of(RecipeRequirements.RESOURCES.requirement(id("first_io"),
            new ResourceRequirement(List.of(stack("beta", 7)), List.of()))));
        var unchanged = new MachineRecipe(id("unchanged"), 10, List.of(RecipeRequirements.RESOURCES.requirement(id("second_io"),
            new ResourceRequirement(List.of(stack("alpha", 9)), List.of()))));
        var unchangedPool = pool("second", unchanged);
        var displays = new RecipeJeiDisplays(); displays.freeze();
        var pages = new RecipePoolJeiRegistry();
        pages.freeze(List.of(pool("first", first), unchangedPool), source -> JeiTestViews.page(source, new JeiTestViews.Helper(), displays));
        IIngredientType<GenericStack> type = () -> GenericStack.class;
        IngredientConverter<GenericStack> converter = new IngredientConverter<>() {
            @Override public IIngredientType<GenericStack> getIngredientType() { return type; }
            @Override public GenericStack getIngredientFromStack(GenericStack value) { return value; }
            @Override public GenericStack getStackFromIngredient(GenericStack value) { return value; }
        };
        RecipePoolJeiRecipe previous = null;
        RecipePoolJeiRecipe.DisplayToken previousToken = null;
        for (var current : List.of(first, replacement, first)) {
            var snapshot = displays.snapshot(List.of(current, unchanged));
            var all = List.of(snapshot.describe(current), snapshot.describe(unchanged));
            var identities = new JeiQueryIdentityIndex();
            identities.captureConverter(converter, new JeiTestViews.IdentityHelper<>(ignored -> "same_native_uid"), List.of(), all);
            var updates = pages.prepareUpdates(List.of(pool("first", current), unchangedPool), snapshot);
            var token = new RecipePoolJeiRecipe.DisplayToken();
            if (previousToken != null) previousToken.revoke();
            RecipePoolJeiRecipe unchangedWrapper = null;
            for (int i = 0; i < updates.size(); i++) {
                var display = all.get(i);
                var ingredients = new JeiRecipeIngredients(display, List.of(converter), List.of(type), identities);
                var wrapper = updates.get(i).createRecipe(i == 0 ? current : unchanged, display, ingredients);
                wrapper.bindDisplayToken(token);
                boolean collision = current == replacement;
                assertEquals(!collision, ingredients.supports(display.resources().getFirst()));
                assertEquals(collision, ingredients.queryConflict(display.resources().getFirst()));
                updates.get(i).apply();
                if (i == 1) unchangedWrapper = wrapper;
            }
            token.activate();
            if (previous != null) assertFalse(previous.isCurrent());
            previous = Objects.requireNonNull(unchangedWrapper);
            previousToken = token;
            assertSame(unchangedPool, pages.pages().getLast().pool());
            assertEquals(9, previous.display().resources().getFirst().stack().amount());
        }
    }

    @Test
    void sealedDeclarationKeepsItsProviderWhenRegistrationReopensAndUnknownPageRefuses() {
        var recipe = recipe("sealed", 1);
        var displays = new RecipeJeiDisplays();
        displays.beginRegistration();
        displays.register(RecipeRequirements.RESOURCES, (_, _, builder) -> builder.resource(stack("bound", 55), JeiRecipeDisplay.Role.INPUT));
        displays.freeze();
        var sealed = displays.frozenCopy();
        displays.beginRegistration();
        displays.freeze();
        assertEquals(55, sealed.describe(recipe).resources().getFirst().stack().amount());
        assertEquals(1, displays.describe(recipe).resources().getFirst().stack().amount());
        assertThrows(IllegalStateException.class, sealed::beginRegistration);
        var original = pool("unknown", recipe);
        var pages = new RecipePoolJeiRegistry();
        pages.register(original.id(), new StaticPage(original));
        pages.freeze(List.of(original), ignored -> { throw new AssertionError("Unexpected default page"); });
        assertThrows(IllegalStateException.class, () -> pages.prepareUpdates(List.of(original), sealed.snapshot(List.of(recipe))));
    }

    private static class StaticPage implements RecipePoolJeiPage {
        protected RecipePool source;
        private StaticPage(RecipePool source) { this.source = source; }
        @Override public RecipePool pool() { return source; }
        @Override public IDrawable getBackground() { return new JeiTestViews.Blank(100, 100); }
        @Override public void setRecipe(IRecipeLayout layout, RecipePoolJeiRecipe wrapper, IIngredients ingredients) {}
    }

    private static final class UpdatingPage extends StaticPage {
        private UpdatingPage(RecipePool source) { super(source); }
        @Override public PreparedUpdate prepareUpdate(RecipePool target, RecipeJeiDisplays.Snapshot displays) { return new Update(this, source, target); }
        private record Update(UpdatingPage owner, RecipePool expected, RecipePool target) implements PreparedUpdate {
            @Override public RecipePoolJeiPage page() { return owner; }
            @Override public RecipePool pool() { return target; }
            @Override public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
                return new RecipePoolJeiRecipe(owner, target, recipe, display, ingredients);
            }
            @Override public void apply() {
                if (owner.source != expected) throw new IllegalStateException("Independent page preparation is obsolete");
                owner.source = target;
            }
        }
    }
}
