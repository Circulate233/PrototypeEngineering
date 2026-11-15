package com.circulation.prototype_eng.api.client.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;


/**
 * Frozen preview choices; limits concern presentation memory/work, never gameplay quantities.
 * Maps and their keys are borrowed read-only. Keep them stable while these settings or a derived
 * preview are retained; create new maps when changing choices. Position keys must be immutable.
 */
public record StructurePreviewSettings(BlockOrientation orientation, Object2IntMap<StructurePreviewPath> sliceCounts,
                                       int maxOccurrences, int maxCellVisits,
                                       Object2IntMap<StructureDisplay> displayOptions,
                                       BlockPos2ObjectMap<CellChoice> cellOptions) {
    public StructurePreviewSettings {
        if (maxOccurrences <= 0 || maxCellVisits <= 0) throw new IllegalArgumentException("Nonpositive preview limits");
    }

    public StructurePreviewSettings(BlockOrientation orientation, Object2IntMap<StructurePreviewPath> sliceCounts,
                                    int maxOccurrences, int maxCellVisits, Object2IntMap<StructureDisplay> displayOptions) {
        this(orientation, sliceCounts, maxOccurrences, maxCellVisits, displayOptions, BlockPos2ObjectMap.emptyMap());
    }

    public StructurePreviewSettings(BlockOrientation orientation, Object2IntMap<StructurePreviewPath> sliceCounts,
                                    int maxOccurrences, int maxCellVisits) {
        this(orientation, sliceCounts, maxOccurrences, maxCellVisits, Object2IntMaps.emptyMap());
    }

    /**
     * Unselected slices use their server-declared minimum.
     */
    public StructurePreviewSettings(BlockOrientation orientation) {
        this(orientation, Object2IntMaps.emptyMap(), 65_536, 1_048_576);
    }

    public StructurePreviewSettings withOrientation(BlockOrientation next) {
        if (next == orientation) return this;
        var rotation = StructureTransform.between(orientation, next);
        var cells = new BlockPos2ObjectMap<CellChoice>(cellOptions.size());
        for (var entries = cellOptions.fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            cells.put(rotation.apply(entry.getLongKey()), entry.getValue());
        }
        return new StructurePreviewSettings(next, sliceCounts, maxOccurrences, maxCellVisits, displayOptions, cells);
    }

    /**
     * Returns -1 when the cell has no explicit representative choice.
     */
    public int explicitOption(StructurePreviewModel.Cell cell) {
        var display = cell.display();
        if (display == null) return -1;
        var local = cellOptions.get(cell.position());
        if (local != null && display.equals(local.display())) return local.option();
        return displayOptions.containsKey(display) ? displayOptions.getInt(display) : -1;
    }

    public int option(StructurePreviewModel.Cell cell) {
        var explicit = explicitOption(cell);
        return Math.max(explicit, 0);
    }

    public record CellChoice(StructureDisplay display, int option) {
    }

}
