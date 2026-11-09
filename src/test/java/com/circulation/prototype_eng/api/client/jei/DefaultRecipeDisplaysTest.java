package com.circulation.prototype_eng.api.client.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.resources.JeiTestLocale;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRecipeDisplaysTest {
    private static final KeyType TYPE = new KeyType("jei_display");
    private static final GenericStack INPUT = new GenericStack(new Key(TYPE, "input"), Long.MAX_VALUE);
    private static final GenericStack OUTPUT = new GenericStack(new Key(TYPE, "output"), 7);

    private static ResourceLocation id(String name) {
        return new ResourceLocation("jei_test", name);
    }

    private static RecipeJeiDisplays displays() {
        var registry = new RecipeJeiDisplays();
        registry.freeze();
        return registry;
    }

    @Test
    void phaseAndLongAmountsSurviveGenerationWhileFeRemainsIndependent() {
        var resource = new ResourceRequirement(ObjectArrayList.of(INPUT), ObjectArrayList.of(INPUT),
            ObjectArrayList.of(OUTPUT), ObjectArrayList.of(OUTPUT), true);
        var recipe = new MachineRecipe(id("phases"), 0, ObjectArrayList.of(
            RecipeRequirements.RESOURCES.requirement(id("io"), resource),
            RecipeRequirements.ENERGY.requirement(id("fe"), new EnergyRequirement(Long.MAX_VALUE, 3, 4, 5, false))));
        var result = displays().describe(recipe);
        assertEquals(4, result.resources().size());
        assertEquals(Long.MAX_VALUE, result.resources().getFirst().stack().amount());
        assertEquals(ObjectArrayList.of(JeiText.Start, JeiText.Tick, JeiText.Tick, JeiText.End),
            new ObjectArrayList<>(result.resources().stream().map(row -> row.details().getLast().text()).toList()));
        assertEquals(List.of(JeiText.Duration, JeiText.EnergyInput, JeiText.EnergyInputPerTick,
                JeiText.EnergyOutputPerTick, JeiText.EnergyOutput),
            result.information().stream().map(JeiRecipeDisplay.Text::text).toList());
        assertTrue(result.information().stream().anyMatch(line -> line.arguments().contains(Long.MAX_VALUE)));
        JeiTestLocale.withTranslations(Map.of(
            JeiText.Start.getTranslationKey(), "开始时", JeiText.Tick.getTranslationKey(), "每 Tick", JeiText.End.getTranslationKey(), "完成时",
            JeiText.ConsumeAt.getTranslationKey(), "%s 消耗 %s", JeiText.ProduceAt.getTranslationKey(), "%s 产出 %s",
            JeiText.ConsumeChance.getTranslationKey(), "消耗概率：%s%%", JeiText.ProduceChance.getTranslationKey(), "产出概率：%s%%"), () -> {
            var tooltips = result.resources().stream().map(JeiRecipeIngredients::tooltip).toList();
            assertEquals(List.of("开始时 消耗 " + Long.MAX_VALUE, "每 Tick 消耗 " + Long.MAX_VALUE, "每 Tick 产出 7", "完成时 产出 7"),
                tooltips.stream().map(lines -> lines.get(1)).toList());
            for (int i = 0; i < tooltips.size(); i++) {
                var lines = tooltips.get(i);
                assertEquals((i < 2 ? "消耗" : "产出") + "概率：100.0%", lines.get(2));
                assertTrue(lines.stream().noneMatch(line -> line.contains("每单位") || line.equals("开始时") || line.equals("每 Tick") || line.equals("完成时")));
            }
        });
    }

    @Test
    void checkpointAndSelectorScopesKeepConditionalChildLifecycleMeaning() {
        var first = RecipeRequirements.RESOURCES.requirement(id("first"), new ResourceRequirement(ObjectArrayList.of(INPUT), ObjectArrayList.of(OUTPUT)));
        var second = RecipeRequirements.RESOURCES.requirement(id("second"), new ResourceRequirement(ObjectArrayList.of(), ObjectArrayList.of(OUTPUT)));
        var selection = RecipeRequirements.SELECTIVE.requirement(id("selector"), new SelectiveRequirement(ObjectArrayList.of(first, second)));
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(40, selection, false));
        var result = displays().describe(new MachineRecipe(id("nested"), 60, ObjectArrayList.of(checkpoint)));
        assertEquals(3, result.resources().size());
        for (var row : result.resources()) {
            assertEquals(JeiText.CheckpointUnscaled, row.details().getFirst().text());
            assertEquals(JeiText.Alternative, row.details().get(1).text());
        }
        assertEquals(1, result.resources().getFirst().details().get(1).arguments().get(1));
        assertEquals(2, result.resources().getLast().details().get(1).arguments().get(1));
    }

    @Test
    void dynamicExamplesAreIndexedWithoutEvaluatingMatcherOrInventingPatternInputs() {
        var matcher = new NamedResourceMatcher(id("callback"), (ignored1, ignored2) -> {
            throw new AssertionError("JEI must not evaluate runtime matcher");
        });
        var dynamic = new DynamicInputGroup(matcher, INPUT.what(), 12, ObjectArrayList.of(OUTPUT.what()), 100);
        var payload = new ResourceRequirement(ObjectArrayList.of(), ObjectArrayList.of(), ObjectArrayList.of(), ObjectArrayList.of(),
            false, ResourceChance.ALWAYS, null, ObjectArrayList.of(), ObjectArrayList.of(dynamic));
        var result = displays().describe(new MachineRecipe(id("dynamic"), 1, ObjectArrayList.of(RecipeRequirements.RESOURCES.requirement(id("io"), payload))));
        assertEquals(1, result.resources().size());
        assertSame(OUTPUT.what(), result.resources().getFirst().stack().what());
        assertEquals(12, result.resources().getFirst().stack().amount());
        assertTrue(result.resources().getFirst().details().stream().anyMatch(line -> line.text() == JeiText.Dynamic));
    }

    @Test
    void weightedOutputsKeepWeightsAndChanceWithoutConvertingToGuaranteedStacks() {
        var random = new RandomOutputPool(ObjectArrayList.of(new WeightedSampling.Weighted<>(INPUT, 2), new WeightedSampling.Weighted<>(OUTPUT, 7)), 1);
        var payload = new ResourceRequirement(ObjectArrayList.of(), ObjectArrayList.of(), ObjectArrayList.of(), ObjectArrayList.of(), false,
            new ResourceChance(35, id("luck")), random);
        var result = displays().describe(new MachineRecipe(id("weighted"), 1, ObjectArrayList.of(RecipeRequirements.RESOURCES.requirement(id("io"), payload))));
        assertEquals(2, result.resources().size());
        for (var row : result.resources()) {
            assertTrue(row.details().stream().anyMatch(line -> line.text() == JeiText.ChanceAttribute));
            assertEquals(JeiText.Weighted, row.details().getLast().text());
            assertEquals(1, row.details().getLast().arguments().get(2));
        }
    }

    @Test
    void unknownTypeWithBuiltInPayloadUsesCustomProviderAndNeverItsRuntimeSystem() {
        var foreign = new RecipeRequirementType<ResourceRequirement>(id("custom"), new RecipeRequirementSystem<>() {
            @Override
            public List<RecipeRequirement<?>> children(ResourceRequirement ignored3) {
                throw new AssertionError("Runtime extension is not a JEI description");
            }
        });
        var recipe = new MachineRecipe(id("custom"), 1, ObjectArrayList.of(foreign.requirement(id("io"), new ResourceRequirement(ObjectArrayList.of(INPUT), ObjectArrayList.of(OUTPUT)))));
        var unknown = displays().describe(recipe);
        assertTrue(unknown.resources().isEmpty());
        assertTrue(unknown.information().stream().anyMatch(line -> line.text() == JeiText.CustomRequirement));
        var custom = new RecipeJeiDisplays();
        custom.register(foreign, (ignored4, requirement, builder) -> builder.resource(requirement.payload().outputs().getFirst(),
            JeiRecipeDisplay.Role.OUTPUT, JeiRecipeDisplay.Text.of(JeiText.End)));
        custom.freeze();
        var described = custom.describe(recipe);
        assertEquals(1, described.resources().size());
        assertSame(OUTPUT, described.resources().getFirst().stack());
    }
}
