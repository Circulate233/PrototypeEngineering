package com.circulation.prototype_eng.api.client.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.integration.jei.RecipePanelLayout;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.resources.JeiTestLocale;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiPatternPresenceTest {
    private static final KeyType TYPE = new KeyType("pattern_presence");
    private static final Key SHARED = new Key(TYPE, "shared");
    private static final Key CAST = new Key(TYPE, "cast");
    private static final Key PRODUCT = new Key(TYPE, "product");

    @Test
    void actualRecipeDisplayOmitsCatalystsAndRetainsConsumingAmountsForTheSameKey() {
        var recipe = new RecipeBuilder(id("shared"), 5)
            .addRequirement(RecipeRequirements.RESOURCE_PRESENCE.requirement(id("presence"),
                new ResourcePresenceRequirement(List.of(new GenericStack(SHARED, 1000), new GenericStack(CAST, 1)))))
            .addInput(id("materials"), new GenericStack(SHARED, 3))
            .addInputPerTick(id("materials"), new GenericStack(SHARED, 2))
            .addOutput(id("materials"), new GenericStack(PRODUCT, 7)).build();
        var display = displays().describe(recipe);
        assertEquals(5, display.resources().size());
        var pattern = new RecipePanelLayout(display).patternRecipe(recipe.durationTicks());
        assertEquals(List.of(List.of(new GenericStack(SHARED, 3)), List.of(new GenericStack(SHARED, 10))), pattern.inputs());
        assertEquals(List.of(List.of(new GenericStack(PRODUCT, 7))), pattern.outputs());
    }

    @Test
    void checkpointPresenceProducesNoEmptyPatternGroup() {
        var child = RecipeRequirements.RESOURCE_PRESENCE.requirement(id("cast"),
            new ResourcePresenceRequirement(List.of(new GenericStack(CAST, 1))));
        var recipe = new MachineRecipe(id("checkpoint"), 11, List.of(
            RecipeRequirements.CHECKPOINT.requirement(id("checkpoint_cast"), new CheckpointRequirement(7, child))));
        var display = displays().describe(recipe);
        assertEquals(1, display.resources().size());
        assertEquals(CAST, display.resources().getFirst().stack().what());
        var pattern = new RecipePanelLayout(display).patternRecipe(recipe.durationTicks());
        assertTrue(pattern.inputs().isEmpty());
        assertTrue(pattern.outputs().isEmpty());
    }

    @Test
    void nestedMarkersFilterOnlyNonconsumingInputCandidatesAndKeepOutputGroups() {
        var marker = JeiRecipeDisplay.Text.of(JeiText.NotConsumed);
        var nested = new JeiRecipeDisplay.Text(JeiText.Start, List.of(), List.of(
            new JeiRecipeDisplay.Text(JeiText.Alternative, List.of(), List.of(marker))));
        var builder = new JeiRecipeDisplay.Builder();
        builder.scoped(nested).resource(new GenericStack(SHARED, 1000), JeiRecipeDisplay.Role.INPUT);
        builder.resource(new GenericStack(SHARED, 3), JeiRecipeDisplay.Role.INPUT);
        builder.scoped(nested).resource(new GenericStack(PRODUCT, 7), JeiRecipeDisplay.Role.OUTPUT);
        var display = builder.build();
        var inputs = display.resources().stream().filter(row -> row.role() == JeiRecipeDisplay.Role.INPUT).toList();
        var outputs = display.resources().stream().filter(row -> row.role() == JeiRecipeDisplay.Role.OUTPUT).toList();
        var pattern = JeiPatternRecipe.fromGroups(List.of(inputs), List.of(outputs), 13);
        assertEquals(List.of(List.of(new GenericStack(SHARED, 3))), pattern.inputs());
        assertEquals(List.of(List.of(new GenericStack(PRODUCT, 7))), pattern.outputs());
    }

    @Test
    void tooltipUsesPresenceAmountsAndKeepsConsumingRowsForTheSameKey() {
        var recipe = new RecipeBuilder(id("tooltips"), 1)
            .addRequirement(RecipeRequirements.RESOURCE_PRESENCE.requirement(id("presence"),
                new ResourcePresenceRequirement(List.of(new GenericStack(SHARED, 1000)))))
            .addInput(id("materials"), new GenericStack(SHARED, 3)).build();
        var rows = displays().describe(recipe).resources();
        JeiTestLocale.withTranslations(Map.of(
            JeiText.PresentAmount.getTranslationKey(), "Requires %s present",
            JeiText.NotConsumed.getTranslationKey(), "Not consumed",
            JeiText.Start.getTranslationKey(), "Start",
            JeiText.ConsumeAt.getTranslationKey(), "%s consume %s"), () -> {
            assertEquals(List.of("Requires 1000 present", "Not consumed"),
                JeiRecipeIngredients.tooltip(rows.getFirst()).subList(1, 3));
            assertEquals("Start consume 3", JeiRecipeIngredients.tooltip(rows.get(1)).get(1));
        });
    }

    private static RecipeJeiDisplays displays() {
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        return displays;
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("pattern_presence_test", name);
    }
}
