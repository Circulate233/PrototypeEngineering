package com.circulation.prototype_eng.api.research.station;

import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.storage.StorageFence;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchStationsTest {
    private static final UUID OWNER = new UUID(0, 1);

    private static ResourceLocation id(String name) {
        return new ResourceLocation("research_stations_test", name);
    }

    @Test
    void dimensionsRotateAndUuidOrderChoosesPrimaryWhileStationsShareOnePool() {
        try (var fixture = new StationFixtures(10, 2)) {
            var first = fixture.station(2, -1);
            var second = fixture.station(2, -1);
            var otherDimension = fixture.station(2, 3);
            fixture.refresh();
            var selection = Objects.requireNonNull(fixture.selector.capture("team", 2));
            var ordered = new ObjectArrayList<>(List.of(first, second));
            ordered.sort(Comparator.comparing(entry -> fixture.identity(entry).identity().machineId()));
            assertSame(ordered.getFirst().machine(), selection.primary());
            assertEquals(List.of(fixture.identity(ordered.get(0)).identity().machineId(), fixture.identity(ordered.get(1)).identity().machineId()), List.copyOf(selection.stationIds()));
            assertEquals(-1, selection.dimension());
            assertEquals(2, selection.hatches().resources().size());
            assertSame(ordered.getFirst().warehouse(), selection.hatches().resources().getFirst());
            assertSame(otherDimension.machine(), Objects.requireNonNull(fixture.selector.capture("team", 2)).primary());
            assertEquals(-1, Objects.requireNonNull(fixture.selector.capture("team", 2)).dimension());
        }
    }

    @Test
    void realDataCenterTierRolesFormationTeamsAndDuplicateIdentityFilterCandidates() {
        try (var fixture = new StationFixtures(10, 1)) {
            var station = fixture.station(2, 0);
            fixture.role(NetworkDeviceRole.DEVICE, 2, 0, OWNER, fixture.world);
            fixture.role(NetworkDeviceRole.RESEARCH_STATION, 2, 0, new UUID(0, 2), fixture.world);
            var unformed = fixture.station(2, 0);
            Objects.requireNonNull(unformed.machine().lifecycle()).invalidateStructure();
            fixture.station(1, 0);
            fixture.refresh();
            assertNull(fixture.selector.capture("team", 2));
            fixture.dataCenter(2);
            fixture.refresh();
            assertSame(station.machine(), Objects.requireNonNull(fixture.selector.capture("team", 2)).primary());
            var saved = station.assembly().encode(station.machine());
            var clone = station.assembly().restore(fixture.host(Objects.requireNonNull(station.machine().type()).id(), 0, OWNER, fixture.world), saved, new MachineLifecycleRegistry());
            fixture.attach(clone, station.assembly(), station.role());
            fixture.refresh();
            assertNull(fixture.selector.capture("team", 2));
        }
    }

    @Test
    void stationLeasesLimitParticipationButDoNotConsumeIndependentResearchNumericSupply() {
        try (var fixture = new StationFixtures(1, 2)) {
            fixture.station(2, 0);
            fixture.station(2, 0);
            fixture.refresh();
            var first = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(1, first.stationIds().size());
            var role = fixture.roleOf(first.primary());
            var permit = Objects.requireNonNull(role.workPermit());
            var ordinary = Objects.requireNonNull(permit.reserve(id("ordinary"), Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 30)));
            assertTrue(ordinary.commit());
            var repeated = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(first.stationIds(), repeated.stationIds(), "ALREADY_GRANTED remains a valid contributor");
            assertEquals(100, repeated.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(70, permit.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            var queue = new TeamResearchQueue(fixture.research, repeated.teamId());
            var head = queue.enqueue(id("research"), 1, OWNER, 0);
            var plan = queue.planSegment(head.key(), repeated.stationIds(), repeated.numericResources(), repeated.capacity(), 0).orElseThrow();
            assertEquals(3, plan.grantedResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(70, permit.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void renewalKeepsPrimaryAndExactPoolAcrossTicksAndRejectsNewHatchBindings() {
        try (var fixture = new StationFixtures(10, 2)) {
            var first = fixture.station(2, 0);
            fixture.station(2, 0);
            fixture.station(2, 2);
            fixture.refresh();
            var selected = Objects.requireNonNull(fixture.selector.capture("team", 2));
            fixture.nextTick();
            assertFalse(selected.current());
            var renewed = Objects.requireNonNull(fixture.selector.renew(selected));
            assertSame(selected.primary(), renewed.primary());
            assertSame(selected.hatches(), renewed.hatches());
            assertEquals(selected.stationIds(), renewed.stationIds());
            assertTrue(renewed.current());
            assertEquals(2, Objects.requireNonNull(fixture.selector.capture("team", 2)).dimension(), "Renewal does not rotate selection");
            fixture.replaceHatches(first, new MachineHatches(List.of(first.warehouse()), List.of(first.energy()), List.of(first.energy())));
            assertFalse(renewed.current());
            assertNull(fixture.selector.renew(selected));
        }
    }

    @Test
    void equalDimensionNeverPoolsDifferentWorldSessionObjects() {
        try (var fixture = new StationFixtures(10, 2)) {
            fixture.station(2, 0);
            fixture.role(NetworkDeviceRole.RESEARCH_STATION, 2, 0, OWNER, new Object());
            fixture.refresh();
            var selected = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(1, selected.stationIds().size());
            assertEquals(1, selected.hatches().resources().size());
            assertTrue(selected.current());
        }
    }

    @Test
    void exactPublicationProofIsReadInsideForeignSourceFenceWithoutNewStorageLocks() {
        try (var fixture = new StationFixtures(10, 2)) {
            var station = fixture.station(2, 0);
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            var permit = Objects.requireNonNull(station.machine().components().get(station.role())).workPermit();
            var snapshot = Objects.requireNonNull(permit).deviceSnapshot();
            var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(station.energy().context())));
            try {
                assertTrue(fence.access(capture::current));
            } finally {
                fence.release();
            }
            fixture.network.revoke();
            assertFalse(capture.current());
            assertSame(snapshot, permit.deviceSnapshot(), "Old capability exposes its own frozen snapshot, never a fresh capture");
            Objects.requireNonNull(station.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertNull(fixture.selector.renew(capture));
        }
    }
}
