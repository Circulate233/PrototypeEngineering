package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Per-container server delta baseline; unchanged captures allocate no packets or row lists.
 */
public final class NetworkCenterDisplay {
    private final Reference2ObjectLinkedOpenHashMap<NetworkResourceType, Row> rows = new Reference2ObjectLinkedOpenHashMap<>();
    private int nextIndex;

    /**
     * The map is the network's completed external supply; zero quantities are hidden.
     */
    public @Nullable NetworkCenterResourcePacket capture(Reference2DoubleMap<NetworkResourceType> resources, ToDoubleFunction<NetworkResourceType> occupied) {
        IntArrayList removed = null;
        ObjectArrayList<NetworkCenterResourcePacket.Definition> added = null;
        ObjectArrayList<NetworkCenterResourcePacket.Change> changed = null;
        for (var entries = rows.reference2ObjectEntrySet().fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            if (resources.getDouble(entry.getKey()) != 0) continue;
            if (removed == null) removed = new IntArrayList();
            removed.add(entry.getValue().index);
            entries.remove();
        }
        for (var entries = Reference2DoubleMaps.fastIterator(resources); entries.hasNext(); ) {
            var entry = entries.next();
            double capacity = entry.getDoubleValue();
            if (capacity == 0) continue;
            var type = entry.getKey();
            double used = occupied.applyAsDouble(type);
            var row = rows.get(type);
            if (row == null) {
                row = new Row(nextIndex++, used, capacity);
                rows.put(type, row);
                if (added == null) added = new ObjectArrayList<>();
                added.add(new NetworkCenterResourcePacket.Definition(row.index, type, used, capacity));
            } else {
                int fields = (row.occupied != used ? NetworkCenterResourcePacket.OCCUPIED : 0)
                    | (row.capacity != capacity ? NetworkCenterResourcePacket.CAPACITY : 0);
                if (fields == 0) continue;
                if (changed == null) changed = new ObjectArrayList<>();
                changed.add(new NetworkCenterResourcePacket.Change(row.index, fields, used, capacity));
                row.occupied = used;
                row.capacity = capacity;
            }
        }
        return removed == null && added == null && changed == null ? null : new NetworkCenterResourcePacket(false,
            removed == null ? IntLists.emptyList() : removed, added == null ? List.of() : added, changed == null ? List.of() : changed);
    }

    /**
     * Complete baseline for a new AE container listener.
     */
    public NetworkCenterResourcePacket fullPacket() {
        var added = new ObjectArrayList<NetworkCenterResourcePacket.Definition>(rows.size());
        for (var entries = rows.reference2ObjectEntrySet().fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            var row = entry.getValue();
            added.add(new NetworkCenterResourcePacket.Definition(row.index, entry.getKey(), row.occupied, row.capacity));
        }
        return new NetworkCenterResourcePacket(true, IntLists.emptyList(), added, List.of());
    }

    private static final class Row {
        final int index;
        double occupied, capacity;

        Row(int index, double occupied, double capacity) {
            this.index = index;
            this.occupied = occupied;
            this.capacity = capacity;
        }
    }
}
