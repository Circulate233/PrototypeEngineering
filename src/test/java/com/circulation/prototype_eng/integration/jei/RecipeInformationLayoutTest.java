package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.localization.JeiText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeInformationLayoutTest {
    @Test
    void wrappingReservesIconWidthAndUsesTheTallerOfTextAndIcon() {
        var builder = new JeiRecipeDisplay.Builder();
        builder.information(JeiRecipeDisplay.Text.of(JeiText.Duration, 100, 5));
        builder.energy(500, JeiRecipeDisplay.Role.INPUT, JeiRecipeDisplay.Text.of(JeiText.EnergyInput, 500));
        builder.energy(1000, JeiRecipeDisplay.Role.OUTPUT, JeiRecipeDisplay.Text.of(JeiText.EnergyOutput, 1000));
        var fullText = List.of("First line", "Second line", "Third line");
        var font = new RecipeInformationLayout.Font(10, (text, width) -> {
            assertEquals(text.energy() == null ? 166 : 144, width);
            return text.text() == JeiText.EnergyOutput ? fullText : List.of("Short text");
        });
        var layout = RecipeInformationLayout.measure(builder.build().information(), 174, font);
        var plain = layout.paragraphs().get(0);
        var icon = layout.paragraphs().get(1);
        var wrapped = layout.paragraphs().get(2);
        assertTrue(icon.y() > plain.y() + plain.height());
        assertTrue(wrapped.y() > icon.y() + icon.height());
        assertEquals(18, icon.height());
        assertEquals(fullText, wrapped.lines());
        assertEquals(30, wrapped.height());
        assertEquals(wrapped.y() + wrapped.height(), layout.height());
        assertTrue(wrapped.contains(layout.height() - 1));
        assertFalse(wrapped.contains(layout.height()));
        assertFalse(plain.contains(-1));
        assertEquals(0, RecipeInformationLayout.measure(List.of(), 174, font).height());
    }
}
