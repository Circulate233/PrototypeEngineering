package com.circulation.prototype_eng.api.client.jei;

import ae2.api.integrations.hei.IngredientConverter;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.integration.jei.AutoLayoutRecipePage;
import com.circulation.prototype_eng.integration.jei.RecipePanelLayout;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.recipe.IIngredientType;
import net.minecraft.client.resources.JeiTestLocale;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native candidate grouping and tooltip association, using the existing non-FML ingredient fixture. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class JeiCandidateDisplayTest {
    private static final KeyType TYPE = new KeyType("jei_candidates");
    private static final IIngredientType<GenericStack> INGREDIENT = () -> GenericStack.class;
    private static final JeiRenderOptions ALTERNATIVES = new JeiRenderOptions(true, JeiRenderOptions.CandidateMode.ALTERNATIVES, 0);

    private static GenericStack stack(String name, long amount) { return new GenericStack(new Key(TYPE, name), amount); }

    private static JeiRecipeIngredients ingredients(JeiRecipeDisplay display) {
        var converter = new IngredientConverter<GenericStack>() {
            @Override public IIngredientType<GenericStack> getIngredientType() { return INGREDIENT; }
            @Override public @Nullable GenericStack getIngredientFromStack(GenericStack stack) { return stack.what().getType() == TYPE ? stack : null; }
            @Override public GenericStack getStackFromIngredient(GenericStack ingredient) { return ingredient; }
        };
        var identities = new JeiQueryIdentityIndex();
        identities.captureConverter(converter, new JeiTestViews.IdentityHelper<>(ingredient ->
                Objects.requireNonNull(converter.getStackFromIngredient(ingredient)).what().toTag().toString()),
            ObjectArrayList.of(), ObjectArrayList.of(display));
        return new JeiRecipeIngredients(display, ObjectArrayList.of(converter), ObjectArrayList.of(INGREDIENT), identities);
    }

    @Test
    void nativeCycleKeepsFullIndexAndAssociatesCopiedIngredientWithItsExactLongTooltip() {
        var builder = new JeiRecipeDisplay.Builder();
        var candidates = builder.candidates(new JeiRecipeDisplay.CandidateGroup("fuzzy"));
        candidates.resource(stack("first", 7), JeiRecipeDisplay.Role.INPUT);
        candidates.resource(stack("second", Long.MAX_VALUE), JeiRecipeDisplay.Role.INPUT);
        var display = builder.build();
        var ingredients = ingredients(display);
        var panels = new RecipePanelLayout(display, ALTERNATIVES, ingredients);
        var nativeLayout = new JeiTestViews.Layout();
        ingredients.initialize(nativeLayout, 1, 0, 1, 0, 100, 20, 18);
        ingredients.showGroups(nativeLayout, panels.inputGroups(0), panels.outputGroups(0), 1, 0);
        var group = nativeLayout.group(INGREDIENT);
        assertEquals(2, group.values.get(0).size());
        var indexed = new JeiTestViews.Ingredients();
        ingredients.index(indexed);
        assertEquals(2, indexed.getInputs(INGREDIENT).size());
        assertEquals(display.resources(), panels.inputs(0));
        var copied = new GenericStack(group.values.get(0).getLast().what(), 1);
        JeiTestLocale.withTranslations(Map.of(JeiText.Consume.getTranslationKey(), "Consume %s"), () -> {
            var tooltip = new ObjectArrayList<String>();
            group.tooltips.getFirst().onTooltip(0, true, copied, tooltip);
            assertTrue(tooltip.getFirst().contains(Long.toString(Long.MAX_VALUE)));
        });
        ingredients.showGroups(nativeLayout, List.of(), List.of(), 1, 0);
        assertTrue(group.values.isEmpty());
        var afterClear = new ObjectArrayList<String>();
        group.tooltips.getFirst().onTooltip(0, true, copied, afterClear);
        assertTrue(afterClear.isEmpty());
    }

    @Test
    void duplicateExactKeysCycleWithTheirOwnAmountsWhileUnsupportedCandidatesRemainVisible() {
        var builder = new JeiRecipeDisplay.Builder();
        var candidates = builder.candidates(new JeiRecipeDisplay.CandidateGroup("weighted"));
        candidates.resource(stack("same", 7), JeiRecipeDisplay.Role.OUTPUT);
        candidates.resource(stack("same", 19), JeiRecipeDisplay.Role.OUTPUT);
        var fallback = builder.candidates(new JeiRecipeDisplay.CandidateGroup("fuzzy"));
        fallback.resource(stack("native", 3), JeiRecipeDisplay.Role.INPUT);
        fallback.resource(new GenericStack(new Key(new KeyType("unsupported"), "other"), 4), JeiRecipeDisplay.Role.INPUT);
        var display = builder.build();
        var ingredients = ingredients(display);
        var panels = new RecipePanelLayout(display, ALTERNATIVES, ingredients);
        assertEquals(2, panels.inputSlots());
        assertEquals(1, panels.outputSlots());
        assertEquals(7, panels.outputs(0).getFirst().stack().amount());
        assertEquals(19, panels.outputs(0).getLast().stack().amount());
        assertSame(display.resources().getLast(), panels.inputs(0).getLast());
        var layout = new JeiTestViews.Layout();
        ingredients.initialize(layout, 2, 1, 2, 0, 100, 20, 18);
        ingredients.showGroups(layout, panels.inputGroups(0), panels.outputGroups(0), 2, 1);
        var group = layout.group(INGREDIENT);
        assertEquals(2, group.values.get(2).size());
        JeiTestLocale.withTranslations(Map.of(JeiText.Produce.getTranslationKey(), "Produce %s"), () -> {
            for (int i = 0; i < 2; i++) {
                var tooltip = new ObjectArrayList<String>();
                group.tooltips.getFirst().onTooltip(2, false, group.values.get(2).get(i), tooltip);
                assertTrue(tooltip.getFirst().contains(i == 0 ? "7" : "19"));
            }
        });
    }

    @Test
    void selectiveMaterialsShareCorrespondingJeiSlotsAndPatternCandidatesKeepAmountsAndPhases() {
        var first = RecipeRequirements.RESOURCES.requirement(new ResourceLocation("jei_test", "first"),
            new ResourceRequirement(List.of(stack("a", 2), stack("catalyst_a", 1)), List.of(stack("out_a", 3))));
        var second = RecipeRequirements.RESOURCES.requirement(new ResourceLocation("jei_test", "second"),
            new ResourceRequirement(List.of(stack("b", 5)), List.of(stack("catalyst_b", 4)),
                List.of(stack("out_b", 2)), List.of(), false));
        var selection = RecipeRequirements.SELECTIVE.requirement(new ResourceLocation("jei_test", "materials"),
            new SelectiveRequirement(List.of(first, second)));
        var descriptions = new RecipeJeiDisplays();
        descriptions.freeze();
        var display = descriptions.describe(new MachineRecipe(new ResourceLocation("jei_test", "selective"), 80, List.of(selection)));
        var ingredients = ingredients(display);
        var panels = new RecipePanelLayout(display, ALTERNATIVES, ingredients);
        var layout = new JeiTestViews.Layout();
        ingredients.initialize(layout, 2, 1, 2, 0, 100, 20, 18);
        ingredients.showGroups(layout, panels.inputGroups(0), panels.outputGroups(0), 2, 1);
        for (var values : layout.group(INGREDIENT).values.values()) assertEquals(2, values.size());
        var pattern = panels.patternRecipe(80);
        assertEquals(List.of(List.of(stack("a", 2), stack("b", 5)),
            List.of(stack("catalyst_a", 1), stack("catalyst_b", 320))), pattern.inputs());
        assertEquals(List.of(List.of(stack("out_a", 3), stack("out_b", 160))), pattern.outputs());
        for (var row : display.resources()) assertTrue(row.details().stream().anyMatch(line -> line.text() == JeiText.Alternative));
    }

    @Test
    void nestedSelectiveAndFuzzyCandidatesRemainOneMaterialSlot() {
        var fuzzy = RecipeRequirements.RESOURCES.requirement(new ResourceLocation("jei_test", "fuzzy"),
            new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, null,
                List.of(new FuzzyInputGroup(List.of(stack("a", 1).what(), stack("b", 1).what()), 7)), List.of()));
        var fixed = RecipeRequirements.RESOURCES.requirement(new ResourceLocation("jei_test", "fixed"),
            new ResourceRequirement(List.of(stack("c", 9)), List.of()));
        var nested = RecipeRequirements.SELECTIVE.requirement(new ResourceLocation("jei_test", "nested"),
            new SelectiveRequirement(List.of(fuzzy, fixed)));
        var last = RecipeRequirements.RESOURCES.requirement(new ResourceLocation("jei_test", "last"),
            new ResourceRequirement(List.of(stack("d", Long.MAX_VALUE)), List.of()));
        var outer = RecipeRequirements.SELECTIVE.requirement(new ResourceLocation("jei_test", "outer"),
            new SelectiveRequirement(List.of(nested, last)));
        var descriptions = new RecipeJeiDisplays();
        descriptions.freeze();
        var display = descriptions.describe(new MachineRecipe(new ResourceLocation("jei_test", "nested_materials"), 20, List.of(outer)));
        var panels = new RecipePanelLayout(display, ALTERNATIVES, ingredients(display));
        assertEquals(1, panels.inputSlots());
        assertEquals(List.of(List.of(stack("a", 7), stack("b", 7), stack("c", 9), stack("d", Long.MAX_VALUE))),
            panels.patternRecipe(20).inputs());
    }

    @Test
    void equalNamesAcrossDeclarationsAndExpandedOptionKeepIndependentGroupsAndEveryCandidate() {
        var builder = new JeiRecipeDisplay.Builder();
        for (int group = 0; group < 2; group++) {
            var candidates = builder.candidates(new JeiRecipeDisplay.CandidateGroup("same_name"));
            for (int row = 0; row < 18; row++) candidates.resource(stack(group + "/" + row, row + 1), JeiRecipeDisplay.Role.INPUT);
        }
        var display = builder.build();
        var ingredients = ingredients(display);
        var alternatives = new RecipePanelLayout(display, ALTERNATIVES, ingredients);
        assertEquals(2, alternatives.inputSlots());
        assertEquals(18, alternatives.inputGroups(0).getFirst().size());
        assertEquals(18, alternatives.inputGroups(0).getLast().size());
        assertEquals(display.resources(), alternatives.inputs(0));
        var expanded = new RecipePanelLayout(display, new JeiRenderOptions(false, JeiRenderOptions.CandidateMode.EXPANDED, 7), ingredients);
        var retained = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        for (int page = 0; page < expanded.pageCount(); page++) retained.addAll(expanded.inputs(page));
        assertEquals(display.resources(), retained);
    }

    @Test
    void realDynamicDescriptionsCycleExplicitExamplesAndKeepScopeLongsAndCompleteExpandedIndex() {
        var pattern = stack("pattern", 1);
        var first = stack("dynamic/first", 1);
        var second = stack("dynamic/second", 1);
        var matcher = new NamedResourceMatcher(new ResourceLocation("jei_test", "dynamic"), (_, _) -> {
            throw new AssertionError("JEI must not execute dynamic matching");
        });
        var dynamic = new DynamicInputGroup(matcher, pattern.what(), Long.MAX_VALUE,
            ObjectArrayList.of(first.what(), second.what()), 100);
        var resources = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            ResourceChance.ALWAYS, null, List.of(), ObjectArrayList.of(dynamic));
        var recipe = new MachineRecipe(new ResourceLocation("jei_test", "dynamic"), 20,
            ObjectArrayList.of(RecipeRequirements.RESOURCES.requirement(new ResourceLocation("jei_test", "io"), resources)));
        var descriptions = new RecipeJeiDisplays();
        descriptions.freeze();
        var display = descriptions.describe(recipe);
        var nativeIngredients = ingredients(display);
        var alternatives = new RecipePanelLayout(display, ALTERNATIVES, nativeIngredients);
        assertEquals(1, alternatives.inputSlots());
        var nativeLayout = new JeiTestViews.Layout();
        nativeIngredients.initialize(nativeLayout, 1, 0, 1, 0, 100, 20, 18);
        nativeIngredients.showGroups(nativeLayout, alternatives.inputGroups(0), alternatives.outputGroups(0), 1, 0);
        assertEquals(2, nativeLayout.group(INGREDIENT).values.get(0).size());
        var indexed = new JeiTestViews.Ingredients();
        nativeIngredients.index(indexed);
        assertEquals(2, indexed.getInputs(INGREDIENT).size());
        assertTrue(indexed.getInputs(INGREDIENT).stream().noneMatch(values -> values.getFirst().what().equals(pattern.what())));
        var expanded = new RecipePanelLayout(display,
            new JeiRenderOptions(true, JeiRenderOptions.CandidateMode.EXPANDED, 0), nativeIngredients);
        assertEquals(2, expanded.inputSlots());
        assertEquals(display.resources(), expanded.inputs(0));
        for (var row : expanded.inputs(0)) {
            assertEquals(Long.MAX_VALUE, row.stack().amount());
            assertTrue(row.details().stream().anyMatch(line -> line.text() == JeiText.Dynamic));
        }
    }

    @Test
    void realWeightedMultiplePicksUseSeparateCyclesAndDefaultPageCapacityWithoutDuplicatingQueryIndex() {
        var random = new RandomOutputPool(ObjectArrayList.of(new WeightedSampling.Weighted<>(stack("random/first", 7), 2),
            new WeightedSampling.Weighted<>(stack("random/second", Long.MAX_VALUE), 3)), 19);
        var resources = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            new ResourceChance(35, null), random);
        var recipe = new MachineRecipe(new ResourceLocation("jei_test", "weighted"), 20,
            ObjectArrayList.of(RecipeRequirements.RESOURCES.requirement(new ResourceLocation("jei_test", "io"), resources)));
        var descriptions = new RecipeJeiDisplays();
        descriptions.freeze();
        var display = descriptions.describe(recipe);
        var nativeIngredients = ingredients(display);
        var alternatives = new RecipePanelLayout(display, ALTERNATIVES, nativeIngredients);
        var page = new AutoLayoutRecipePage(new DefaultRecipePool(new ResourceLocation("jei_test", "pool"), "jei.test.pool",
            ObjectArrayList.of(recipe)), new JeiTestViews.Helper(), descriptions, ALTERNATIVES, JeiTestViews.FONT);
        var wrapper = page.createRecipe(recipe, display, nativeIngredients);
        var nativeLayout = new JeiTestViews.Layout();
        page.setRecipe(nativeLayout, wrapper, new JeiTestViews.Ingredients());
        assertEquals(16, page.outputLayout().size());
        assertEquals(16, alternatives.outputGroups(0).size());
        assertEquals(3, alternatives.outputGroups(1).size());
        assertEquals(16, nativeLayout.group(INGREDIENT).values.size());
        for (var values : nativeLayout.group(INGREDIENT).values.values()) assertEquals(2, values.size());
        assertTrue(wrapper.handleMouseScroll(10, 20, -1));
        assertEquals(3, nativeLayout.group(INGREDIENT).values.size());
        var indexed = new JeiTestViews.Ingredients();
        nativeIngredients.index(indexed);
        assertEquals(2, indexed.getOutputs(INGREDIENT).size());
        var expanded = new RecipePanelLayout(display,
            new JeiRenderOptions(true, JeiRenderOptions.CandidateMode.EXPANDED, 0), nativeIngredients);
        assertEquals(display.resources(), expanded.outputs(0));
    }
}
