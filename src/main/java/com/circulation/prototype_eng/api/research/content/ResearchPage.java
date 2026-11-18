package com.circulation.prototype_eng.api.research.content;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A null member list groups unassigned research in the page's namespace. */
public record ResearchPage(int order, boolean manual, @Nullable String title,
                           @Nullable List<ResourceLocation> researches, Object2ObjectMap<String, Position> positions) {
    public ResearchPage(int order, boolean manual, Object2ObjectMap<String, Position> positions) {
        this(order, manual, null, null, positions);
    }

    public int priority() {
        return order;
    }

    public void move(String id, int x, int y) {
        positions.put(id, new Position(x, y));
    }

    public record Position(int x, int y) {
    }
}
