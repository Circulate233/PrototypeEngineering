package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.testsupport.TestResources;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("DataFlowIssue")
class ResearchOverviewDisplayTest {
    private static final ResourceLocation ID = new ResourceLocation("overview_display", "research");
    private static final UUID PLAYER = new UUID(0, 1);

    private static ResearchRegistry registry() {
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(ID).maxLevel(20).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 20).researchTier(1).durationTicks(100)
                                                                     .resourceFormula(ResearchResourceFormula.builder().affine(ComputingResourceFixtures.COMPUTE, 100, 10).build()).build()).build());
        registry.freeze();
        return registry;
    }

    private static ResearchOverviewPacket.State state(long elapsed, double accumulated) {
        var resources = List.of(new ResearchOverviewPacket.ResourceProgress(ComputingResourceFixtures.COMPUTE, 2, 3, accumulated, 100));
        return new ResearchOverviewPacket.State(ID, 0, 0, 1, 0, "", true, false, false, false, 0,
            new ResearchOverviewPacket.Progress(1, elapsed, List.of(), resources), List.of(), 0, ResearchOverviewPacket.Availability.EMPTY);
    }

    private static ResearchOverviewPacket.Current current(long elapsed, double accumulated) {
        return new ResearchOverviewPacket.Current(ID, 1, 1, elapsed, 100, 2, false, "", null, List.of(),
            List.of(new ResearchOverviewPacket.ResourceProgress(ComputingResourceFixtures.COMPUTE, 2, 3, accumulated, 100)));
    }

    private static ResearchOverviewPacket.Environment environment(String team) {
        return new ResearchOverviewPacket.Environment(team, team, 1, 1, null, true, false, false, true, 0, "", 0, 0, PLAYER);
    }

    private static ResearchOverviewPacket roundTrip(ResearchOverviewPacket packet, ByteBuf buffer) {
        packet.writeToPacket(buffer);
        return new ResearchOverviewPacket(buffer);
    }

    @Test
    void changedNumericRowRoundTripsWithoutResendingDefinitionQueueOrState() {
        var display = new ResearchOverviewDisplay(registry());
        var client = new ResearchOverviewClientView();
        var queue = List.of(new ResearchOverviewPacket.Queue(1, ID, 1));
        var baseline = display.capture(List.of(state(1, 1)), queue, List.of(), current(1, 1), environment("team"));
        var fullBytes = Unpooled.buffer();
        var deltaBytes = Unpooled.buffer();
        try {
            client.receive(roundTrip(baseline, fullBytes));
            var next = display.capture(List.of(state(2, 3)), queue, List.of(), current(2, 3), environment("team"));
            assertNotNull(next);
            assertFalse(next.reset());
            assertNull(next.definitions());
            assertNull(next.queue());
            assertTrue(next.states().isEmpty());
            next.writeToPacket(deltaBytes);
            assertTrue(deltaBytes.readableBytes() < 45);
            client.receive(new ResearchOverviewPacket(deltaBytes));
            assertEquals(2, client.current().elapsed());
            assertEquals(3, client.current().resources().getFirst().accumulated());
            assertEquals(2, client.entry(ID).progress().elapsed());
            assertEquals(2, client.current().resources().getFirst().occupied());
            assertEquals(3, client.current().resources().getFirst().capacity());
            assertEquals(110, client.entry(ID).stage(2).resources().getFirst().totalAt(1, 2));
            assertNull(display.capture(List.of(state(2, 3)), queue, List.of(), current(2, 3), environment("team")));
            client.receive(roundTrip(display.fullPacket(), fullBytes));
            assertEquals(2, client.entry(ID).progress().elapsed());
        } finally {
            fullBytes.release();
            deltaBytes.release();
        }
    }

    @Test
    void manualMaterialDeltasPreserveAvailableAndRemainingIndependentlyOfOriginalTotal() {
        var display = new ResearchOverviewDisplay(registry());
        var client = new ResearchOverviewClientView();
        var key = new TestResources.Key(new TestResources.KeyType("overview_manual_material"), "material");
        var queue = List.of(new ResearchOverviewPacket.Queue(1, ID, 1));
        var before = new ResearchOverviewPacket.Current(ID, 1, 1, 10, 100, 0, true, "Contributor", PLAYER,
            List.of(new ResearchOverviewPacket.MaterialProgress(key, 3, 8, 32)), List.of());
        client.receive(display.capture(List.of(state(10, 0)), queue, List.of(), before, environment("team")));
        var after = new ResearchOverviewPacket.Current(ID, 1, 1, 20, 100, 0, true, "Contributor", PLAYER,
            List.of(new ResearchOverviewPacket.MaterialProgress(key, 4, 4, 32)), List.of());
        var packet = display.capture(List.of(state(20, 0)), queue, List.of(), after, environment("team"));
        assertNotNull(packet);
        assertFalse(packet.reset());
        assertNull(packet.definitions());
        var buffer = Unpooled.buffer();
        try {
            client.receive(roundTrip(packet, buffer));
            assertTrue(client.current().manual());
            var material = client.current().materials().getFirst();
            assertSame(key, material.key());
            assertEquals(4, material.available());
            assertEquals(4, material.remaining());
            assertEquals(32, material.total());
            assertEquals(material, client.entry(ID).progress().materials().getFirst());
            var depleted = new ResearchOverviewPacket.Current(ID, 1, 1, 20, 100, 0, true, "Contributor", PLAYER,
                List.of(new ResearchOverviewPacket.MaterialProgress(key, 0, 4, 32)), List.of());
            client.receive(roundTrip(display.capture(List.of(state(20, 0)), queue, List.of(), depleted, environment("team")), buffer));
            assertEquals(0, client.current().materials().getFirst().available());
            assertEquals(4, client.current().materials().getFirst().remaining());
        } finally {
            buffer.release();
        }
    }

    @Test
    void queueRemovalRetainsProgressAndTeamResetRemovesCurrentPanel() {
        var display = new ResearchOverviewDisplay(registry());
        var client = new ResearchOverviewClientView();
        var buffer = Unpooled.buffer();
        try {
            client.receive(roundTrip(display.capture(List.of(state(20, 20)), List.of(new ResearchOverviewPacket.Queue(1, ID, 1)), List.of(), current(20, 20), environment("team")), buffer));
            client.receive(roundTrip(display.capture(List.of(state(20, 20)), List.of(), List.of(), null, environment("team")), buffer));
            assertNull(client.current());
            assertTrue(client.queue().isEmpty());
            assertEquals(20, client.entry(ID).progress().elapsed());
            client.receive(roundTrip(display.capture(List.of(state(30, 30)), List.of(new ResearchOverviewPacket.Queue(1, ID, 1)), List.of(), current(30, 30), environment("team")), buffer));
            client.receive(roundTrip(display.capture(List.of(state(0, 0)), List.of(), List.of(), null, environment("other")), buffer));
            assertEquals("other", client.teamId());
            assertNull(client.current());
            assertEquals(0, client.entry(ID).progress().elapsed());
        } finally {
            buffer.release();
        }
    }

    @Test
    void capacityAndDeviceChangesRoundTripWithoutResendingResearchDefinitions() {
        var display = new ResearchOverviewDisplay(registry());
        var client = new ResearchOverviewClientView();
        var buffer = Unpooled.buffer();
        var firstId = new UUID(0, 11);
        var secondId = new UUID(0, 12);
        var stored = List.of(new ResearchOverviewPacket.Stored(ID, 3));
        var databases = List.of(new ResearchOverviewPacket.Database(firstId, "Primary", 1, 4, stored),
            new ResearchOverviewPacket.Database(secondId, "Standby", 1, 8, List.of()));
        var active = new ResearchOverviewPacket.Environment("team", "team", 2, 3, firstId,
            true, false, false, true, 0, "", 0, 0, PLAYER);
        var absent = new ResearchOverviewPacket.Environment("team", "team", 0, 0, null,
            true, false, false, true, 0, "", 0, 0, PLAYER);
        try {
            client.receive(roundTrip(display.capture(List.of(state(0, 0)), List.of(), databases, null, active), buffer));
            assertEquals(databases, client.databases());
            assertEquals(3, client.databases().getFirst().remaining());
            assertEquals(2, client.centerLevel());
            assertEquals(3, client.stationLevel());

            var outage = display.capture(List.of(state(0, 0)), List.of(), databases, null, absent);
            assertNotNull(outage);
            assertNull(outage.definitions());
            assertNull(outage.databases());
            assertTrue(outage.states().isEmpty());
            client.receive(roundTrip(outage, buffer));
            assertEquals(databases, client.databases());
            assertEquals(0, client.centerLevel());
            assertEquals(0, client.stationLevel());

            var expanded = List.of(new ResearchOverviewPacket.Database(firstId, "Primary", 1, 4, stored),
                new ResearchOverviewPacket.Database(secondId, "Standby", 1, 12, stored));
            var capacityChange = display.capture(List.of(state(0, 0)), List.of(), expanded, null, absent);
            assertNotNull(capacityChange);
            assertNull(capacityChange.definitions());
            assertNull(capacityChange.environment());
            client.receive(roundTrip(capacityChange, buffer));
            assertEquals(expanded, client.databases());
            assertEquals(11, client.databases().get(1).remaining());
            assertEquals(3, client.databases().get(1).level(ID));
        } finally {
            buffer.release();
        }
    }
}
