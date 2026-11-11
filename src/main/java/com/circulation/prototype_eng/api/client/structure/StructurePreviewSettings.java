package com.circulation.prototype_eng.api.client.structure;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.AbstractObject2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Frozen preview choices; limits concern presentation memory/work, never gameplay quantities.
 */
public record StructurePreviewSettings(BlockOrientation orientation, Object2IntMap<StructurePreviewPath> sliceCounts,
                                       int maxOccurrences, int maxCellVisits,
                                       Object2IntMap<StructureDisplay> displayOptions,
                                       Map<BlockPos, CellChoice> cellOptions) {
    public StructurePreviewSettings {
        Objects.requireNonNull(orientation);
        if (maxOccurrences <= 0 || maxCellVisits <= 0) throw new IllegalArgumentException("Nonpositive preview limits");
        var counts = new Object2IntOpenHashMap<StructurePreviewPath>();
        for (var entry : sliceCounts.object2IntEntrySet()) counts.put(Objects.requireNonNull(entry.getKey()), entry.getIntValue());
        sliceCounts = Object2IntMaps.unmodifiable(new FrozenChoices<>(counts));
        var options = new Object2IntOpenHashMap<StructureDisplay>();
        for (var entry : displayOptions.object2IntEntrySet()) {
            var display = Objects.requireNonNull(entry.getKey());
            int selected = entry.getIntValue();
            if (selected < 0 || selected >= display.options().size())
                throw new IllegalArgumentException("Invalid representative choice");
            options.put(display, selected);
        }
        displayOptions = Object2IntMaps.unmodifiable(new FrozenChoices<>(options));
        var cells = new Object2ObjectOpenHashMap<BlockPos, CellChoice>();
        cellOptions.forEach((position, choice) -> {
            var fixed = Objects.requireNonNull(position).toImmutable();
            if (cells.putIfAbsent(fixed, Objects.requireNonNull(choice)) != null)
                throw new IllegalArgumentException("Duplicate representative position");
        });
        cellOptions = Object2ObjectMaps.unmodifiable(cells);
    }

    public StructurePreviewSettings(BlockOrientation orientation, Object2IntMap<StructurePreviewPath> sliceCounts,
                                    int maxOccurrences, int maxCellVisits, Object2IntMap<StructureDisplay> displayOptions) {
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
        return displayOptions.containsKey(display) ? displayOptions.getInt(display) : null;
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

    /** Entry snapshots close the setValue escape left by fastutil's map wrapper alone. */
    private static final class FrozenChoices<K> extends AbstractObject2IntMap<K> {
        private final Object2IntMap<K> values;
        private final ObjectSet<Object2IntMap.Entry<K>> entries;

        private FrozenChoices(Object2IntMap<K> owned) {
            values = owned;
            var snapshot = new ObjectOpenHashSet<Object2IntMap.Entry<K>>();
            for (var entry : owned.object2IntEntrySet())
                snapshot.add(new BasicEntry<>(entry.getKey(), entry.getIntValue()));
            entries = ObjectSets.unmodifiable(snapshot);
        }

        @Override
        public ObjectSet<Object2IntMap.Entry<K>> object2IntEntrySet() {
            return entries;
        }

        @Override
        public int getInt(Object key) {
            return values.getInt(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public int size() {
            return values.size();
        }
    }
}
