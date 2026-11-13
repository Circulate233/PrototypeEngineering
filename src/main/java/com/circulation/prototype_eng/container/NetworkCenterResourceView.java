package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * Client view retains resource definitions and applies indexed numeric changes in place.
 */
public final class NetworkCenterResourceView {
    private final Int2ObjectLinkedOpenHashMap<Row> indexed = new Int2ObjectLinkedOpenHashMap<>();
    private final ObjectArrayList<Row> rows = new ObjectArrayList<>();

    public void receive(NetworkCenterResourcePacket packet) {
        if (packet.full()) indexed.clear();
        for (int index = 0; index < packet.removed().size(); index++) indexed.remove(packet.removed().getInt(index));
        for (var entry : packet.added())
            indexed.put(entry.index(), new Row(entry.resource(), entry.occupied(), entry.capacity()));
        for (var entry : packet.changed()) {
            var row = indexed.get(entry.index());
            if ((entry.fields() & NetworkCenterResourcePacket.OCCUPIED) != 0) row.occupied = entry.occupied();
            if ((entry.fields() & NetworkCenterResourcePacket.CAPACITY) != 0) row.capacity = entry.capacity();
            row.text = null;
        }
        if (packet.full() || !packet.removed().isEmpty() || !packet.added().isEmpty()) {
            rows.clear();
            rows.addAll(indexed.values());
        }
    }

    /**
     * Borrowed display order; structural changes rebuild it, numeric updates retain the rows.
     */
    public List<Row> rows() {
        return rows;
    }

    public void invalidateText() {
        for (var row : rows) row.text = null;
    }

    public static final class Row {
        private final NetworkResourceType resource;
        private double occupied, capacity;
        private String text;

        private Row(NetworkResourceType resource, double occupied, double capacity) {
            this.resource = resource;
            this.occupied = occupied;
            this.capacity = capacity;
        }

        public double occupied() {
            return occupied;
        }

        public double capacity() {
            return capacity;
        }

        @SideOnly(Side.CLIENT)
        public String text() {
            if (text == null)
                text = resource.displayName() + "：" + resource.format(occupied) + " / " + resource.format(capacity);
            return text;
        }
    }
}
