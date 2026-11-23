package com.circulation.prototype_eng.research.queue;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.api.research.queue.ResearchQueueNbt;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("DataFlowIssue") // Test setup establishes successful admission or capture before reading the result.
class TeamResearchQueueTest {
    private static final ResourceLocation ID = new ResourceLocation("test", "research");
    private static final UUID PLAYER = new UUID(0, 1), STATION = new UUID(0, 2), SECOND = new UUID(0, 3);
    private static final NetworkResourceType COMPUTE = ComputingResourceFixtures.COMPUTE, QUBIT = ComputingResourceFixtures.QUBIT;
    private static final TestResources.KeyType TYPE = new TestResources.KeyType("research_progress");
    private static final TestResources.Key A = new TestResources.Key(TYPE, "a"), B = new TestResources.Key(TYPE, "b"), C = new TestResources.Key(TYPE, "c");

    private static ResearchRegistry registry(ResearchLevelStage.Builder stage) {
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(ID).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage.build()).build());
        registry.freeze();
        return registry;
    }

    private static Reference2DoubleMap<NetworkResourceType> resources(double compute, double qubit) {
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        if (compute > 0) result.put(COMPUTE, compute);
        if (qubit > 0) result.put(QUBIT, qubit);
        return result;
    }

    private static void commit(TeamResearchQueue queue, TeamResearchQueue.SegmentPlan plan) {
        var grant = queue.prepareGrantSegment(plan);
        grant.commit();
        grant.complete();
    }

    @Test
    void overallProgressWaitsForEveryNetworkResourceWhileFasterResourcesAccumulateIndependently() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(6).researchTier(1)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 100).constant(QUBIT, 60).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(100, 10)));
        for (int tick = 1; tick <= 30; tick++) {
            var plan = queue.planSegment(head.key(), stations, resources(100, 2), ImmutableResourceAmounts.empty(), tick);
            if (tick > 6) assertEquals(0, plan.grantedResources().getDouble(COMPUTE));
            assertEquals(tick == 30, plan.completesResearch());
            commit(queue, plan);
            if (tick == 6) {
                assertEquals(1, queue.activeProgress().snapshot().elapsedTicks());
                assertEquals(12, queue.activeProgress().snapshot().resources().getDouble(QUBIT));
            }
            if (tick < 30) assertEquals(tick / 5, queue.activeProgress().snapshot().elapsedTicks());
        }
        assertEquals(0, queue.size());
    }

    @Test
    void missingNetworkResourceStopsTimeWhileSuppliedResourcesRemainPaidAcrossReload() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(4).researchTier(1).material(A, 4)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 10).perTick(QUBIT, 2).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(10, 2)));
        for (int tick = 1; tick <= 4; tick++) {
            var plan = queue.planSegment(head.key(), stations, resources(10, 0), ImmutableResourceAmounts.single(A, 1), tick);
            assertFalse(plan.completesResearch());
            commit(queue, plan);
            assertEquals(0, queue.activeProgress().snapshot().elapsedTicks());
        }
        assertNull(queue.planSegment(head.key(), stations, resources(10, 0), ImmutableResourceAmounts.empty(), 5));
        var codec = new ResearchQueueNbt(registry, new ResourceKeyNbt(_ -> TYPE));
        var restored = TeamResearchQueue.restore(registry, codec.decode(codec.encode(queue.snapshot())));
        assertEquals(0, restored.activeProgress().snapshot().remainingMaterials().getLong(A));
        assertEquals(40, restored.activeProgress().snapshot().resources().getDouble(COMPUTE));
        for (int tick = 6; tick <= 9; tick++) {
            var plan = restored.planSegment(head.key(), stations, resources(0, 2), ImmutableResourceAmounts.empty(), tick);
            assertTrue(plan.payments().isEmpty());
            assertEquals(0, plan.grantedResources().getDouble(COMPUTE));
            assertEquals(tick == 9, plan.completesResearch());
            commit(restored, plan);
            if (tick < 9) assertEquals(tick - 5, restored.activeProgress().snapshot().elapsedTicks());
        }
        assertEquals(0, restored.size());
    }

    @Test
    void fractionalNetworkSupplyUnlocksWholeTicksAndFinishesWithoutAnExtraPayment() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(5).researchTier(1)
                                                  .resourceFormula(ResearchResourceFormula.builder().constant(COMPUTE, 0.5).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(0.1, 0)));
        double consumed = 0;
        for (int tick = 1; tick <= 5; tick++) {
            var plan = queue.planSegment(head.key(), stations, resources(0.1, 0), ImmutableResourceAmounts.empty(), tick);
            assertNotNull(plan);
            consumed += plan.grantedResources().getDouble(COMPUTE);
            assertEquals(tick == 5, plan.completesResearch());
            commit(queue, plan);
            if (tick < 5) assertEquals(tick, queue.activeProgress().snapshot().elapsedTicks());
        }
        assertEquals(0.5, consumed);
        assertEquals(0, queue.size());
    }

    @Test
    void roundedNetworkRatioCannotUnlockTimeBeyondTheAmountActuallyAccumulated() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(Long.MAX_VALUE).researchTier(1)
                                                  .resourceFormula(ResearchResourceFormula.builder().constant(COMPUTE, 1).build()));
        var progress = new ResearchProgress(registry, new ResearchProgress.Target(ID, 1));
        long supported = Long.MAX_VALUE - 1024;
        progress.restore(new ResearchProgress.Snapshot(progress.target(), supported - 1, ImmutableResourceAmounts.empty(), resources(Math.nextDown(1D), 0)));
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(0, 0)));
        var plan = progress.plan(stations, resources(0, 0), ImmutableResourceAmounts.empty());
        assertNotNull(plan);
        assertEquals(supported, plan.after().elapsedTicks());
        assertFalse(plan.complete());
        assertTrue(plan.grants().isEmpty());
        progress.restore(plan.after());
        assertNull(progress.plan(stations, resources(0, 0), ImmutableResourceAmounts.empty()));
    }

    @Test
    void paysIndividualMaterialKindsWithEqualWeightsAndKeepsSlowResourcesIndependent() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(6).researchTier(1)
                                                  .material(A, 2).material(B, 10).material(C, 100)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 10).perTick(QUBIT, 10).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(10, 10)));
        var first = queue.planSegment(head.key(), stations, resources(10, 2), ImmutableResourceAmounts.single(A, 1), 1);
        assertEquals(1, first.payments().getLong(A));
        assertEquals(0, first.payments().getLong(B));
        commit(queue, first);
        assertEquals(0, queue.activeProgress().snapshot().elapsedTicks());
        assertEquals(10, queue.activeProgress().snapshot().resources().getDouble(COMPUTE));
        var second = queue.planSegment(head.key(), stations, resources(10, 2), ImmutableResourceAmounts.single(B, 5), 2);
        assertEquals(5, second.payments().getLong(B));
        commit(queue, second);
        assertEquals(0, queue.activeProgress().snapshot().elapsedTicks());
        assertEquals(1, queue.activeProgress().snapshot().remainingMaterials().getLong(A));
        assertEquals(5, queue.activeProgress().snapshot().remainingMaterials().getLong(B));
        assertEquals(100, queue.activeProgress().snapshot().remainingMaterials().getLong(C));
        var catchUp = queue.planSegment(head.key(), stations, resources(10, 2), ImmutableResourceAmounts.empty(), 3);
        assertTrue(catchUp.payments().isEmpty());
        assertEquals(0, catchUp.grantedResources().getDouble(COMPUTE));
        assertEquals(2, catchUp.grantedResources().getDouble(QUBIT));
    }

    @Test
    void combinesStationLimitsWithoutMultiplyingTotalDemandAndDoesNotRepeatATick() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(4).researchTier(1)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 10).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(100, 0)), new ResearchProgress.StationRate(SECOND, resources(100, 0)));
        var first = queue.planSegment(head.key(), stations, resources(100, 0), ImmutableResourceAmounts.empty(), 1);
        assertEquals(20, first.grantedResources().getDouble(COMPUTE));
        commit(queue, first);
        assertEquals(2, queue.activeProgress().snapshot().elapsedTicks());
        assertNull(queue.planSegment(head.key(), stations, resources(100, 0), ImmutableResourceAmounts.empty(), 1));
        var second = queue.planSegment(head.key(), stations, resources(100, 0), ImmutableResourceAmounts.empty(), 2);
        assertTrue(second.completesResearch());
    }

    @Test
    void fullySuppliedMaterialResearchAdvancesOnEveryTickThroughFractionalBoundaries() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(22).researchTier(1).material(A, 22)
                                                  .resourceFormula(ResearchResourceFormula.builder().build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(0, 0)));
        long paid = 0;
        for (int tick = 1; tick <= 22; tick++) {
            var plan = queue.planSegment(head.key(), stations, resources(0, 0), ImmutableResourceAmounts.single(A, 22), tick);
            assertNotNull(plan, "Fully supplied research stalled at tick " + tick);
            paid += plan.payments().getLong(A);
            assertEquals(tick == 22, plan.completesResearch());
            commit(queue, plan);
            if (tick < 22) assertEquals(tick, queue.activeProgress().snapshot().elapsedTicks());
        }
        assertEquals(22, paid);
        assertEquals(0, queue.size());
    }

    @Test
    void weightedPaidMaterialsUnlockTheirExactTimeWithoutConsumingAgain() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(22).researchTier(1).material(A, 22).material(B, 44)
                                                  .resourceFormula(ResearchResourceFormula.builder().build()));
        var progress = new ResearchProgress(registry, new ResearchProgress.Target(ID, 1));
        var remaining = new AEKey2LongMap.OpenHashMap();
        remaining.put(A, 7);
        remaining.put(B, 14);
        progress.restore(new ResearchProgress.Snapshot(progress.target(), 14, remaining, resources(0, 0)));
        var plan = progress.plan(List.of(new ResearchProgress.StationRate(STATION, resources(0, 0))), resources(0, 0), ImmutableResourceAmounts.empty());
        assertNotNull(plan);
        assertEquals(15, plan.after().elapsedTicks());
        assertTrue(plan.payments().isEmpty());
        assertEquals(7, plan.after().remainingMaterials().getLong(A));
        assertEquals(14, plan.after().remainingMaterials().getLong(B));
        progress.restore(plan.after());
        assertNull(progress.plan(List.of(new ResearchProgress.StationRate(STATION, resources(0, 0))), resources(0, 0), ImmutableResourceAmounts.empty()));
    }

    @Test
    void roundingAlmostPaidMaterialsToOneDoesNotUnlockUnpaidFinalTick() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(Long.MAX_VALUE).researchTier(1).material(A, Long.MAX_VALUE)
                                                  .resourceFormula(ResearchResourceFormula.builder().build()));
        var progress = new ResearchProgress(registry, new ResearchProgress.Target(ID, 1));
        progress.restore(new ResearchProgress.Snapshot(progress.target(), Long.MAX_VALUE - 1, ImmutableResourceAmounts.single(A, 1), resources(0, 0)));
        assertNull(progress.plan(List.of(new ResearchProgress.StationRate(STATION, resources(0, 0))), resources(0, 0), ImmutableResourceAmounts.empty()));
    }

    @Test
    void removedTargetSurvivesNbtAndReentryWithoutRepayingItsMaterials() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(4).researchTier(1).material(A, 4)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 10).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        commit(queue, queue.planSegment(head.key(), List.of(new ResearchProgress.StationRate(STATION, resources(10, 0))),
            resources(10, 0), ImmutableResourceAmounts.single(A, 1), 1));
        queue.remove(head.key(), 2);
        var codec = new ResearchQueueNbt(registry, new ResourceKeyNbt(_ -> TYPE));
        var restored = TeamResearchQueue.restore(registry, codec.decode(codec.encode(queue.snapshot())));
        var resumed = restored.enqueue(ID, 1, PLAYER, 3);
        assertNotEquals(head.key().queueEntryId(), resumed.key().queueEntryId());
        assertEquals(1, restored.activeProgress().snapshot().elapsedTicks());
        assertEquals(3, restored.activeProgress().snapshot().remainingMaterials().getLong(A));
        var plan = restored.planSegment(resumed.key(), List.of(new ResearchProgress.StationRate(STATION, resources(10, 0))),
            resources(10, 0), ImmutableResourceAmounts.single(A, 1), 4);
        var mutation = restored.prepareGrantSegment(plan);
        mutation.commit();
        mutation.rollback();
        assertEquals(1, restored.activeProgress().snapshot().elapsedTicks());
        assertEquals(3, restored.activeProgress().snapshot().remainingMaterials().getLong(A));
    }
}
