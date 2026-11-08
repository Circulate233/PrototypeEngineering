package com.circulation.prototype_eng.api.client.structure;

import ae2.api.orientation.BlockOrientation;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;

import java.util.Map;
import java.util.Objects;

/**
 * Frozen preview choices; limits concern presentation memory/work, never gameplay quantities.
 */
public record StructurePreviewSettings(BlockOrientation orientation, Map<StructurePreviewPath, Integer> sliceCounts,
                                       int maxOccurrences, int maxCellVisits) {
    public StructurePreviewSettings {
        Objects.requireNonNull(orientation);
        if (maxOccurrences <= 0 || maxCellVisits <= 0) throw new IllegalArgumentException("Nonpositive preview limits");
        var counts = new Object2ObjectOpenHashMap<StructurePreviewPath, Integer>();
        sliceCounts.forEach((path, count) -> counts.put(Objects.requireNonNull(path), Objects.requireNonNull(count)));
        sliceCounts = Object2ObjectMaps.unmodifiable(counts);
    }

    /**
     * Unselected slices use their server-declared minimum.
     */
    public StructurePreviewSettings(BlockOrientation orientation) {
        this(orientation, Map.of(), 65_536, 1_048_576);
    }
}
