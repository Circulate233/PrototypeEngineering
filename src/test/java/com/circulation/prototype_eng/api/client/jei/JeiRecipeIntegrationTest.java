package com.circulation.prototype_eng.api.client.jei;

import ae2.api.integrations.hei.IngredientConverter;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.jei.AutoLayoutRecipePage;
import com.circulation.prototype_eng.integration.jei.RecipeInformationLayout;
import com.circulation.prototype_eng.integration.jei.RecipePanelLayout;
import com.circulation.prototype_eng.integration.jei.SingleInputRecipePage;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IIngredientType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class JeiRecipeIntegrationTest {
    private static final KeyType ITEM = new KeyType("jei_item_like");
    private static final KeyType FLUID = new KeyType("jei_fluid_like");
    private static final IIngredientType<GenericStack> ITEM_INGREDIENT = new IngredientType();
    private static final IIngredientType<GenericStack> FLUID_INGREDIENT = new IngredientType();

    private static ResourceLocation id(String name) {
        return new ResourceLocation("jei_test", name);
    }

    private static GenericStack stack(KeyType type, String name, long amount) {
        return new GenericStack(new Key(type, name), amount);
    }

    private static IngredientConverter<GenericStack> converter(KeyType keyType, IIngredientType<GenericStack> ingredientType) {
        return new IngredientConverter<>() {
            @Override
            public IIngredientType<GenericStack> getIngredientType() {
                return ingredientType;
            }

            @Override
            public @Nullable GenericStack getIngredientFromStack(GenericStack stack) {
                return stack.what().getType() == keyType ? stack : null;
            }

            @Override
            public GenericStack getStackFromIngredient(GenericStack ingredient) {
                return ingredient;
            }
        };
    }

    private static JeiRecipeIngredients ingredients(JeiRecipeDisplay display) {
        var converters = ObjectArrayList.<IngredientConverter<?>>of(converter(ITEM, ITEM_INGREDIENT), converter(FLUID, FLUID_INGREDIENT));
        return new JeiRecipeIngredients(display, converters, ObjectArrayList.of(ITEM_INGREDIENT, FLUID_INGREDIENT), identities(display, converters));
    }

    private static JeiQueryIdentityIndex identities(JeiRecipeDisplay display, List<IngredientConverter<?>> converters) {
        var index = new JeiQueryIdentityIndex();
        for (var converter : converters) {
            captureFixture(index, converter, display);
        }
        return index;
    }

    private static <T> void captureFixture(JeiQueryIdentityIndex index, IngredientConverter<T> converter, JeiRecipeDisplay display) {
        index.captureConverter(converter, new JeiTestViews.IdentityHelper<>(ingredient ->
                Objects.requireNonNull(converter.getStackFromIngredient(ingredient)).what().toTag().toString()),
            ObjectArrayList.of(), ObjectArrayList.of(display));
    }

    private static MachineRecipe recipe(String name, List<GenericStack> inputs, List<GenericStack> outputs) {
        return new MachineRecipe(id(name), 10, ObjectArrayList.of(RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(inputs, outputs))));
    }

    private static RecipeJeiDisplays displays() {
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        return displays;
    }

    private static RecipePool pool(String name, MachineRecipe... recipes) {
        return new DefaultRecipePool(id(name), "jei.test.pool", ObjectArrayList.of(recipes));
    }

    @Test
    void nativeGroupsKeepMixedTypesAndLongSourceAmountsAndClearUnusedRows() {
        var inputItem = stack(ITEM, "in_item", Long.MAX_VALUE);
        var inputFluid = stack(FLUID, "in_fluid", 2_000_000_001L);
        var outputItem = stack(ITEM, "out_item", 7);
        var outputFluid = stack(FLUID, "out_fluid", 8);
        var display = displays().describe(recipe("mixed", ObjectArrayList.of(inputItem, inputFluid), ObjectArrayList.of(outputFluid, outputItem)));
        var ingredients = ingredients(display);
        var target = new JeiTestViews.Ingredients();
        ingredients.index(target);
        assertEquals(1, target.getInputs(ITEM_INGREDIENT).size());
        assertEquals(1, target.getOutputs(ITEM_INGREDIENT).size());
        assertEquals(1, target.getInputs(FLUID_INGREDIENT).size());
        assertEquals(1, target.getOutputs(FLUID_INGREDIENT).size());
        assertEquals(Long.MAX_VALUE, display.resources().getFirst().stack().amount());
        assertEquals(1, target.getInputs(ITEM_INGREDIENT).getFirst().getFirst().amount());
        var panels = new RecipePanelLayout(display);
        var layout = new JeiTestViews.Layout();
        ingredients.initialize(layout, 4, 4, 4, 0, 100, 20, 18);
        ingredients.show(layout, panels.inputs(0), panels.outputs(0), 4, 4);
        assertSame(inputItem.what(), layout.group(ITEM_INGREDIENT).values.get(0).getFirst().what());
        assertSame(inputFluid.what(), layout.group(FLUID_INGREDIENT).values.get(1).getFirst().what());
        assertSame(outputFluid.what(), layout.group(FLUID_INGREDIENT).values.get(4).getFirst().what());
        assertSame(outputItem.what(), layout.group(ITEM_INGREDIENT).values.get(5).getFirst().what());
        assertTrue(layout.group(ITEM_INGREDIENT).roles.get(0));
        assertFalse(layout.group(FLUID_INGREDIENT).roles.get(4));
        ingredients.show(layout, ObjectArrayList.of(), ObjectArrayList.of(), 4, 4);
        assertTrue(layout.group(ITEM_INGREDIENT).values.isEmpty());
        assertTrue(layout.group(FLUID_INGREDIENT).values.isEmpty());
    }

    @Test
    void convertersRequireRegisteredTypeAndExactAeKeyRoundTripForQueryIndexing() {
        var source = stack(ITEM, "exact", 42);
        var display = displays().describe(recipe("identity", ObjectArrayList.of(source), ObjectArrayList.of()));
        var nativeConverters = ObjectArrayList.<IngredientConverter<?>>of(converter(ITEM, ITEM_INGREDIENT));
        var unregistered = new JeiRecipeIngredients(display, nativeConverters, ObjectArrayList.of(), identities(display, nativeConverters));
        assertFalse(unregistered.supports(display.resources().getFirst()));
        var wrongKey = stack(ITEM, "different_nbt_identity", 1);
        var wrong = new IngredientConverter<GenericStack>() {
            @Override
            public IIngredientType<GenericStack> getIngredientType() {
                return ITEM_INGREDIENT;
            }

            @Override
            public GenericStack getIngredientFromStack(GenericStack ignored1) {
                return wrongKey;
            }

            @Override
            public GenericStack getStackFromIngredient(GenericStack ingredient) {
                return ingredient;
            }
        };
        var invalidConverters = ObjectArrayList.<IngredientConverter<?>>of(wrong);
        var invalid = new JeiRecipeIngredients(display, invalidConverters, ObjectArrayList.of(ITEM_INGREDIENT), identities(display, invalidConverters));
        assertFalse(invalid.supports(display.resources().getFirst()));
        var target = new JeiTestViews.Ingredients();
        invalid.index(target);
        assertTrue(target.getInputs(ITEM_INGREDIENT).isEmpty());
    }

    @Test
    void actualDefaultWrapperPagesAllResourcesWhileQueryIndexIncludesHiddenPanels() {
        var inputStacks = new ObjectArrayList<GenericStack>();
        var outputStacks = new ObjectArrayList<GenericStack>();
        for (int i = 0; i < 35; i++) {
            inputStacks.add(stack(ITEM, "in" + i, i + 1L));
        }
        for (int i = 0; i < 19; i++) {
            outputStacks.add(stack(FLUID, "out" + i, i + 1L));
        }
        var recipe = recipe("overflow", inputStacks, outputStacks);
        var displays = displays();
        var display = displays.describe(recipe);
        var smallerInput = stack(FLUID, "small_input", 2);
        var smallerOutput = stack(ITEM, "small_output", 3);
        var smaller = recipe("smaller", ObjectArrayList.of(smallerInput), ObjectArrayList.of(smallerOutput));
        var pool = pool("default", recipe, smaller);
        var page = JeiTestViews.page(pool, new JeiTestViews.Helper(), displays);
        var wrapper = page.createRecipe(recipe, display, ingredients(display));
        var indexed = new JeiTestViews.Ingredients();
        wrapper.getIngredients(indexed);
        assertEquals(35, indexed.getInputs(ITEM_INGREDIENT).size());
        assertEquals(19, indexed.getOutputs(FLUID_INGREDIENT).size());
        var layout = new JeiTestViews.Layout();
        page.setRecipe(layout, wrapper, indexed);
        assertEquals(16, layout.group(ITEM_INGREDIENT).values.size());
        assertEquals(16, layout.group(FLUID_INGREDIENT).values.size());
        assertTrue(wrapper.handleMouseScroll(80, page.getBackground().getHeight() - 1, -1));
        assertEquals(16, layout.group(ITEM_INGREDIENT).values.size());
        assertEquals(3, layout.group(FLUID_INGREDIENT).values.size());
        assertSame(inputStacks.get(16).what(), layout.group(ITEM_INGREDIENT).values.get(0).getFirst().what());
        assertTrue(wrapper.handleMouseScroll(80, page.getBackground().getHeight() - 1, -1));
        assertEquals(3, layout.group(ITEM_INGREDIENT).values.size());
        assertTrue(layout.group(FLUID_INGREDIENT).values.isEmpty());
        assertSame(inputStacks.get(32).what(), layout.group(ITEM_INGREDIENT).values.get(0).getFirst().what());
        var smallerDisplay = displays.describe(smaller);
        var smallerWrapper = page.createRecipe(smaller, smallerDisplay, ingredients(smallerDisplay));
        var smallerIndexed = new JeiTestViews.Ingredients();
        smallerWrapper.getIngredients(smallerIndexed);
        var smallerLayout = new JeiTestViews.Layout();
        page.setRecipe(smallerLayout, smallerWrapper, smallerIndexed);
        assertEquals(1, smallerLayout.group(FLUID_INGREDIENT).values.size());
        assertEquals(1, smallerLayout.group(ITEM_INGREDIENT).values.size());
        assertSame(smallerInput.what(), smallerLayout.group(FLUID_INGREDIENT).values.get(0).getFirst().what());
        assertSame(smallerOutput.what(), smallerLayout.group(ITEM_INGREDIENT).values.get(1).getFirst().what());
        assertEquals(3, layout.group(ITEM_INGREDIENT).values.size());
        assertTrue(layout.group(FLUID_INGREDIENT).values.isEmpty());
        assertEquals(35, indexed.getInputs(ITEM_INGREDIENT).size());
        var all = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        var importSource = Objects.requireNonNull(wrapper.patternRecipe());
        assertEquals(35, importSource.inputs().size());
        assertEquals(19, importSource.outputs().size());
        assertEquals(inputStacks.get(20), importSource.inputs().get(20).getFirst());
        var panels = new RecipePanelLayout(display);
        for (int i = 0; i < panels.pageCount(); i++) {
            all.addAll(panels.inputs(i));
            all.addAll(panels.outputs(i));
        }
        assertEquals(54, all.size());
    }

    @Test
    void nativeConversionWinsAndFallbackSharesItsIngredientGroupWithoutReplacingNativeEntries() {
        var nativeInput = stack(ITEM, "native", 7);
        var genericInput = stack(FLUID, "generic", Long.MAX_VALUE);
        var genericOutput = stack(FLUID, "generic_output", Long.MAX_VALUE - 1);
        var display = displays().describe(recipe("fallback", List.of(nativeInput, genericInput), List.of(genericOutput)));
        var converters = List.<IngredientConverter<?>>of(converter(ITEM, ITEM_INGREDIENT), converter(FLUID, FLUID_INGREDIENT));
        var fallbackCalls = new ObjectArrayList<GenericStack>();
        // Exercise routing/indexing with a pure carrier. Production uses AE2S GenericStack.wrapInItemStack.
        var fallback = new IngredientConverter<GenericStack>() {
            @Override
            public IIngredientType<GenericStack> getIngredientType() {
                return ITEM_INGREDIENT;
            }

            @Override
            public GenericStack getIngredientFromStack(GenericStack stack) {
                fallbackCalls.add(stack);
                return stack;
            }

            @Override
            public GenericStack getStackFromIngredient(GenericStack stack) {
                return stack;
            }
        };
        var bridge = new JeiRecipeIngredients(display, converters, List.of(ITEM_INGREDIENT, FLUID_INGREDIENT), identities(display, converters),
            fallback, key -> key.getType() == ITEM);
        assertEquals(List.of(genericInput, genericOutput), fallbackCalls);
        assertTrue(display.resources().stream().allMatch(bridge::supports));
        var indexed = new JeiTestViews.Ingredients();
        bridge.index(indexed);
        assertEquals(2, indexed.getInputs(ITEM_INGREDIENT).size());
        assertTrue(indexed.getInputs(FLUID_INGREDIENT).isEmpty());
        assertEquals(1, indexed.getInputs(ITEM_INGREDIENT).getFirst().getFirst().amount());
        assertEquals(Long.MAX_VALUE, indexed.getInputs(ITEM_INGREDIENT).getLast().getFirst().amount());
        assertEquals(List.of(List.of(genericOutput)), indexed.getOutputs(ITEM_INGREDIENT));
        var layout = new JeiTestViews.Layout();
        bridge.initialize(layout, 2, 1, 2, 0, 100, 20, 18);
        bridge.show(layout, display.resources().subList(0, 2), display.resources().subList(2, 3), 2, 1);
        assertEquals(3, layout.group(ITEM_INGREDIENT).values.size());
        assertSame(nativeInput.what(), layout.group(ITEM_INGREDIENT).values.get(0).getFirst().what());
        assertEquals(genericInput, layout.group(ITEM_INGREDIENT).values.get(1).getFirst());
        assertEquals(genericOutput, layout.group(ITEM_INGREDIENT).values.get(2).getFirst());
        bridge.show(layout, List.of(display.resources().getFirst()), List.of(), 2, 1);
        assertEquals(1, layout.group(ITEM_INGREDIENT).values.size());
    }

    @Test
    void fallbackCreatesItsGroupWhenNoNativeConverterAcceptsTheKey() {
        var source = stack(FLUID, "fallback_only", Long.MAX_VALUE);
        var display = displays().describe(recipe("fallback_only", List.of(source), List.of()));
        var fallback = converter(FLUID, ITEM_INGREDIENT);
        var bridge = new JeiRecipeIngredients(display, List.of(), List.of(ITEM_INGREDIENT), new JeiQueryIdentityIndex(), fallback);
        var indexed = new JeiTestViews.Ingredients();
        bridge.index(indexed);
        assertTrue(bridge.supports(display.resources().getFirst()));
        assertEquals(List.of(List.of(source)), indexed.getInputs(ITEM_INGREDIENT));
        var layout = new JeiTestViews.Layout();
        bridge.initialize(layout, 1, 0, 1, 0, 100, 20, 18);
        bridge.show(layout, display.resources(), List.of(), 1, 0);
        assertEquals(source, layout.group(ITEM_INGREDIENT).values.get(0).getFirst());
        bridge.show(layout, List.of(), List.of(), 1, 0);
        assertTrue(layout.group(ITEM_INGREDIENT).values.isEmpty());
    }

    @Test
    void twoActualPoolPathsShareNativeDataWhileIndependentCustomPageReplacesOnlyItsPool() {
        var first = recipe("first", ObjectArrayList.of(stack(ITEM, "input_a", 2)), ObjectArrayList.of());
        var second = recipe("second", ObjectArrayList.of(), ObjectArrayList.of(stack(FLUID, "output_b", 4)));
        var defaultPool = pool("default", first);
        var customPool = pool("custom", second);
        var displays = displays();
        var pages = new RecipePoolJeiRegistry();
        var custom = new IndependentPage(customPool);
        pages.register(customPool.id(), custom);
        pages.freeze(ObjectArrayList.of(defaultPool, customPool), pool -> JeiTestViews.page(pool, new JeiTestViews.Helper(), displays));
        assertInstanceOf(AutoLayoutRecipePage.class, pages.pages().getFirst());
        assertSame(custom, pages.pages().getLast());
        assertNotSame(pages.pages().getFirst(), custom);
        var display = displays.describe(second);
        var wrapper = custom.createRecipe(second, display, ingredients(display));
        var nativeIngredients = new JeiTestViews.Ingredients();
        wrapper.getIngredients(nativeIngredients);
        custom.setRecipe(new JeiTestViews.Layout(), wrapper, nativeIngredients);
        assertEquals(1, nativeIngredients.getOutputs(FLUID_INGREDIENT).size());
        assertTrue(wrapper.handleMouseScroll(30, 40, -1));
        assertTrue(custom.clicked);
        assertThrows(IllegalStateException.class, () -> pages.register(defaultPool.id(), custom));
    }

    @Test
    void conflictingOrCounterfeitBindingsDoNotPublishPartialPoolCategories() {
        var pool = pool("pool", recipe("recipe", ObjectArrayList.of(), ObjectArrayList.of()));
        var pages = new RecipePoolJeiRegistry();
        var custom = new IndependentPage(pool);
        pages.register(pool.id(), custom);
        assertThrows(IllegalArgumentException.class, () -> pages.register(pool.id(), new IndependentPage(pool)));
        var equivalent = new DefaultRecipePool(pool.id(), pool.translationKey(), pool.recipes());
        assertThrows(IllegalArgumentException.class, () -> pages.freeze(ObjectArrayList.of(equivalent), IndependentPage::new));
        assertThrows(IllegalStateException.class, pages::pages);
        pages.freeze(ObjectArrayList.of(pool), IndependentPage::new);
        assertSame(custom, pages.pages().getFirst());
    }

    @Test
    void allMaterialPagesRepeatCompleteInformationAndRejectInvalidPageIndexes() {
        var builder = new JeiRecipeDisplay.Builder();
        var scoped = builder.scoped(JeiRecipeDisplay.Text.of(JeiText.CheckpointScaled, id("checkpoint"), 30),
            JeiRecipeDisplay.Text.of(JeiText.End));
        for (int i = 0; i < 37; i++) {
            scoped.information(JeiRecipeDisplay.Text.of(JeiText.EnergyInput, i + 1));
        }
        var display = builder.build();
        var layout = new RecipePanelLayout(display);
        assertEquals(1, layout.pageCount());
        assertEquals(display.information(), layout.information(0));
        assertEquals(2, layout.information(0).getLast().context().size());
        assertSame(JeiText.End, layout.information(0).getLast().context().getLast().text());
        for (int i = 0; i < 33; i++) builder.resource(stack(ITEM, "input" + i, 1), JeiRecipeDisplay.Role.INPUT);
        var materialPages = new RecipePanelLayout(builder.build());
        assertEquals(3, materialPages.pageCount());
        for (int i = 0; i < materialPages.pageCount(); i++)
            assertEquals(display.information(), materialPages.information(i));
        assertThrows(IllegalArgumentException.class, () -> layout.information(-1));
        assertThrows(IllegalArgumentException.class, () -> layout.information(layout.pageCount()));
    }

    @Test
    void poolHeightFitsWrappedExplanationsOnEveryMaterialPageAndRebuildsWithFontMetrics() {
        var stacks = new ObjectArrayList<GenericStack>();
        for (int i = 0; i < 33; i++) stacks.add(stack(ITEM, "wide" + i, i + 1));
        var large = recipe("large", stacks, List.of());
        var small = recipe("small", List.of(stacks.getFirst()), List.of());
        var pool = pool("measured", small, large);
        var displays = new RecipeJeiDisplays();
        displays.register(RecipeRequirements.RESOURCES, (recipe, requirement, builder) -> {
            for (var stack : requirement.payload().inputs()) builder.resource(stack, JeiRecipeDisplay.Role.INPUT);
            builder.information(JeiRecipeDisplay.Text.of(JeiText.CustomRequirement, recipe == large ? "long" : "short"));
        });
        displays.freeze();
        var longLines = List.of("Line one", "Line two", "Line three", "Line four", "Line five", "Line six", "Last line");
        var font = new RecipeInformationLayout.Font(11, (text, width) -> {
            assertEquals(166, width);
            return text.arguments().contains("long") ? longLines : List.of("Short explanation");
        });
        var page = new AutoLayoutRecipePage(pool, new JeiTestViews.Helper(), displays, JeiRenderOptions.defaults(), font);
        assertTrue(page.getBackground().getHeight() > 160);
        assertEquals(16, page.inputLayout().size());
        assertEquals(0, page.outputLayout().size());
        var display = displays.describe(large);
        var wrapper = page.createRecipe(large, display, ingredients(display));
        var layout = new JeiTestViews.Layout();
        page.setRecipe(layout, wrapper, new JeiTestViews.Ingredients());
        int nearBottom = page.getBackground().getHeight() - 25;
        for (int i = 0; i < 3; i++) {
            assertTrue(wrapper.getTooltipStrings(2, nearBottom).isEmpty());
            assertTrue(wrapper.getTooltipStrings(4, 80).isEmpty());
            assertTrue(wrapper.getTooltipStrings(80, 36).isEmpty());
            assertFalse(wrapper.handleMouseScroll(10, page.getBackground().getHeight(), -1));
            assertTrue(wrapper.handleMouseScroll(10, page.getBackground().getHeight() - 1, -1));
        }
        assertEquals(33, Objects.requireNonNull(wrapper.patternRecipe()).inputs().size());
        var smallerFont = new RecipeInformationLayout.Font(9, (_, _) -> List.of("Short translation"));
        var reloaded = new AutoLayoutRecipePage(pool, new JeiTestViews.Helper(), displays, JeiRenderOptions.defaults(), smallerFont);
        assertTrue(reloaded.getBackground().getHeight() < page.getBackground().getHeight());
    }

    @Test
    void singleInputPageKeepsItsInputWhileReplacingNativeOutputSlotsAndAcceptsPreparedDefinitions() {
        var input = stack(ITEM, "single_input", 5);
        var outputs = new ObjectArrayList<GenericStack>();
        for (int i = 0; i < 27; i++) outputs.add(stack(i % 2 == 0 ? ITEM : FLUID, "many_out" + i, Long.MAX_VALUE - i));
        var entry = SimpleRecipePool.Entry.create(id("single_many"), 10, input, outputs, Set.of());
        var pool = new SimpleRecipePool(id("single_many_pool"), "pool.title", List.of(entry));
        var displays = displays();
        var display = displays.describe(entry.recipe());
        var page = new SingleInputRecipePage(pool, new JeiTestViews.Helper(), displays, JeiRenderOptions.defaults(), JeiTestViews.FONT);
        var wrapper = page.createRecipe(entry.recipe(), display, ingredients(display));
        var indexed = new JeiTestViews.Ingredients();
        wrapper.getIngredients(indexed);
        var layout = new JeiTestViews.Layout();
        page.setRecipe(layout, wrapper, indexed);
        assertEquals(1, page.inputLayout().size());
        assertEquals(24, page.outputLayout().size());
        assertEquals(25, layout.group(ITEM_INGREDIENT).getGuiIngredients().size());
        int previousInputX = layout.group(ITEM_INGREDIENT).slots.get(0).x();
        int oldHeight = page.getBackground().getHeight();
        assertTrue(wrapper.handleMouseScroll(10, oldHeight - 1, -1));
        for (var type : List.of(ITEM_INGREDIENT, FLUID_INGREDIENT)) {
            var group = layout.group(type);
            assertEquals(4, group.getGuiIngredients().size());
            assertFalse(group.getGuiIngredients().containsKey(4));
            assertEquals(1, group.tooltips.size());
        }
        assertNotEquals(previousInputX, layout.group(ITEM_INGREDIENT).slots.get(0).x());
        assertSame(input.what(), layout.group(ITEM_INGREDIENT).values.get(0).getFirst().what());
        assertSame(outputs.get(24).what(), layout.group(ITEM_INGREDIENT).values.get(1).getFirst().what());
        assertSame(outputs.get(25).what(), layout.group(FLUID_INGREDIENT).values.get(2).getFirst().what());
        assertEquals(27, indexed.getOutputs(ITEM_INGREDIENT).size() + indexed.getOutputs(FLUID_INGREDIENT).size());
        assertEquals(outputs.getLast(), Objects.requireNonNull(wrapper.patternRecipe()).outputs().getLast().getFirst());
        assertEquals(1, Objects.requireNonNull(wrapper.patternRecipe()).inputs().size());

        var replacement = SimpleRecipePool.Entry.create(entry.recipe().id(), 10, input, List.of(outputs.getFirst()), Set.of());
        var target = pool.freeze(List.of(replacement.recipe()), List.of());
        var snapshot = displays.capture(target.recipes());
        var prepared = Objects.requireNonNull(page.prepareUpdate(target, snapshot));
        var nextDisplay = snapshot.describe(replacement.recipe());
        var next = prepared.createRecipe(replacement.recipe(), nextDisplay, ingredients(nextDisplay));
        prepared.apply();
        assertSame(target, page.pool());
        assertEquals(1, page.outputLayout().size());
        var nextLayout = new JeiTestViews.Layout();
        page.setRecipe(nextLayout, next, new JeiTestViews.Ingredients());
        assertEquals(2, nextLayout.group(ITEM_INGREDIENT).getGuiIngredients().size());
        assertFalse(next.handleMouseScroll(10, page.getBackground().getHeight() - 1, -1));
        assertTrue(wrapper.handleMouseScroll(10, oldHeight - 1, 1));
        assertEquals(25, layout.group(ITEM_INGREDIENT).getGuiIngredients().size());
        assertThrows(IllegalStateException.class, prepared::apply);
    }

    @Test
    void auxiliaryInputsRemainVisibleAcrossPagesAndCompleteInQueriesAndPatterns() {
        var input = stack(ITEM, "auxiliary_main", 5);
        var auxiliary = new ObjectArrayList<GenericStack>();
        auxiliary.add(stack(FLUID, "water", 1));
        for (int i = 0; i < RecipePanelLayout.RESOURCE_CAPACITY * 2; i++)
            auxiliary.add(stack(i % 2 == 0 ? ITEM : FLUID, "auxiliary_" + i, i + 2));
        var outputs = new ObjectArrayList<GenericStack>();
        for (int i = 0; i < RecipePanelLayout.RESOURCE_CAPACITY + 3; i++)
            outputs.add(stack(i % 2 == 0 ? ITEM : FLUID, "auxiliary_output_" + i, i + 1));
        var entry = SimpleRecipePool.Entry.create(id("auxiliary"), 10, input, outputs, Set.of());
        var pool = new SimpleRecipePool(id("auxiliary_pool"), "pool.title", List.of(entry)).withAuxiliaryInputs(auxiliary);
        var recipe = pool.recipes().getFirst();
        var displays = displays();
        var display = displays.describe(recipe);
        var page = new SingleInputRecipePage(pool, new JeiTestViews.Helper(), displays, JeiRenderOptions.defaults(), JeiTestViews.FONT);
        var wrapper = page.createRecipe(recipe, display, ingredients(display));
        var indexed = new JeiTestViews.Ingredients();
        wrapper.getIngredients(indexed);
        assertEquals(auxiliary.size() + 1,
            indexed.getInputs(ITEM_INGREDIENT).size() + indexed.getInputs(FLUID_INGREDIENT).size());
        assertEquals(outputs.size(), indexed.getOutputs(ITEM_INGREDIENT).size() + indexed.getOutputs(FLUID_INGREDIENT).size());
        var expectedInputs = new ObjectArrayList<GenericStack>();
        expectedInputs.add(input);
        expectedInputs.addAll(auxiliary);
        var pattern = Objects.requireNonNull(wrapper.patternRecipe());
        assertEquals(expectedInputs, pattern.inputs().stream().map(List::getFirst).toList());
        assertEquals(outputs, pattern.outputs().stream().map(List::getFirst).toList());

        var layout = new JeiTestViews.Layout();
        page.setRecipe(layout, wrapper, indexed);
        var visibleInputs = new ObjectOpenHashSet<AEKey>();
        var visibleOutputs = new ObjectOpenHashSet<AEKey>();
        int count = (expectedInputs.size() + RecipePanelLayout.RESOURCE_CAPACITY - 1) / RecipePanelLayout.RESOURCE_CAPACITY;
        for (int i = 0; i < count; i++) {
            visibleInputs.addAll(visibleKeys(layout, true));
            visibleOutputs.addAll(visibleKeys(layout, false));
            assertTrue(wrapper.handleMouseScroll(10, page.getBackground().getHeight() - 1, -1));
        }
        assertEquals(new ObjectOpenHashSet<>(expectedInputs.stream().map(GenericStack::what).toList()), visibleInputs);
        assertEquals(new ObjectOpenHashSet<>(outputs.stream().map(GenericStack::what).toList()), visibleOutputs);
        assertTrue(visibleKeys(layout, true).contains(input.what()));
    }

    @Test
    void preparedSingleInputPagesSwitchAuxiliaryLayoutBothWaysWithoutChangingOlderWrappers() {
        var input = stack(ITEM, "prepared_main", 7);
        var outputs = new ObjectArrayList<GenericStack>();
        for (int i = 0; i < RecipePanelLayout.RESOURCE_CAPACITY * 2 + 1; i++)
            outputs.add(stack(ITEM, "prepared_output_" + i, i + 1));
        var entry = SimpleRecipePool.Entry.create(id("prepared_auxiliary"), 10, input, outputs, Set.of());
        var original = new SimpleRecipePool(id("prepared_auxiliary_pool"), "pool.title", List.of(entry));
        var displays = displays();
        var originalDisplay = displays.describe(entry.recipe());
        var page = new SingleInputRecipePage(original, new JeiTestViews.Helper(), displays, JeiRenderOptions.defaults(), JeiTestViews.FONT);
        var originalWrapper = page.createRecipe(entry.recipe(), originalDisplay, ingredients(originalDisplay));
        var originalLayout = new JeiTestViews.Layout();
        page.setRecipe(originalLayout, originalWrapper, new JeiTestViews.Ingredients());
        int originalHeight = page.getBackground().getHeight();

        var auxiliary = new ObjectArrayList<GenericStack>();
        for (int i = 0; i < RecipePanelLayout.RESOURCE_CAPACITY + 1; i++)
            auxiliary.add(stack(FLUID, "prepared_auxiliary_" + i, i + 1));
        var augmented = original.withAuxiliaryInputs(auxiliary);
        var snapshot = displays.capture(augmented.recipes());
        var augmentedRecipe = augmented.recipes().getFirst();
        var augmentedDisplay = snapshot.describe(augmentedRecipe);
        var prepared = Objects.requireNonNull(page.prepareUpdate(augmented, snapshot));
        var augmentedWrapper = prepared.createRecipe(augmentedRecipe, augmentedDisplay, ingredients(augmentedDisplay));
        prepared.apply();
        var augmentedLayout = new JeiTestViews.Layout();
        page.setRecipe(augmentedLayout, augmentedWrapper, new JeiTestViews.Ingredients());
        assertEquals(RecipePanelLayout.RESOURCE_CAPACITY, page.inputLayout().size());
        assertEquals(RecipePanelLayout.RESOURCE_CAPACITY, page.outputLayout().size());
        int augmentedHeight = page.getBackground().getHeight();
        assertTrue(augmentedWrapper.handleMouseScroll(10, augmentedHeight - 1, -1));
        assertFalse(visibleKeys(augmentedLayout, true).contains(input.what()));
        assertTrue(originalWrapper.handleMouseScroll(10, originalHeight - 1, -1));
        assertTrue(visibleKeys(originalLayout, true).contains(input.what()));

        var restoredSnapshot = displays.capture(original.recipes());
        var restoredDisplay = restoredSnapshot.describe(entry.recipe());
        var restore = Objects.requireNonNull(page.prepareUpdate(original, restoredSnapshot));
        var restored = restore.createRecipe(entry.recipe(), restoredDisplay, ingredients(restoredDisplay));
        restore.apply();
        var restoredLayout = new JeiTestViews.Layout();
        page.setRecipe(restoredLayout, restored, new JeiTestViews.Ingredients());
        assertEquals(1, page.inputLayout().size());
        assertTrue(restored.handleMouseScroll(10, page.getBackground().getHeight() - 1, -1));
        assertTrue(visibleKeys(restoredLayout, true).contains(input.what()));
        assertEquals(List.of(List.of(input)), Objects.requireNonNull(restored.patternRecipe()).inputs());
        assertTrue(augmentedWrapper.handleMouseScroll(10, augmentedHeight - 1, -1));
        assertTrue(visibleKeys(augmentedLayout, true).isEmpty());
    }

    private static Set<AEKey> visibleKeys(JeiTestViews.Layout layout, boolean input) {
        var result = new ObjectOpenHashSet<AEKey>();
        for (var type : List.of(ITEM_INGREDIENT, FLUID_INGREDIENT)) {
            var group = layout.group(type);
            for (var entry : group.values.int2ObjectEntrySet()) {
                if (group.roles.get(entry.getIntKey()) == input)
                    for (var stack : entry.getValue()) result.add(stack.what());
            }
        }
        return result;
    }

    @Test
    void globalHelperUidCaptureDowngradesCrossPoolAndRegisteredKeyCollisionsBeforeIndexing() {
        var alpha = stack(ITEM, "alpha", Long.MAX_VALUE);
        var beta = stack(ITEM, "beta", 7);
        var registered = stack(ITEM, "registered", 1);
        var registryOutput = stack(ITEM, "registry_output", Long.MAX_VALUE - 1);
        var unique = stack(ITEM, "unique", 11);
        assertNotEquals(alpha.what(), beta.what());
        assertNotEquals(registered.what(), registryOutput.what());
        var firstRecipe = recipe("first_collision", ObjectArrayList.of(alpha), ObjectArrayList.of(registryOutput));
        var secondRecipe = recipe("second_collision", ObjectArrayList.of(beta, unique), ObjectArrayList.of());
        var pools = ObjectArrayList.of(pool("first_collision_pool", firstRecipe), pool("second_collision_pool", secondRecipe));
        var displays = displays();
        var allPoolDisplays = new ObjectArrayList<JeiRecipeDisplay>();
        for (var pool : pools) {
            for (var recipe : pool.recipes()) {
                allPoolDisplays.add(displays.describe(recipe));
            }
        }
        var converter = converter(ITEM, ITEM_INGREDIENT);
        var helper = new JeiTestViews.IdentityHelper<GenericStack>(ingredient -> {
            if (ingredient.what().equals(registered.what()) || ingredient.what().equals(registryOutput.what())) {
                return "registered_collision_uid";
            }
            return ingredient.what().equals(unique.what()) ? "unique_uid" : "cross_pool_collision_uid";
        });
        // Exercise our grouping with explicit helper semantics; platform NBT/UID behavior is source-audited separately.
        var identities = new JeiQueryIdentityIndex();
        identities.captureConverter(converter, helper, ObjectArrayList.of(registered), allPoolDisplays);
        var converters = ObjectArrayList.<IngredientConverter<?>>of(converter);
        var first = allPoolDisplays.getFirst();
        var second = allPoolDisplays.getLast();
        var firstIngredients = new JeiRecipeIngredients(first, converters, ObjectArrayList.of(ITEM_INGREDIENT), identities);
        var secondIngredients = new JeiRecipeIngredients(second, converters, ObjectArrayList.of(ITEM_INGREDIENT), identities);
        var alphaRow = first.resources().getFirst();
        var registryRow = first.resources().getLast();
        var betaRow = second.resources().getFirst();
        var uniqueRow = second.resources().getLast();
        assertFalse(firstIngredients.supports(alphaRow));
        assertFalse(secondIngredients.supports(betaRow));
        assertFalse(firstIngredients.supports(registryRow));
        assertTrue(firstIngredients.queryConflict(alphaRow));
        assertTrue(secondIngredients.queryConflict(betaRow));
        assertTrue(firstIngredients.queryConflict(registryRow));
        assertFalse(identities.supports(converter, registered, registered.what()));
        assertTrue(identities.conflicts(converter, registered));
        assertTrue(secondIngredients.supports(uniqueRow));
        assertFalse(secondIngredients.queryConflict(uniqueRow));
        var firstIndex = new JeiTestViews.Ingredients();
        var secondIndex = new JeiTestViews.Ingredients();
        firstIngredients.index(firstIndex);
        secondIngredients.index(secondIndex);
        assertTrue(firstIndex.getInputs(ITEM_INGREDIENT).isEmpty());
        assertTrue(firstIndex.getOutputs(ITEM_INGREDIENT).isEmpty());
        assertEquals(1, secondIndex.getInputs(ITEM_INGREDIENT).size());
        assertTrue(secondIndex.getOutputs(ITEM_INGREDIENT).isEmpty());
        assertSame(unique.what(), secondIndex.getInputs(ITEM_INGREDIENT).getFirst().getFirst().what());
        assertEquals(1, secondIndex.getInputs(ITEM_INGREDIENT).getFirst().getFirst().amount());
        // Rejected query entries retain the exact display icon source and original long quantity.
        assertSame(alpha.what(), alphaRow.stack().what());
        assertEquals(Long.MAX_VALUE, alphaRow.stack().amount());
        assertSame(beta.what(), betaRow.stack().what());
        assertEquals(7, betaRow.stack().amount());
        assertSame(registryOutput.what(), registryRow.stack().what());
        assertEquals(Long.MAX_VALUE - 1, registryRow.stack().amount());
        assertSame(unique.what(), uniqueRow.stack().what());
        assertEquals(11, uniqueRow.stack().amount());
    }

    private static class IngredientType implements IIngredientType<GenericStack> {
        @Override
        public Class<GenericStack> getIngredientClass() {
            return GenericStack.class;
        }
    }

    /**
     * Implements the public native category interface directly; its layout differs from the default grid.
     */
    private static class IndependentPage implements RecipePoolJeiPage {
        private final RecipePool pool;
        private boolean clicked;

        IndependentPage(RecipePool pool) {
            this.pool = pool;
        }

        @Override
        public RecipePool pool() {
            return pool;
        }

        @Override
        public IDrawable getBackground() {
            return new JeiTestViews.Blank(120, 90);
        }

        @Override
        public void drawExtras(Minecraft minecraft) {
            Gui.drawRect(10, 10, 110, 75, clicked ? 0xFF446688 : 0xFF334455);
            minecraft.fontRenderer.drawString(pool.id().toString(), 12, 12, 0xFFFFFF);
        }

        @Override
        public void setRecipe(IRecipeLayout layout, RecipePoolJeiRecipe wrapper, IIngredients ignored2) {
            wrapper.ingredients().initialize(layout, 0, 1, 1, 10, 70, 25, 18);
            var panels = new RecipePanelLayout(wrapper.display());
            wrapper.ingredients().show(layout, ObjectArrayList.of(), panels.outputs(0), 0, 1);
        }

        @Override
        public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            return new IndependentRecipe(this, recipe, display, ingredients);
        }
    }

    private static class IndependentRecipe extends RecipePoolJeiRecipe {
        private final IndependentPage owner;

        IndependentRecipe(IndependentPage owner, MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            super(owner, recipe, display, ingredients);
            this.owner = owner;
        }

        @Override
        public boolean handleClick(Minecraft ignored, int x, int y, int button) {
            return select(x, y, button);
        }

        @Override
        public boolean handleMouseScroll(int x, int y, int scroll) {
            return scroll != 0 && select(x, y, 0);
        }

        private boolean select(int x, int y, int button) {
            if (x == 30 && y == 40 && button == 0) {
                owner.clicked = true;
                return true;
            }
            return false;
        }
    }
}
