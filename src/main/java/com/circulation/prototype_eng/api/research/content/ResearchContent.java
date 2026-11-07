package com.circulation.prototype_eng.api.research.content;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import java.util.Objects;
import net.minecraft.util.ResourceLocation;

/** Immutable direct-display client text; executable research and reward definitions stay separate. */
public record ResearchContent(String title, String summary, ObjectList<String> description, Icon icon) {
    public ResearchContent {
        title = requiredText(title);
        Objects.requireNonNull(summary);
        Objects.requireNonNull(icon);
        var lines = new ObjectArrayList<String>();
        for (var line : Objects.requireNonNull(description)) lines.add(requiredText(line));
        if (lines.isEmpty()) throw new IllegalArgumentException("Research content requires description lines");
        description = ObjectLists.unmodifiable(lines);
    }

    private static String requiredText(String value) {
        var trimmed = Objects.requireNonNull(value).trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Research content text must not be blank");
        return trimmed;
    }

    /** A named icon-map entry or an item variant; no item registry lookup occurs while loading text. */
    public record Icon(Kind kind, ResourceLocation id, int meta) {
        public Icon {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(id);
            if (meta < 0 || kind == Kind.ICON && meta != 0) throw new IllegalArgumentException("Invalid research content icon metadata");
        }
    }

    public enum Kind { ITEM, ICON }
}
