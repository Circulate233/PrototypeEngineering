package com.circulation.prototype_eng.api.client.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;
import java.util.Objects;

/**
 * Frozen preview choices; limits concern presentation memory/work, never gameplay quantities.
 */
public record StructurePreviewSettings(BlockOrientation orientation, Map<StructurePreviewPath, Integer> sliceCounts,
                                       int maxOccurrences, int maxCellVisits,
                                       Map<StructureDisplay, Integer> displayOptions) {
    public StructurePreviewSettings {
        Objects.requireNonNull(orientation);
        if (maxOccurrences <= 0 || maxCellVisits <= 0) throw new IllegalArgumentException("Nonpositive preview limits");
        var counts = new Object2ObjectOpenHashMap<StructurePreviewPath, Integer>();
        sliceCounts.forEach((path, count) -> counts.put(Objects.requireNonNull(path), Objects.requireNonNull(count)));
        sliceCounts = Object2ObjectMaps.unmodifiable(counts);
        var options = new Object2ObjectOpenHashMap<StructureDisplay, Integer>();
        displayOptions.forEach((display, selected) -> {
            Objects.requireNonNull(display);
            Objects.requireNonNull(selected);
            if (selected < 0 || selected >= display.options().size())
                throw new IllegalArgumentException("Invalid representative choice");
            options.put(display, selected);
        });
        displayOptions = Object2ObjectMaps.unmodifiable(options);
    }

    public StructurePreviewSettings(BlockOrientation orientation, Map<StructurePreviewPath, Integer> sliceCounts,
                                    int maxOccurrences, int maxCellVisits) {
        this(orientation, sliceCounts, maxOccurrences, maxCellVisits, Map.of());
    }

    /**
     * Unselected slices use their server-declared minimum.
     */
    public StructurePreviewSettings(BlockOrientation orientation) {
        this(orientation, Map.of(), 65_536, 1_048_576);
    }
}
