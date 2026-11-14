package com.circulation.prototype_eng.api.research.station;

import static com.circulation.prototype_eng.api.research.station.StationFixtures.OWNER;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.TYPE;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


@SuppressWarnings("DataFlowIssue") // Test setup establishes successful admission or capture before reading the result.
class ResearchStationWorkTest {
    private static final TestResources.Key A = new TestResources.Key(TYPE, "material");

    static ResearchDefinition definition(long time, double total, long material) {
        var stage = ResearchLevelStage.builder(1, 1).durationTicks(time).researchTier(2)
                                      .resourceFormula(ResearchResourceFormula.builder().constant(ComputingResourceFixtures.COMPUTE, total).build());
        if (material > 0) stage.material(A, material);
        return ResearchDefinition.builder(id("research")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage.build()).build();
    }

    private static TeamResearchQueue queue(StationFixtures fixture) {
        var queue = new TeamResearchQueue(fixture.research, "team");
        queue.enqueue(id("research"), 1, OWNER, 0);
        return queue;
    }

    private static ResearchStationWork work(StationFixtures fixture, TeamResearchQueue queue) {
        return new ResearchStationWork(queue, new TeamOwnedResearch("team", fixture.research), Map.of(OWNER, new PlayerResearchState(OWNER)));
    }

    @Test
    void pooledMaterialsPowerAndRemainingNetworkBudgetCommitTogether() {
        try (var fixture = new StationFixtures(2, 100, 2, definition(4, 40, 4))) {
            var first = fixture.poweredStation(2);
            var second = fixture.poweredStation(2);
            Objects.requireNonNull(first.warehouse().input()).insert(A, 1, Actionable.MODULATE);
            Objects.requireNonNull(second.warehouse().input()).insert(A, 3, Actionable.MODULATE);
            fixture.refresh();
            var center = fixture.roleOf(fixture.entries.getFirst().machine()).workPermit();
            var earlier = Objects.requireNonNull(center.reserve(id("ordinary"), Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 30)));
            assertTrue(earlier.commit());
            var queue = queue(fixture);
            var result = work(fixture, queue).advance(Objects.requireNonNull(fixture.selector.capture("team", 2)),
                new ResearchStationWork.Context(1, Set.of(OWNER), () -> true));
            assertEquals(ResearchStationWork.Status.ADVANCED, result.status());
            assertEquals(2, result.stations().size());
            assertEquals(2, queue.activeProgress().snapshot().remainingMaterials().getLong(A));
            assertEquals(2, first.warehouse().input().amount(A) + second.warehouse().input().amount(A));
            assertEquals(16, first.energy().storedEnergy() + second.energy().storedEnergy());
            assertEquals(50, center.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void staleAuthorityRollsBackMaterialsPowerAndNetworkWithoutChangingProgress() {
        try (var fixture = new StationFixtures(1, 100, 2, definition(4, 40, 4))) {
            var station = fixture.poweredStation(2);
            Objects.requireNonNull(station.warehouse().input()).insert(A, 4, Actionable.MODULATE);
            fixture.refresh();
            var queue = queue(fixture);
            var reads = new AtomicInteger();
            var result = work(fixture, queue).advance(Objects.requireNonNull(fixture.selector.capture("team", 2)),
                new ResearchStationWork.Context(1, Set.of(OWNER), () -> reads.getAndIncrement() == 0));
            assertEquals(ResearchStationWork.Status.WAITING, result.status());
            assertEquals(4, station.warehouse().input().amount(A));
            assertEquals(10, station.energy().storedEnergy());
            assertEquals(0, queue.activeProgress().snapshot().elapsedTicks());
            assertEquals(100, fixture.roleOf(station.machine()).workPermit().availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void stationsDoNotTakeConcurrencyFromOrdinaryMachines() {
        try (var fixture = new StationFixtures(1, 100, 2, definition(4, 40, 0))) {
            fixture.station(2, 0);
            var ordinary = fixture.role(NetworkDeviceRole.DEVICE, 1, 0, OWNER, fixture.world);
            fixture.refresh();
            var permit = fixture.roleOf(ordinary.machine()).workPermit();
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, permit.requestLease());
            assertNull(fixture.selector.capture("team", 2));
            assertEquals(NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED, permit.requestLease());
        }
    }

    @Test
    void partialMaterialPaymentChargesOneStationBeforeAWholeWorkTickIsUnlocked() {
        try (var fixture = new StationFixtures(2, 0, 2, definition(1, 0, 100))) {
            var first = fixture.poweredStation(3);
            var second = fixture.poweredStation(3);
            Objects.requireNonNull(first.warehouse().input()).insert(A, 1, Actionable.MODULATE);
            fixture.refresh();
            var queue = queue(fixture);
            var result = work(fixture, queue).advance(Objects.requireNonNull(fixture.selector.capture("team", 2)),
                new ResearchStationWork.Context(1, Set.of(OWNER), () -> true));
            assertEquals(ResearchStationWork.Status.ADVANCED, result.status());
            assertEquals(1, result.stations().size());
            assertEquals(0, queue.activeProgress().snapshot().elapsedTicks());
            assertEquals(99, queue.activeProgress().snapshot().remainingMaterials().getLong(A));
            assertEquals(17, first.energy().storedEnergy() + second.energy().storedEnergy());
        }
    }

    @Test
    void stationsWithoutActualContributionReleaseConcurrencyAndPayNoPower() {
        try (var fixture = new StationFixtures(2, 10, 2, definition(1, 20, 0))) {
            var first = fixture.poweredStation(3);
            var second = fixture.poweredStation(3);
            fixture.refresh();
            var queue = queue(fixture);
            var result = work(fixture, queue).advance(Objects.requireNonNull(fixture.selector.capture("team", 2)),
                new ResearchStationWork.Context(1, Set.of(OWNER), () -> true));
            assertEquals(1, result.stations().size());
            assertEquals(17, first.energy().storedEnergy() + second.energy().storedEnergy());
        }
    }
}
