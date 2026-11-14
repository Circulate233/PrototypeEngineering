package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.Tooltip;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.List;

/**
 * Explicit line breaks and bounded wrapping for the resource chamber help text.
 */
public final class HatchTooltips {
    private HatchTooltips() {
    }

    public static List<ITextComponent> wrap(FontRenderer font, List<ITextComponent> lines, int maxWidth) {
        var expanded = new ObjectArrayList<ITextComponent>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            expanded.add(expandLineBreaks(line));
        }
        var result = new ObjectArrayList<ITextComponent>(lines.size());
        var elements = new Tooltip(expanded).content();
        for (int index2 = 0; index2 < elements.size(); index2++) {
            var line = elements.get(index2);
            result.addAll(GuiUtilRenderComponents.splitText(line, Math.max(40, maxWidth), font, false, false));
        }
        return result;
    }

    private static ITextComponent expandLineBreaks(ITextComponent line) {
        if (!line.getUnformattedText().contains("\\n")) return line;
        var expanded = new TextComponentString("");
        for (var part : line) {
            var text = new TextComponentString(part.getUnformattedComponentText().replace("\\n", "\n"));
            text.setStyle(part.getStyle().createShallowCopy());
            expanded.appendSibling(text);
        }
        return expanded;
    }
}
