package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Energy;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Role;
import com.circulation.prototype_eng.localization.JeiText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiEnergyIngredientsTest {
    @Test
    void energyFocusIgnoresAmountButPreservesDirectionAndTiming() {
        var helper = JeiEnergyIngredients.HELPER;
        var small = new Energy(1, Role.INPUT, true);
        var large = new Energy(Long.MAX_VALUE, Role.INPUT, true);
        assertEquals(helper.getUniqueId(small), helper.getUniqueId(large));
        assertNotEquals(helper.getUniqueId(small), helper.getUniqueId(new Energy(1, Role.OUTPUT, true)));
        assertNotEquals(helper.getUniqueId(small), helper.getUniqueId(new Energy(1, Role.INPUT, false)));
        assertSame(large, helper.getMatch(List.of(large), small));
        assertEquals(4, JeiEnergyIngredients.entries().stream().map(helper::getUniqueId).distinct().count());
    }

    @Test
    void indexedEnergyRetainsAmountsAndDoesNotBecomePatternMaterials() {
        var builder = new JeiRecipeDisplay.Builder();
        builder.energy(128, Role.INPUT, true, JeiRecipeDisplay.Text.of(JeiText.EnergyInputPerTick, 128));
        builder.energy(512, Role.OUTPUT, JeiRecipeDisplay.Text.of(JeiText.EnergyOutput, 512));
        var display = builder.build();
        assertEquals(List.of(new Energy(128, Role.INPUT, true)), JeiEnergyIngredients.values(display, Role.INPUT));
        assertEquals(List.of(new Energy(512, Role.OUTPUT, false)), JeiEnergyIngredients.values(display, Role.OUTPUT));
        assertTrue(display.resources().isEmpty());
    }
}
