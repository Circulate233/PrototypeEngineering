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
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
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
        return state(elapsed, accumulated, true);
    }

    private static ResearchOverviewPacket.State state(long elapsed, double accumulated, boolean canStart) {
        var resources = List.of(new ResearchOverviewPacket.ResourceProgress(ComputingResourceFixtures.COMPUTE, 2, 3, accumulated, 100));
        return new ResearchOverviewPacket.State(ID, 0, 0, 1, 0, "", canStart, false, false, false, 0,
            new ResearchOverviewPacket.Progress(1, elapsed, List.of(), resources), List.of(), 0, ResearchOverviewPacket.Availability.EMPTY);
    }

    private static ResearchOverviewPacket.Current current(long elapsed, double accumulated) {
        return new ResearchOverviewPacket.Current(ID, 1, 1, elapsed, 100, 2, false, "", null, List.of(),
            List.of(new ResearchOverviewPacket.ResourceProgress(ComputingResourceFixtures.COMPUTE, 2, 3, accumulated, 100)));
    }

    private static ResearchOverviewPacket.Environment environment(String team) {
        return environment(team, 0);
    }

    private static ResearchOverviewPacket.Environment environment(String team, int queued) {
        return new ResearchOverviewPacket.Environment(team, team, 1, 1, null, true, false, false, true, 0, "", 0, 0, PLAYER, queued);
    }

    private static ResearchOverviewPacket roundTrip(ResearchOverviewPacket packet, ByteBuf buffer) {
        packet.writeToPacket(buffer);
        return new ResearchOverviewPacket(buffer);
    }

    @Test
    void changedNumericRowRoundTripsWithoutResendingDefinitionQueueOrState() {
        var display = new ResearchOverviewDisplay(registry());
        var client = new ResearchOverviewClientView();
        var queue = List.of(new ResearchOverviewPacket.Queue(1, ID, 1, 0));
        var env = environment("team", queue.size());
        var baseline = display.capture(List.of(state(1, 1)), queue, List.of(), current(1, 1), env);
        var fullBytes = Unpooled.buffer();
        var deltaBytes = Unpooled.buffer();
        try {
            client.receive(1, roundTrip(baseline, fullBytes));
            var next = display.capture(List.of(state(2, 3)), queue, List.of(), current(2, 3), env);
            assertNotNull(next);
            assertFalse(next.reset());
            assertNull(next.definitions());
            assertNull(next.queue());
            assertTrue(next.states().isEmpty());
            next.writeToPacket(deltaBytes);
            assertTrue(deltaBytes.readableBytes() < 45);
            client.receive(1, new ResearchOverviewPacket(deltaBytes));
            assertEquals(2, client.current().elapsed());
            assertEquals(3, client.current().resources().getFirst().accumulated());
            assertEquals(2, client.entry(ID).progress().elapsed());
            assertEquals(2, client.current().resources().getFirst().occupied());
            assertEquals(3, client.current().resources().getFirst().capacity());
            assertNull(display.capture(List.of(state(2, 3)), queue, List.of(), current(2, 3), env));
            var stateOnly = display.capture(List.of(state(2, 3, false)), queue, List.of(), current(2, 3), env);
            assertFalse(stateOnly.currentChanged());
            client.receive(1, roundTrip(stateOnly, fullBytes));
            assertFalse(client.entry(ID).canStart());
            assertEquals(client.current().elapsed(), client.entry(ID).progress().elapsed());
            client.receive(1, roundTrip(display.fullPacket(), fullBytes));
            assertEquals(2, client.entry(ID).progress().elapsed());
        } finally {
            fullBytes.release();
            deltaBytes.release();
        }
    }

    @Test
    void detailsRoundTripForTheCurrentSelectionAndDiscardAnObsoleteReply() {
        var registry = registry();
        var display = new ResearchOverviewDisplay(registry);
        var client = new ResearchOverviewClientView();
        var bytes = Unpooled.buffer();
        try {
            client.receive(1, roundTrip(display.capture(List.of(state(0, 0)), List.of(), List.of(), null, environment("team")), bytes));
            assertTrue(client.entry(ID).stage(2).resources().isEmpty());
            var definition = ResearchOverviewDisplay.definition(registry.require(ID), Set.of(ID), true);
            var payload = new ResearchOverviewDetail(definition, "{\"description\":[\"研究详情\"]}");
            payload.write(new PacketBuffer(bytes));
            var reply = ResearchOverviewDetail.read(new PacketBuffer(bytes));

            client.expectDetail(ID, 1);
            client.expectDetail(ID, 2);
            client.receiveDetail(1, ID, 1, 4, reply);
            assertNull(client.detail(ID));
            client.receiveDetail(1, ID, 2, 5, reply);
            assertEquals(payload.json(), client.detail(ID).json());
            assertEquals(110, client.entry(ID).stage(2).resources().getFirst().totalAt(1, 2));
            client.receiveDetail(1, ID, 2, 4, null);
            assertSame(reply, client.detail(ID));
            assertEquals(5, client.detailRevision(ID));

            var newTeam = display.capture(List.of(state(0, 0)), List.of(), List.of(), null, environment("other"));
            client.receive(2, roundTrip(newTeam, bytes));
            assertNull(client.detail(ID));
            client.receiveDetail(1, ID, 2, 5, reply);
            assertNull(client.detail(ID));
            var updated = new ResearchOverviewDetail(definition, "{\"summary\":\"Other team\"}");
            client.receiveDetail(2, ID, 2, 5, updated);
            assertSame(updated, client.detail(ID));
            client.receiveDetail(1, ID, 2, 6, null);
            assertSame(updated, client.detail(ID));
            client.receiveDetail(2, ID, 2, 6, null);
            assertNull(client.detail(ID));
            assertTrue(client.detailUnavailable(ID));
            assertEquals(6, client.detailRevision(ID));

            client.expectDetail(null, 3);
            assertNull(client.detail(ID));
            assertTrue(client.entry(ID).stage(2).resources().isEmpty());
        } finally {
            bytes.release();
        }
    }

    @Test
    void manualMaterialDeltasPreserveAvailableAndRemainingIndependentlyOfOriginalTotal() {
        var display = new ResearchOverviewDisplay(registry());
        var client = new ResearchOverviewClientView();
        var key = new TestResources.Key(new TestResources.KeyType("overview_manual_material"), "material");
        var queue = List.of(new ResearchOverviewPacket.Queue(1, ID, 1, 0));
        var before = new ResearchOverviewPacket.Current(ID, 1, 1, 10, 100, 0, true, "Contributor", PLAYER,
            List.of(new ResearchOverviewPacket.MaterialProgress(key, 3, 8, 32)), List.of());
        client.receive(1, display.capture(List.of(state(10, 0)), queue, List.of(), before, environment("team")));
        var after = new ResearchOverviewPacket.Current(ID, 1, 1, 20, 100, 0, true, "Contributor", PLAYER,
            List.of(new ResearchOverviewPacket.MaterialProgress(key, 4, 4, 32)), List.of());
        var packet = display.capture(List.of(state(20, 0)), queue, List.of(), after, environment("team"));
        assertNotNull(packet);
        assertFalse(packet.reset());
        assertNull(packet.definitions());
        var buffer = Unpooled.buffer();
        try {
            client.receive(1, roundTrip(packet, buffer));
            assertTrue(client.current().manual());
            var material = client.current().materials().getFirst();
            assertSame(key, material.key());
            assertEquals(4, material.available());
            assertEquals(4, material.remaining());
            assertEquals(32, material.total());
            assertEquals(material, client.entry(ID).progress().materials().getFirst());
            var depleted = new ResearchOverviewPacket.Current(ID, 1, 1, 20, 100, 0, true, "Contributor", PLAYER,
                List.of(new ResearchOverviewPacket.MaterialProgress(key, 0, 4, 32)), List.of());
            client.receive(1, roundTrip(display.capture(List.of(state(20, 0)), queue, List.of(), depleted, environment("team")), buffer));
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
            client.receive(1, roundTrip(display.capture(List.of(state(20, 20)), List.of(new ResearchOverviewPacket.Queue(1, ID, 1, 0)), List.of(), current(20, 20), environment("team", 1)), buffer));
            client.receive(1, roundTrip(display.capture(List.of(state(20, 20)), List.of(), List.of(), null, environment("team")), buffer));
            assertNull(client.current());
            assertTrue(client.queue().isEmpty());
            assertEquals(20, client.entry(ID).progress().elapsed());
            client.receive(1, roundTrip(display.capture(List.of(state(30, 30)), List.of(new ResearchOverviewPacket.Queue(1, ID, 1, 0)), List.of(), current(30, 30), environment("team", 1)), buffer));
            client.receive(2, roundTrip(display.capture(List.of(state(0, 0)), List.of(), List.of(), null, environment("other")), buffer));
            assertEquals("other", client.teamId());
            assertNull(client.current());
            assertEquals(0, client.entry(ID).progress().elapsed());
        } finally {
            buffer.release();
        }
    }

    @Test
    void hiddenHeadLeavesItsSlotEmptyAndKeepsItsCapacityOccupied() {
        var display = new ResearchOverviewDisplay(registry());
        var client = new ResearchOverviewClientView();
        var buffer = Unpooled.buffer();
        try {
            var waiting = new ResearchOverviewPacket.Queue(1, ID, 1, 1);
            var packet = display.capture(List.of(state(0, 0)), List.of(waiting), List.of(), null, environment("team", 2));
            client.receive(1, roundTrip(packet, buffer));
            assertNull(client.queueSlot(0));
            assertEquals(waiting, client.queueSlot(1));
            assertEquals(waiting, client.queued(ID));
            assertEquals(2, client.queuedCount());
            assertNull(client.current());

            var running = new ResearchOverviewPacket.Queue(waiting.entryId(), ID, 1, 0);
            var advanced = display.capture(List.of(state(20, 20)), List.of(running), List.of(), current(20, 20), environment("team", 1));
            client.receive(1, roundTrip(advanced, buffer));
            assertEquals(running, client.queueSlot(0));
            assertNull(client.queueSlot(1));
            assertEquals(1, client.queuedCount());
            assertEquals(ID, client.current().id());
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
            true, false, false, true, 0, "", 0, 0, PLAYER, 0);
        var absent = new ResearchOverviewPacket.Environment("team", "team", 0, 0, null,
            true, false, false, true, 0, "", 0, 0, PLAYER, 0);
        try {
            client.receive(1, roundTrip(display.capture(List.of(state(0, 0)), List.of(), databases, null, active), buffer));
            assertEquals(databases, client.databases());
            assertEquals(3, client.databases().getFirst().remaining());
            assertEquals(2, client.centerLevel());
            assertEquals(3, client.stationLevel());

            var outage = display.capture(List.of(state(0, 0)), List.of(), databases, null, absent);
            assertNotNull(outage);
            assertNull(outage.definitions());
            assertNull(outage.databases());
            assertTrue(outage.states().isEmpty());
            client.receive(1, roundTrip(outage, buffer));
            assertEquals(databases, client.databases());
            assertEquals(0, client.centerLevel());
            assertEquals(0, client.stationLevel());

            var expanded = List.of(new ResearchOverviewPacket.Database(firstId, "Primary", 1, 4, stored),
                new ResearchOverviewPacket.Database(secondId, "Standby", 1, 12, 4, stored));
            var capacityChange = display.capture(List.of(state(0, 0)), List.of(), expanded, null, absent);
            assertNotNull(capacityChange);
            assertNull(capacityChange.definitions());
            assertNull(capacityChange.environment());
            client.receive(1, roundTrip(capacityChange, buffer));
            assertEquals(expanded, client.databases());
            assertEquals(8, client.databases().get(1).remaining());
            assertEquals(3, client.databases().get(1).level(ID));
        } finally {
            buffer.release();
        }
    }
}
