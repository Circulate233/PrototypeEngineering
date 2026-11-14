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
            row.supplyText = null;
            row.amountsText = null;
            row.capacityText = null;
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
        for (var row : rows) {
            row.text = null;
            row.supplyText = null;
            row.amountsText = null;
            row.capacityText = null;
            row.name = null;
        }
    }

    public static final class Row {
        private final NetworkResourceType resource;
        private double occupied, capacity;
        private String text;
        private String supplyText;
        private String amountsText, capacityText, name;

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
        public String name() {
            if (name == null) name = resource.displayName();
            return name;
        }

        @SideOnly(Side.CLIENT)
        public String amountsText() {
            if (amountsText == null) amountsText = resource.format(occupied) + " / " + resource.format(capacity);
            return amountsText;
        }

        @SideOnly(Side.CLIENT)
        public String text() {
            if (text == null)
                text = resource.displayName() + "：" + resource.format(occupied) + " / " + resource.format(capacity);
            return text;
        }

        @SideOnly(Side.CLIENT)
        public String capacityText() {
            if (capacityText == null) capacityText = resource.format(capacity);
            return capacityText;
        }

        @SideOnly(Side.CLIENT)
        public String supplyText() {
            if (supplyText == null) supplyText = resource.displayName() + "：" + resource.format(capacity);
            return supplyText;
        }
    }
}
