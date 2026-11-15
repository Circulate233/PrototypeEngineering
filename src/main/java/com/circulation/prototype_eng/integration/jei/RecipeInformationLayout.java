package com.circulation.prototype_eng.integration.jei;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

/**
 * Measures the complete explanation with the same font and wrapping used for drawing.
 * Layout and wrapped-line lists are read-only for the layout's lifetime.
 */
@SideOnly(Side.CLIENT)
public record RecipeInformationLayout(List<Paragraph> paragraphs, int height) {
    public static RecipeInformationLayout measure(List<JeiRecipeDisplay.Text> information, int width, Font font) {
        var paragraphs = new ObjectArrayList<Paragraph>(information.size());
        int y = 0;
        for (int index = 0; index < information.size(); index++) {
            var source = information.get(index);
            int x = source.energy() == null ? 4 : 26;
            var lines = font.wrap().apply(source, width - x - 4);
            int height = Math.max(source.energy() == null ? 0 : 18, lines.size() * font.lineHeight());
            paragraphs.add(new Paragraph(source, lines, x, y, height));
            y += height + 3;
        }
        return new RecipeInformationLayout(paragraphs, Math.max(0, y - 3));
    }

    @FunctionalInterface
    public interface TextWrapper {
        /**
         * Returns completed lines that remain unchanged while the measured layout is in use.
         */
        List<String> apply(JeiRecipeDisplay.Text text, int width);
    }

    public record Font(int lineHeight, TextWrapper wrap) {
        public Font {
            if (lineHeight <= 0) throw new IllegalArgumentException("Non-positive font height");
        }
    }

    public record Paragraph(JeiRecipeDisplay.Text source, List<String> lines, int x, int y, int height) {
        public boolean contains(int mouseY) {
            return mouseY >= y && mouseY < y + height;
        }
    }
}
