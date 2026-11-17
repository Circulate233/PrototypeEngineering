package com.circulation.prototype_eng.api.research.content;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Client-owned page membership and coordinates. A null member list uses the page ID as its default research namespace.
 * The owner seeds automatic positions before editing; members and positions are borrowed and remain client-owned.
 */
public record ResearchPage(int order, boolean manual, @Nullable String title,
                           @Nullable List<ResourceLocation> researches, Object2ObjectMap<String, Position> positions) {
    public ResearchPage(int order, boolean manual, Object2ObjectMap<String, Position> positions) {
        this(order, manual, null, null, positions);
    }

    public void move(String id, int x, int y) {
        positions.put(id, new Position(x, y));
    }

    public record Position(int x, int y) {
    }
}
