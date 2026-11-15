package com.circulation.prototype_eng.api.research.content;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.ResourceLocation;


/**
 * Immutable direct-display client text; executable research and reward definitions stay separate.
 * Title and description lines are normalized nonblank text; descriptions remain borrowed read-only.
 */
public record ResearchContent(String title, String summary, ObjectList<String> description, Icon icon) {
    public enum Kind {ITEM, ICON}

    /**
     * A named icon-map entry or an item variant; no item registry lookup occurs while loading text.
     */
    public record Icon(Kind kind, ResourceLocation id, int meta) {
    }
}
