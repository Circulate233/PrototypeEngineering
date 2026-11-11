package com.circulation.prototype_eng.integration.jei;

import it.unimi.dsi.fastutil.objects.ObjectLists;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** Measures the complete explanation with the same font and wrapping used for drawing. */
public record RecipeInformationLayout(List<Paragraph> paragraphs, int height) {
    public static RecipeInformationLayout measure(List<JeiRecipeDisplay.Text> information, int width, Font font) {
        var paragraphs = new ObjectArrayList<Paragraph>();
        int y = 0;
        for (var source : information) {
            int x = source.energy() == null ? 4 : 26;
            var lines = List.copyOf(font.wrap().apply(source, width - x - 4));
            int height = Math.max(source.energy() == null ? 0 : 18, lines.size() * font.lineHeight());
            paragraphs.add(new Paragraph(source, lines, x, y, height));
            y += height + 3;
        }
        return new RecipeInformationLayout(ObjectLists.unmodifiable(paragraphs), Math.max(0, y - 3));
    }

    public record Font(int lineHeight, BiFunction<JeiRecipeDisplay.Text, Integer, List<String>> wrap) {
        public Font {
            if (lineHeight <= 0) throw new IllegalArgumentException("Non-positive font height");
            Objects.requireNonNull(wrap);
        }
    }

    public record Paragraph(JeiRecipeDisplay.Text source, List<String> lines, int x, int y, int height) {
        public boolean contains(int mouseY) {
            return mouseY >= y && mouseY < y + height;
        }
    }
}
