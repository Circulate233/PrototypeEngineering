package com.circulation.prototype_eng.research.queue;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.queue.ResearchQueueNbt;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamResearchQueueTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final List<UUID> STATIONS = List.of(new UUID(0, 1), new UUID(0, 2));

    private static Fixture fixture(long demand, long cap, int tier) {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        for (int index = 0; index < 6; index++) {
            var formula = ResearchResourceFormula.builder();
            if (tier != 0) {
                formula.constant(NetworkResources.COMPUTE, demand, cap);
            }
            registry.register(ResearchDefinition.builder(id(index)).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                                .stage(ResearchLevelStage.builder(1, 1).researchTier(tier).resourceFormula(formula.build()).build()).build());
        }
        registry.freeze(resources);
        return new Fixture(registry, resources, new TeamResearchQueue(registry, resources, "team"));
    }

    private static TeamResearchView view(Fixture fixture, int retained) {
        var targets = new Object2IntOpenHashMap<ResourceLocation>();
        targets.put(id(retained), 1);
        var blacklist = new ObjectOpenHashSet<ResourceLocation>();
        for (int index = 0; index < 6; index++) {
            if (index != retained) {
                blacklist.add(id(index));
            }
        }
        return new TeamResearchView(fixture.registry(), "team", Set.of(PLAYER), new Object2IntOpenHashMap<>(), targets, blacklist);
    }

    private static Object2LongOpenHashMap<NetworkResourceType> amounts(long value) {
        var amounts = new Object2LongOpenHashMap<NetworkResourceType>();
        amounts.put(NetworkResources.COMPUTE, value);
        return amounts;
    }

    private static ResourceLocation id(int index) {
        return new ResourceLocation("prototype_eng_test", "research_" + index);
    }

    @Test
    void capacityDuplicatesAndIdsAreValidatedBeforeMutation() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        for (int index = 0; index < 5; index++) {
            queue.enqueue(id(index), 1, PLAYER, 1);
        }
        var before = queue.snapshot();
        assertEquals(5, queue.size());
        assertTrue(queue.containsTarget(id(0), 1));
        assertThrows(IllegalStateException.class, () -> queue.enqueue(id(5), 1, PLAYER, 1));
        assertThrows(IllegalStateException.class, () -> queue.enqueue(id(0), 1, PLAYER, 1));
        assertEquals(before, queue.snapshot());
        var first = queue.activeHead().orElseThrow();
        assertEquals(first, queue.removeHead(first.key()));
        var next = queue.enqueue(id(5), 1, PLAYER, 2);
        assertEquals(6, next.key().queueEntryId());
    }

    @Test
    void sumsStationLimitsAndSeparatelyCapsEachNumericResource() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var available = amounts(100);
        available.put(NetworkResources.THREAD, 100);
        var plan = queue.planSegment(head.key(), STATIONS, available, 2).orElseThrow();
        assertEquals(4, plan.grantedResources().getLong(NetworkResources.COMPUTE));
        assertFalse(plan.grantedResources().containsKey(NetworkResources.THREAD));
        assertFalse(plan.completesResearch());
        var mutation = queue.prepareGrantSegment(plan);
        mutation.commit();
        assertEquals(4, queue.resourceProgressSnapshot().getLong(NetworkResources.COMPUTE));
        assertThrows(IllegalStateException.class, queue::snapshot);
        assertThrows(IllegalStateException.class, () -> queue.enqueue(id(1), 1, PLAYER, 2));
        mutation.complete();
        assertFalse(queue.busy());
        assertTrue(queue.planSegment(head.key(), STATIONS, available, 2).isEmpty());
        assertThrows(IllegalStateException.class, () -> queue.planSegment(head.key(), STATIONS, available, 1));
    }

    @Test
    void grantRollbackRestoresWorkButPermanentlyInvalidatesEarlierPlans() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var plan = queue.planSegment(head.key(), STATIONS, amounts(100), 2).orElseThrow();
        var mutation = queue.prepareGrantSegment(plan);
        mutation.commit();
        var result = mutation.result();
        assertThrows(IllegalStateException.class, mutation::commit);
        assertSame(result, mutation.result());
        assertEquals(ReversibleTransaction.Resolution.REVERSIBLE, mutation.resolution());
        mutation.rollback();
        long epoch = queue.epoch();
        mutation.rollback();
        assertEquals(epoch, queue.epoch());
        assertTrue(queue.resourceProgressSnapshot().isEmpty());
        assertEquals(ReversibleTransaction.Resolution.ROLLED_BACK, mutation.resolution());
        assertThrows(IllegalStateException.class, () -> queue.prepareGrantSegment(plan));
        assertTrue(queue.planSegment(head.key(), STATIONS, amounts(100), 2).isPresent());
    }

    @Test
    void completedGrantRemovesOnlyItsHeadAndCannotUndoLaterWrites() {
        var fixture = fixture(3, 2, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var tail = queue.enqueue(id(1), 1, PLAYER, 1);
        var plan = queue.planSegment(head.key(), STATIONS, amounts(100), 2).orElseThrow();
        var mutation = queue.prepareGrantSegment(plan);
        mutation.commit();
        assertTrue(mutation.result().completedResearch());
        assertEquals(3, mutation.result().grantedResources().getLong(NetworkResources.COMPUTE));
        mutation.complete();
        mutation.complete();
        assertEquals(tail, queue.activeHead().orElseThrow());
        queue.enqueue(id(2), 1, PLAYER, 2);
        assertThrows(IllegalStateException.class, mutation::rollback);
        assertEquals(2, queue.size());
        assertEquals(ReversibleTransaction.Resolution.COMPLETED, mutation.resolution());
        assertThrows(IllegalStateException.class, mutation::commit);
    }

    @Test
    void pruningLaterTargetsPreservesHeadWorkAndPruningHeadClearsItReversibly() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var tail = queue.enqueue(id(1), 1, PLAYER, 1);
        var grant = queue.prepareGrantSegment(queue.planSegment(head.key(), STATIONS, amounts(100), 2).orElseThrow());
        grant.commit();
        grant.complete();
        var prune = queue.preparePrune(view(fixture, 0));
        prune.commit();
        assertEquals(List.of(tail), prune.removed());
        assertEquals(4, queue.resourceProgressSnapshot().getLong(NetworkResources.COMPUTE));
        prune.rollback();
        assertEquals(List.of(head, tail), queue.queueSnapshot());
        var headPrune = queue.preparePrune(view(fixture, 1));
        headPrune.commit();
        assertTrue(queue.resourceProgressSnapshot().isEmpty());
        headPrune.complete();
        assertEquals(tail, queue.activeHead().orElseThrow());
    }

    @Test
    void tierZeroStationPlanningAndUnstartedHeadRemovalDoNotDiscardWork() {
        var manual = fixture(0, 2, 0);
        var head = manual.queue().enqueue(id(0), 1, PLAYER, 1);
        assertThrows(IllegalStateException.class, () -> manual.queue().planSegment(head.key(), STATIONS, amounts(100), 2));
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        var active = queue.enqueue(id(0), 1, PLAYER, 1);
        var tail = queue.enqueue(id(1), 1, PLAYER, 1);
        var grant = queue.prepareGrantSegment(queue.planSegment(active.key(), STATIONS, amounts(100), 2).orElseThrow());
        grant.commit();
        grant.complete();
        assertThrows(IllegalStateException.class, () -> queue.removeUnstarted(active.key()));
        assertEquals(tail, queue.removeUnstarted(tail.key()));
        assertEquals(4, queue.resourceProgressSnapshot().getLong(NetworkResources.COMPUTE));
    }

    @Test
    void saveRoundTripPreservesProgressTickDedupIdsAndRejectsRestoredPlanAuthority() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var oldPlan = queue.planSegment(head.key(), STATIONS, amounts(100), 2).orElseThrow();
        var mutation = queue.prepareGrantSegment(oldPlan);
        mutation.commit();
        mutation.complete();
        var codec = new ResearchQueueNbt(fixture.registry(), fixture.resources());
        var saved = codec.decode(codec.encode(queue.snapshot()));
        var restored = TeamResearchQueue.restore(fixture.registry(), fixture.resources(), saved);
        assertEquals(queue.snapshot(), restored.snapshot());
        assertThrows(IllegalStateException.class, () -> restored.prepareGrantSegment(oldPlan));
        assertTrue(restored.planSegment(head.key(), STATIONS, amounts(100), 2).isEmpty());
        restored.removeHead(head.key());
        var again = TeamResearchQueue.restore(fixture.registry(), fixture.resources(), codec.decode(codec.encode(restored.snapshot())));
        assertEquals(2, again.enqueue(id(1), 1, PLAYER, 3).key().queueEntryId());
    }

    @Test
    void restoreRejectsWrongHeadDuplicateEntryIdsUnknownOrExcessProgressAndWrongNbtTypes() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        queue.enqueue(id(0), 1, PLAYER, 1);
        queue.enqueue(id(1), 1, PLAYER, 1);
        var codec = new ResearchQueueNbt(fixture.registry(), fixture.resources());
        var original = codec.encode(queue.snapshot());
        var wrongHead = original.copy();
        wrongHead.getCompoundTag("Head").setLong("EntryId", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongHead));
        var duplicate = original.copy();
        duplicate.getTagList("Queue", 10).getCompoundTagAt(1).setLong("EntryId", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
        var wrongType = original.copy();
        wrongType.setInteger("Epoch", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongType));
        var excess = original.copy();
        var progress = new NBTTagList();
        var amount = new NBTTagCompound();
        amount.setString("Resource", NetworkResources.COMPUTE.id().toString());
        amount.setLong("Amount", 11);
        progress.appendTag(amount);
        excess.setTag("Progress", progress);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(excess));
        amount.setString("Resource", "prototype_eng_test:unknown");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(excess));
    }

    @Test
    void plansRejectDuplicateStationsAndOverflowWithoutPublishingPartialState() {
        var fixture = fixture(Long.MAX_VALUE, Long.MAX_VALUE, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var before = queue.snapshot();
        assertThrows(IllegalArgumentException.class, () -> queue.planSegment(head.key(), List.of(STATIONS.getFirst(), STATIONS.getFirst()), amounts(1), 2));
        assertThrows(ArithmeticException.class, () -> queue.planSegment(head.key(), STATIONS, amounts(1), 2));
        assertEquals(before, queue.snapshot());
    }

    @Test
    void planKeepsCompleteMaterialDefinitionsAndExactAmounts() {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        var material = RecipeRequirements.RESOURCES.requirement(new ResourceLocation("test", "material"), new ResourceRequirement(List.of(), List.of()));
        registry.register(ResearchDefinition.builder(id(0)).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).materialRequirement(material)
                                                                     .resourceFormula(ResearchResourceFormula.builder().constant(NetworkResources.COMPUTE, 1, 1).build()).build()).build());
        registry.freeze(resources);
        var queue = new TeamResearchQueue(registry, resources, "team");
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var plan = queue.planSegment(head.key(), STATIONS, amounts(1), 2).orElseThrow();
        assertSame(material, plan.materialRequirements().getFirst());
    }

    @Test
    void completingOneNumericDimensionDoesNotCompleteTheOtherDimension() {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(id(0)).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder()
                                                                                                                                           .constant(NetworkResources.COMPUTE, 5, 4).constant(NetworkResources.QUBIT, 9, 2).build()).build()).build());
        registry.freeze(resources);
        var queue = new TeamResearchQueue(registry, resources, "team");
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var available = amounts(100);
        available.put(NetworkResources.QUBIT, 3);
        var first = queue.planSegment(head.key(), STATIONS, available, 2).orElseThrow();
        assertEquals(5, first.grantedResources().getLong(NetworkResources.COMPUTE));
        assertEquals(3, first.grantedResources().getLong(NetworkResources.QUBIT));
        assertFalse(first.completesResearch());
        var mutation = queue.prepareGrantSegment(first);
        mutation.commit();
        mutation.complete();
        available.put(NetworkResources.QUBIT, 100);
        var second = queue.planSegment(head.key(), STATIONS, available, 3).orElseThrow();
        assertFalse(second.grantedResources().containsKey(NetworkResources.COMPUTE));
        assertEquals(4, second.grantedResources().getLong(NetworkResources.QUBIT));
        assertFalse(second.completesResearch());
        var next = queue.prepareGrantSegment(second);
        next.commit();
        next.complete();
        assertEquals(2, queue.planSegment(head.key(), STATIONS, available, 4).orElseThrow().grantedResources().getLong(NetworkResources.QUBIT));
    }

    @Test
    void preparedRollbackInvalidatesObservedPlansAndExhaustionLeavesStateUntouched() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var plan = queue.planSegment(head.key(), STATIONS, amounts(100), 2).orElseThrow();
        var mutation = queue.prepareGrantSegment(plan);
        mutation.rollback();
        assertThrows(IllegalStateException.class, () -> queue.prepareGrantSegment(plan));
        var before = queue.snapshot();
        var exhausted = new TeamResearchQueue.Snapshot(before.teamId(), before.entries(), before.activeHead(), before.resourceProgress(),
            Long.MAX_VALUE, Long.MAX_VALUE - 1, before.updatedAtWorldTime(), before.lastGrantedEntryId(), before.lastGrantedWorldTime());
        var restored = TeamResearchQueue.restore(fixture.registry(), fixture.resources(), exhausted);
        assertThrows(ArithmeticException.class, () -> restored.enqueue(id(1), 1, PLAYER, 2));
        assertEquals(exhausted, restored.snapshot());
        var lastPlan = restored.planSegment(head.key(), STATIONS, amounts(100), 2).orElseThrow();
        assertThrows(IllegalStateException.class, () -> restored.prepareGrantSegment(lastPlan));
        assertFalse(restored.busy());
        assertEquals(exhausted, restored.snapshot());
    }

    @Test
    void aFalseStartConditionAfterEnqueueDoesNotCancelStructurallyEligibleWork() {
        var enabled = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(id(0)).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .startCondition(_ -> {
                                                calls.incrementAndGet();
                                                return enabled.get();
                                            })
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder()
                                                                                                                                           .constant(NetworkResources.COMPUTE, 10, 2).build()).build()).build());
        registry.freeze(resources);
        var eligibility = new ResearchEligibility(registry);
        var player = new PlayerResearchState(PLAYER);
        var view = eligibility.calculate("team", Set.of(PLAYER), Map.of(PLAYER, player), new Object2IntOpenHashMap<>(), 1, Set.of(), amounts(100));
        assertTrue(eligibility.canStart(view, id(0), 1, PLAYER, amounts(100), 1));
        var queue = new TeamResearchQueue(registry, resources, "team");
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        enabled.set(false);
        var laterView = eligibility.calculate("team", Set.of(PLAYER), Map.of(PLAYER, player), new Object2IntOpenHashMap<>(), 1, Set.of(), amounts(100));
        assertFalse(laterView.isStartable(id(0)));
        int beforePrune = calls.get();
        var prune = queue.preparePrune(laterView);
        assertTrue(prune.removed().isEmpty());
        prune.commit();
        prune.complete();
        assertEquals(head, queue.activeHead().orElseThrow());
        assertEquals(beforePrune, calls.get());
    }

    @Test
    void queueAndMutationReadsWritesAndTerminalRetriesRequireTheActualOwnerThread() {
        var fixture = fixture(10, 2, 1);
        var queue = fixture.queue();
        var head = queue.enqueue(id(0), 1, PLAYER, 1);
        var plan = queue.planSegment(head.key(), STATIONS, amounts(100), 2).orElseThrow();
        var grant = queue.prepareGrantSegment(plan);
        CompletableFuture.runAsync(() -> {
            assertThrows(IllegalStateException.class, queue::epoch);
            assertThrows(IllegalStateException.class, queue::busy);
            assertThrows(IllegalStateException.class, queue::snapshot);
            assertThrows(IllegalStateException.class, queue::resourceProgressSnapshot);
            assertThrows(IllegalStateException.class, () -> queue.enqueue(id(1), 1, PLAYER, 2));
            assertThrows(IllegalStateException.class, grant::commit);
            assertThrows(IllegalStateException.class, grant::complete);
            assertThrows(IllegalStateException.class, grant::rollback);
            assertThrows(IllegalStateException.class, grant::resolution);
            assertThrows(IllegalStateException.class, grant::result);
        }).join();
        assertTrue(queue.busy());
        grant.commit();
        grant.complete();
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, grant::complete)).join();
        var prune = queue.preparePrune(view(fixture, 0));
        CompletableFuture.runAsync(() -> {
            assertThrows(IllegalStateException.class, prune::commit);
            assertThrows(IllegalStateException.class, prune::complete);
            assertThrows(IllegalStateException.class, prune::rollback);
            assertThrows(IllegalStateException.class, prune::resolution);
            assertThrows(IllegalStateException.class, prune::removed);
        }).join();
        prune.rollback();
        var restored = TeamResearchQueue.restore(fixture.registry(), fixture.resources(), queue.snapshot());
        assertEquals(queue.snapshot(), restored.snapshot());
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, restored::queueSnapshot)).join();
    }

    private record Fixture(ResearchRegistry registry, NetworkResourceRegistry resources, TeamResearchQueue queue) {
    }
}
