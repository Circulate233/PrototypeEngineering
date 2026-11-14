package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKeyTypes;
import ae2.container.guisync.DataSynchronization;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.testsupport.TestResources;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComputerDetailsSyncTest {
    @Test
    void aeTransfersComponentDefinitionsAndCompositionChanges() {
        var type = new TestResources.KeyType("computer_gui_inputs");
        AEKeyTypes.register(type);
        var item = new TestResources.Key(type, "component");
        var supply = Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 20.5);
        var demand = Reference2DoubleMaps.singleton(ComputingResourceFixtures.QUBIT, 3.5);
        var server = new Host();
        var client = new Host();
        var outgoing = new DataSynchronization(server);
        var incoming = new DataSynchronization(client);
        server.packet = new ComputerDetailsPacket(List.of(new ComputerDetailsPacket.Component(item, 2, 4, 8, supply, demand)));
        transfer(outgoing, incoming, true);
        var component = client.packet.components().getFirst();
        assertEquals(item, component.item());
        assertEquals(2, component.count());
        assertEquals(4, component.concurrency());
        assertEquals(8, component.energy());
        assertEquals(20.5, component.provided().values().iterator().nextDouble());
        assertEquals(3.5, component.required().values().iterator().nextDouble());
        assertFalse(outgoing.hasChanges());
        server.packet = new ComputerDetailsPacket(List.of(new ComputerDetailsPacket.Component(item, 1, 4, 8, supply, demand)));
        transfer(outgoing, incoming, false);
        assertEquals(1, client.packet.components().getFirst().count());
        server.packet = new ComputerDetailsPacket(List.of());
        transfer(outgoing, incoming, false);
        assertTrue(client.packet.components().isEmpty());
    }

    private static void transfer(DataSynchronization outgoing, DataSynchronization incoming, boolean full) {
        var data = Unpooled.buffer();
        try {
            if (full) outgoing.writeFull(data);
            else outgoing.writeUpdate(data);
            incoming.readUpdate(data);
        } finally {
            data.release();
        }
    }

    private static final class Host {
        @GuiSync(0)
        public ComputerDetailsPacket packet = ComputerDetailsPacket.EMPTY;
    }
}
