package com.circulation.prototype_eng.research.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void independentlyAccumulatesScarceResourcesAfterTimeAndOtherResourcesFinish() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(6).researchTier(1)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 100).constant(QUBIT, 60).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(100, 10)));
        for (int tick = 1; tick <= 30; tick++) {
            var plan = queue.planSegment(head.key(), stations, resources(100, 2), ImmutableResourceAmounts.empty(), tick).orElseThrow();
            if (tick > 6) assertEquals(0, plan.grantedResources().getDouble(COMPUTE));
            assertEquals(tick == 30, plan.completesResearch());
            commit(queue, plan);
            if (tick == 6) {
                assertEquals(6, queue.activeProgress().snapshot().elapsedTicks());
                assertEquals(12, queue.activeProgress().snapshot().resources().getDouble(QUBIT));
            }
        }
        assertEquals(0, queue.size());
    }

    @Test
    void paysIndividualMaterialKindsWithEqualWeightsAndKeepsSlowResourcesIndependent() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(6).researchTier(1)
                                                  .material(A, 2).material(B, 10).material(C, 100)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 10).perTick(QUBIT, 10).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        var stations = List.of(new ResearchProgress.StationRate(STATION, resources(10, 10)));
        var first = queue.planSegment(head.key(), stations, resources(10, 2), ImmutableResourceAmounts.single(A, 1), 1).orElseThrow();
        assertEquals(1, first.payments().getLong(A));
        assertEquals(0, first.payments().getLong(B));
        commit(queue, first);
        assertEquals(10, queue.activeProgress().snapshot().resources().getDouble(COMPUTE));
        var second = queue.planSegment(head.key(), stations, resources(10, 2), ImmutableResourceAmounts.single(B, 5), 2).orElseThrow();
        assertEquals(5, second.payments().getLong(B));
        commit(queue, second);
        assertEquals(1, queue.activeProgress().snapshot().remainingMaterials().getLong(A));
        assertEquals(5, queue.activeProgress().snapshot().remainingMaterials().getLong(B));
        assertEquals(100, queue.activeProgress().snapshot().remainingMaterials().getLong(C));
        var catchUp = queue.planSegment(head.key(), stations, resources(10, 2), ImmutableResourceAmounts.empty(), 3).orElseThrow();
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
        var first = queue.planSegment(head.key(), stations, resources(100, 0), ImmutableResourceAmounts.empty(), 1).orElseThrow();
        assertEquals(20, first.grantedResources().getDouble(COMPUTE));
        commit(queue, first);
        assertEquals(2, queue.activeProgress().snapshot().elapsedTicks());
        assertTrue(queue.planSegment(head.key(), stations, resources(100, 0), ImmutableResourceAmounts.empty(), 1).isEmpty());
        var second = queue.planSegment(head.key(), stations, resources(100, 0), ImmutableResourceAmounts.empty(), 2).orElseThrow();
        assertTrue(second.completesResearch());
    }

    @Test
    void removedTargetSurvivesNbtAndReentryWithoutRepayingItsMaterials() {
        var registry = registry(ResearchLevelStage.builder(1, 1).durationTicks(4).researchTier(1).material(A, 4)
                                                  .resourceFormula(ResearchResourceFormula.builder().perTick(COMPUTE, 10).build()));
        var queue = new TeamResearchQueue(registry, "team");
        var head = queue.enqueue(ID, 1, PLAYER, 0);
        commit(queue, queue.planSegment(head.key(), List.of(new ResearchProgress.StationRate(STATION, resources(10, 0))),
            resources(10, 0), ImmutableResourceAmounts.single(A, 1), 1).orElseThrow());
        queue.remove(head.key(), 2);
        var codec = new ResearchQueueNbt(registry, new ResourceKeyNbt(_ -> TYPE));
        var restored = TeamResearchQueue.restore(registry, codec.decode(codec.encode(queue.snapshot())));
        var resumed = restored.enqueue(ID, 1, PLAYER, 3);
        assertNotEquals(head.key().queueEntryId(), resumed.key().queueEntryId());
        assertEquals(1, restored.activeProgress().snapshot().elapsedTicks());
        assertEquals(3, restored.activeProgress().snapshot().remainingMaterials().getLong(A));
        var plan = restored.planSegment(resumed.key(), List.of(new ResearchProgress.StationRate(STATION, resources(10, 0))),
            resources(10, 0), ImmutableResourceAmounts.single(A, 1), 4).orElseThrow();
        var mutation = restored.prepareGrantSegment(plan);
        mutation.commit();
        mutation.rollback();
        assertEquals(1, restored.activeProgress().snapshot().elapsedTicks());
        assertEquals(3, restored.activeProgress().snapshot().remainingMaterials().getLong(A));
    }
}
