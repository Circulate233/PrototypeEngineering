package com.circulation.prototype_eng.api.client.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Frozen preview choices; limits concern presentation memory/work, never gameplay quantities.
 */
public record StructurePreviewSettings(BlockOrientation orientation, Map<StructurePreviewPath, Integer> sliceCounts,
                                       int maxOccurrences, int maxCellVisits,
                                       Map<StructureDisplay, Integer> displayOptions,
                                       Map<BlockPos, CellChoice> cellOptions) {
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
        var cells = new Object2ObjectOpenHashMap<BlockPos, CellChoice>();
        cellOptions.forEach((position, choice) -> {
            var fixed = Objects.requireNonNull(position).toImmutable();
            if (cells.putIfAbsent(fixed, Objects.requireNonNull(choice)) != null)
                throw new IllegalArgumentException("Duplicate representative position");
        });
        cellOptions = Object2ObjectMaps.unmodifiable(cells);
    }

    public StructurePreviewSettings(BlockOrientation orientation, Map<StructurePreviewPath, Integer> sliceCounts,
                                    int maxOccurrences, int maxCellVisits, Map<StructureDisplay, Integer> displayOptions) {
        this(orientation, sliceCounts, maxOccurrences, maxCellVisits, displayOptions, Map.of());
    }

    public StructurePreviewSettings withOrientation(BlockOrientation next) {
        if (next == orientation) return this;
        var rotation = StructureTransform.between(orientation, next);
        var cells = new Object2ObjectOpenHashMap<BlockPos, CellChoice>();
        cellOptions.forEach((position, choice) -> cells.put(rotation.apply(position), choice));
        return new StructurePreviewSettings(next, sliceCounts, maxOccurrences, maxCellVisits, displayOptions, cells);
    }

    public @Nullable Integer explicitOption(StructurePreviewModel.Cell cell) {
        var display = cell.display();
        if (display == null) return null;
        var local = cellOptions.get(cell.position());
        if (local != null && display.equals(local.display())) return local.option();
        return displayOptions.get(display);
    }

    public int option(StructurePreviewModel.Cell cell) {
        var explicit = explicitOption(cell);
        return explicit == null ? 0 : explicit;
    }

    public record CellChoice(StructureDisplay display, int option) {
        public CellChoice {
            Objects.requireNonNull(display);
            Objects.checkIndex(option, display.options().size());
        }
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
