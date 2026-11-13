package com.circulation.prototype_eng.container;

import ae2.container.guisync.DataSynchronization;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCenterResourceSyncTest {
    @Test
    void aeAppliesIndexedDeltasRemovalsAndFreshFullBaselines() {
        var supply = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        var occupied = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        supply.put(ComputingResourceFixtures.COMPUTE, 20.5);
        supply.put(ComputingResourceFixtures.QUBIT, 10.0);
        var display = new NetworkCenterDisplay();
        display.capture(supply, occupied::getDouble);
        var server = new SyncHost();
        var client = new SyncHost();
        var outgoing = new DataSynchronization(server);
        var incoming = new DataSynchronization(client);
        var view = new NetworkCenterResourceView();
        server.packet = display.fullPacket();
        int initialBytes = transfer(outgoing, incoming, view, client, true);
        assertEquals(2, view.rows().size());
        assertFalse(outgoing.hasChanges());
        assertNull(display.capture(supply, occupied::getDouble));
        occupied.put(ComputingResourceFixtures.COMPUTE, 1.25);
        server.packet = Objects.requireNonNull(display.capture(supply, occupied::getDouble));
        assertTrue(server.packet.added().isEmpty());
        assertEquals(NetworkCenterResourcePacket.OCCUPIED, server.packet.changed().getFirst().fields());
        var rows = view.rows();
        int updateBytes = transfer(outgoing, incoming, view, client, false);
        assertSame(rows, view.rows());
        assertTrue(updateBytes < initialBytes);
        var compute = view.rows().stream().filter(row -> row.capacity() == 20.5).findFirst().orElseThrow();
        assertEquals(1.25, compute.occupied());
        supply.put(ComputingResourceFixtures.COMPUTE, 40.0);
        server.packet = Objects.requireNonNull(display.capture(supply, occupied::getDouble));
        assertEquals(NetworkCenterResourcePacket.CAPACITY, server.packet.changed().getFirst().fields());
        transfer(outgoing, incoming, view, client, false);
        assertEquals(40.0, compute.capacity());
        assertEquals(1.25, compute.occupied());
        supply.put(ComputingResourceFixtures.QUBIT, 0);
        server.packet = Objects.requireNonNull(display.capture(supply, occupied::getDouble));
        transfer(outgoing, incoming, view, client, false);
        assertEquals(1, view.rows().size());
        assertSame(compute, view.rows().getFirst());
        server.packet = display.fullPacket();
        var fresh = new SyncHost();
        var freshView = new NetworkCenterResourceView();
        transfer(outgoing, new DataSynchronization(fresh), freshView, fresh, true);
        assertEquals(40.0, freshView.rows().getFirst().capacity());
        assertEquals(1.25, freshView.rows().getFirst().occupied());
        supply.clear();
        server.packet = Objects.requireNonNull(display.capture(supply, occupied::getDouble));
        transfer(outgoing, incoming, view, client, false);
        assertTrue(view.rows().isEmpty());
    }

    private static int transfer(DataSynchronization outgoing, DataSynchronization incoming, NetworkCenterResourceView view, SyncHost client, boolean full) {
        var data = Unpooled.buffer();
        try {
            if (full) outgoing.writeFull(data);
            else outgoing.writeUpdate(data);
            int bytes = data.readableBytes();
            incoming.readUpdate(data);
            view.receive(client.packet);
            return bytes;
        } finally {
            data.release();
        }
    }

    private static final class SyncHost {
        @GuiSync(0)
        public NetworkCenterResourcePacket packet = NetworkCenterResourcePacket.EMPTY;
    }
}
