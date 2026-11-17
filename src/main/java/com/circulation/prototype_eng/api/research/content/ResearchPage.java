package com.circulation.prototype_eng.api.research.content;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

/**
 * Client-owned page coordinates in GUI pixels. The owner seeds all automatic positions before editing.
 */
public record ResearchPage(int order, boolean manual, Object2ObjectMap<String, Position> positions) {
    public boolean move(String path, int x, int y) {
        for (var entry : positions.object2ObjectEntrySet()) {
            if (entry.getKey().equals(path)) continue;
            var other = entry.getValue();
            if (x < other.x + 128 / 1.7 && x + 128 / 1.7 > other.x
                && y < other.y + 52 / 1.7 && y + 52 / 1.7 > other.y) return false;
        }
        positions.put(path, new Position(x, y));
        return true;
    }

    public record Position(int x, int y) {
    }
}
