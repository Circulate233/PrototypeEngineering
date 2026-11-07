package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.api.research.reward.RewardFragment;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.research.reward.RewardItemDeliveryAdapter;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.research.ResearchWorldNbt;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ResearchWorldRewardTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final ResourceLocation RESEARCH = id("research");
    private static final ResourceLocation WORK = id("work");
    private static final ResourceLocation GROUP = id("group");
    private static final ResourceLocation OTHER_GROUP = id("other_group");
    private static final TestResources.KeyType TYPE = new TestResources.KeyType("world_reward");
    private static final TestResources.Key ITEM = new TestResources.Key(TYPE, "item");

    @Test void orderedEffectsUseCompleteWorldCheckpointsBeforeEachDeliveryAndRelease() {
        var order = new ObjectArrayList<String>();
        var fixture = fixture(event("first", (_, _) -> { order.add("first"); return RewardEventAction.Result.APPLIED; }),
            items(), event("last", (_, _) -> { order.add("last"); return RewardEventAction.Result.APPLIED; }));
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        adapter.beforePrepare = fragment -> order.add(fragment.target() instanceof RewardFragment.Inventory ? "prepare inventory" : "prepare drop");
        adapter.beforeDelivery = fragment -> order.add(fragment.target() instanceof RewardFragment.Inventory ? "inventory" : "drop");
        var baseline = world.snapshot();

        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
        assertEquals(ObjectList.of("first", "prepare inventory", "inventory", "prepare drop", "drop", "last"), order);
        assertEquals(9, saves.attempts.size());
        for (var snapshot : saves.attempts) {
            assertEquals(20, snapshot.serverTime());
            assertEquals(baseline.teams(), snapshot.teams());
            assertEquals(player(baseline, BOB), player(snapshot, BOB));
            assertEquals(player(baseline, ALICE).completedLevels(), player(snapshot, ALICE).completedLevels());
            assertEquals(snapshot, fixture.roundTrip(snapshot));
        }
        assertEquals(RewardClaim.Status.CLAIMING, reward(saves.attempts.getFirst()).status());
        assertTrue(reward(saves.attempts.getFirst()).children().stream().allMatch(child -> child.status() == RewardClaim.ChildStatus.PENDING));
        assertEquals(RewardClaim.ChildStatus.COMPLETED, reward(saves.attempts.get(1)).children().getFirst().status());
        assertEquals(RewardFragment.Status.DELIVERING, fragments(saves.attempts.get(2)).getFirst().status());
        assertEquals(RewardFragment.Status.CONFIRMED, fragments(saves.attempts.get(3)).getFirst().status());
        assertEquals(RewardFragment.Status.DELIVERING, fragments(saves.attempts.get(4)).getLast().status());
        assertEquals(RewardFragment.Status.CONFIRMED, fragments(saves.attempts.get(5)).getLast().status());
        assertEquals(RewardClaim.ChildStatus.COMPLETED, reward(saves.attempts.get(6)).children().get(1).status());
        assertEquals(RewardClaim.ChildStatus.COMPLETED, reward(saves.attempts.get(7)).children().getLast().status());
        assertEquals(RewardClaim.Status.CLAIMED, reward(saves.attempts.getLast()).status());
        assertEquals(2, adapter.deliveries);
        assertEquals(2, adapter.prepares);
        assertEquals(2, adapter.commits);
        assertEquals(2, adapter.releases);
        assertEquals(world.snapshot(), saves.durable);
        assertFalse(world.busy());
        assertFalse(world.savePending());
    }

    @Test void failedSelectionMustRewriteTheSameWorldBeforeChoiceOrNewActorOperations() {
        var events = new AtomicInteger();
        var fixture = fixture(event("event", (_, _) -> { events.incrementAndGet(); return RewardEventAction.Result.APPLIED; }));
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        saves.reject = _ -> true;

        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var pending = saves.attempts.getFirst();
        assertTrue(world.savePending());
        assertFalse(world.busy());
        assertEquals(0, events.get());
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> world.playerLogin(new UUID(0, 3), 21)));
        assertSame(pending, saves.attempts.getLast());
        assertEquals(20, world.serverTime());
        saves.reject = _ -> false;
        assertEquals(ResearchRewardClaimer.Status.CHOICE_FIXED,
            world.claimReward(ALICE, RESEARCH, 1, OTHER_GROUP, 22, adapter, () -> true).status());
        assertSame(pending, saves.attempts.getLast());
        assertEquals(2, world.snapshot().players().size());
        assertEquals(0, events.get());
        assertEquals(ResearchRewardClaimer.Status.CLAIMED,
            world.claimReward(ALICE, RESEARCH, 1, GROUP, 23, adapter, () -> true).status());
        assertEquals(1, events.get());
        assertFalse(world.savePending());
    }

    @Test void failedDeliveringCheckpointPreventsEffectsUntilExactPendingRewriteAndAbsenceProof() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        saves.reject = snapshot -> fragments(snapshot).getFirst().status() == RewardFragment.Status.DELIVERING;

        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var pending = saves.attempts.getLast();
        assertEquals(0, adapter.deliveries);
        assertEquals(1, adapter.prepares);
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        assertSame(pending, saves.attempts.getLast());
        assertEquals(0, adapter.scans);
        assertEquals(1, adapter.prepares);
        saves.reject = _ -> false;
        int retryIndex = saves.attempts.size();
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
        assertSame(pending, saves.attempts.get(retryIndex));
        assertEquals(1, adapter.plans);
        assertEquals(1, adapter.scans);
        assertEquals(3, adapter.prepares);
        assertEquals(2, adapter.deliveries);
        assertEquals(2, adapter.physical.size());
    }

    @Test void failedConfirmedCheckpointRetainsPhysicalReceiptAndProtectionUntilDurableRetry() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        saves.reject = snapshot -> fragments(snapshot).getFirst().status() == RewardFragment.Status.CONFIRMED;

        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var pending = saves.attempts.getLast();
        assertEquals(1, adapter.deliveries);
        assertEquals(1, adapter.commits);
        assertEquals(0, adapter.releases);
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        assertSame(pending, saves.attempts.getLast());
        assertEquals(0, adapter.releases);
        saves.reject = _ -> false;
        int retryIndex = saves.attempts.size();
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
        assertSame(pending, saves.attempts.get(retryIndex));
        assertEquals(2, adapter.deliveries);
        assertEquals(2, adapter.commits);
        assertEquals(2, adapter.released.size());
        assertEquals(0, adapter.scans);
        assertFalse(world.savePending());
    }

    @Test void failedFinalCheckpointRetriesClaimedStateAndOnlyRepeatsIdempotentCleanup() {
        var events = new AtomicInteger();
        var fixture = fixture(items(), event("last", (_, _) -> { events.incrementAndGet(); return RewardEventAction.Result.APPLIED; }));
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        saves.reject = snapshot -> reward(snapshot).status() == RewardClaim.Status.CLAIMED;

        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var pending = saves.attempts.getLast();
        assertEquals(RewardClaim.Status.CLAIMING, reward(saves.durable).status());
        assertEquals(RewardClaim.Status.CLAIMED, reward(pending).status());
        assertEquals(2, adapter.deliveries);
        assertEquals(2, adapter.releases);
        assertEquals(1, events.get());
        saves.reject = _ -> false;
        int retryIndex = saves.attempts.size();
        assertEquals(ResearchRewardClaimer.Status.ALREADY_CLAIMED, claim(world, adapter).status());
        assertSame(pending, saves.attempts.get(retryIndex));
        assertEquals(2, adapter.deliveries);
        assertEquals(2, adapter.commits);
        assertEquals(4, adapter.releases);
        assertEquals(2, adapter.released.size());
        assertEquals(1, events.get());
        assertEquals(0, adapter.scans);
        assertEquals(RewardClaim.Status.CLAIMED, reward(saves.durable).status());
    }

    @Test void nbtRestartOfDeliveringReceiptObservesAndCommitsWithoutReissuingCompletedEffects() {
        var events = new AtomicInteger();
        var fixture = fixture(event("first", (_, _) -> { events.incrementAndGet(); return RewardEventAction.Result.APPLIED; }), items());
        var saves = new Saves(fixture);
        var original = fixture.world(saves);
        var adapter = new Adapter(saves);
        var failure = new IllegalStateException("item flush reply lost");
        adapter.afterCommit = _ -> { throw failure; };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> claim(original, adapter)));
        assertEquals(RewardFragment.Status.DELIVERING, fragments(saves.durable).getFirst().status());
        assertEquals(1, adapter.deliveries);
        assertEquals(0, adapter.releases);
        assertFalse(original.busy());
        var restored = fixture.restore(saves, saves.durable);
        adapter.afterCommit = _ -> { };
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(restored, adapter).status());
        assertEquals(1, events.get());
        assertEquals(1, adapter.plans);
        assertEquals(1, adapter.scans);
        assertEquals(2, adapter.deliveries);
        assertEquals(3, adapter.commits);
        assertEquals(2, adapter.released.size());
        assertEquals(restored.snapshot(), fixture.roundTrip(saves.durable));
    }

    @Test void cleanupExceptionAfterDurableConfirmationRecoversWithoutReplacingMissingReleasedItems() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        var failure = new IllegalStateException("cleanup reply lost");
        adapter.afterRelease = _ -> { throw failure; };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var confirmed = fragments(saves.durable).getFirst();
        assertEquals(RewardFragment.Status.CONFIRMED, confirmed.status());
        assertTrue(adapter.released.contains(confirmed.id()));
        adapter.physical.remove(confirmed.id()); // A released stack may be consumed before restart.
        var restored = fixture.restore(saves, saves.durable);
        adapter.afterRelease = _ -> { };
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(restored, adapter).status());
        assertEquals(2, adapter.deliveries);
        assertEquals(0, adapter.scans);
        assertFalse(adapter.physical.containsKey(confirmed.id()));
        assertEquals(ResearchRewardClaimer.Status.ALREADY_CLAIMED, claim(restored, adapter).status());
        assertEquals(2, adapter.deliveries);
        assertEquals(2, adapter.released.size());
    }

    @Test void actorRewardSessionAndOtherPlayerMutationBothRejectClaimAndWorldSnapshots() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        var alice = world.player(ALICE);
        try (var session = alice.tryRewardSession(reward(world.snapshot()).key())) {
            assertNotNull(session);
            assertEquals(ResearchRewardClaimer.Status.BUSY, claim(world, adapter).status());
            assertThrows(IllegalStateException.class, world::snapshot);
            assertThrows(IllegalStateException.class, () -> world.checkpoint(20));
            assertEquals(0, saves.attempts.size());
        }
        var mutation = world.player(BOB).prepareCompletion(WORK, true, 1, IntArrayList.of(), 20);
        assertEquals(ResearchRewardClaimer.Status.BUSY, claim(world, adapter).status());
        assertThrows(IllegalStateException.class, world::snapshot);
        assertThrows(IllegalStateException.class, () -> world.checkpoint(20));
        assertEquals(0, adapter.plans);
        mutation.rollback();
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
    }

    @Test void rewardCheckpointCannotBorrowAnUnrelatedSourceWriterIntroducedByAnEvent() {
        var other = new AtomicReference<PlayerResearchState>();
        var mutation = new AtomicReference<PlayerResearchState.Completion>();
        var events = new AtomicInteger();
        var fixture = fixture(event("event", (_, _) -> {
            events.incrementAndGet();
            mutation.set(other.get().prepareCompletion(WORK, true, 1, IntArrayList.of(), 20));
            return RewardEventAction.Result.APPLIED;
        }), items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        other.set(world.player(BOB));

        var failure = assertThrows(IllegalStateException.class, () -> claim(world, adapter));
        assertTrue(failure.getMessage().contains("another transaction"));
        assertEquals(1, saves.attempts.size());
        assertEquals(RewardClaim.ChildStatus.PENDING, reward(saves.durable).children().getFirst().status());
        assertEquals(0, adapter.deliveries);
        assertFalse(world.busy());
        mutation.get().rollback();
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
        assertEquals(1, events.get());
        assertEquals(2, adapter.deliveries);
    }

    @Test void callbacksCannotReenterWorldMutationsAndWorldSaveDefersToRewardCheckpoint() {
        var owner = new AtomicReference<ResearchWorld>();
        var savesRef = new AtomicReference<Saves>();
        var adapterRef = new AtomicReference<Adapter>();
        var fixture = fixture(event("event", (_, _) -> {
            var world = owner.get();
            assertTrue(world.busy());
            assertThrows(IllegalStateException.class, () -> claim(world, adapterRef.get()));
            assertThrows(IllegalStateException.class, () -> world.playerLogin(new UUID(0, 3), 20));
            assertThrows(IllegalStateException.class, () -> world.player(ALICE));
            assertThrows(IllegalStateException.class, world::snapshot);
            assertThrows(IllegalStateException.class, () -> world.close(20));
            int attempts = savesRef.get().attempts.size();
            world.checkpoint(20);
            assertEquals(attempts, savesRef.get().attempts.size());
            return RewardEventAction.Result.APPLIED;
        }), items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        owner.set(world);
        savesRef.set(saves);
        adapterRef.set(adapter);
        saves.beforeWrite = snapshot -> {
            assertThrows(IllegalStateException.class, () -> world.playerLogin(BOB, snapshot.serverTime()));
            assertThrows(IllegalStateException.class, () -> world.close(snapshot.serverTime()));
            int attempts = saves.attempts.size();
            world.checkpoint(snapshot.serverTime());
            assertEquals(attempts, saves.attempts.size());
        };

        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
        assertEquals(8, saves.attempts.size());
        assertFalse(world.faulted());
        saves.beforeWrite = _ -> { };
        world.playerLogin(new UUID(0, 3), 21);
        assertEquals(3, world.snapshot().players().size());
    }

    @Test void staleActorBeforeSelectionOrAfterPlanningPerformsNoEffectsOrSelectionWrites() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, claim(world, adapter, () -> false).status());
        assertEquals(0, adapter.plans);
        var current = new AtomicBoolean(true);
        adapter.afterPlan = () -> current.set(false);
        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, claim(world, adapter, current::get).status());
        assertEquals(1, adapter.plans);
        assertEquals(0, adapter.deliveries);
        assertEquals(0, saves.attempts.size());
        assertEquals(RewardClaim.Status.UNCLAIMED, reward(world.snapshot()).status());
        assertFalse(world.busy());
    }

    @Test void actorInvalidatedByCompletedEventSavesItBeforeStoppingLaterEffects() {
        var current = new AtomicBoolean(true);
        var events = new AtomicInteger();
        var fixture = fixture(event("first", (_, _) -> {
            events.incrementAndGet();
            current.set(false);
            return RewardEventAction.Result.APPLIED;
        }), items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);

        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, claim(world, adapter, current::get).status());
        assertEquals(2, saves.attempts.size());
        assertEquals(RewardClaim.ChildStatus.COMPLETED, reward(saves.durable).children().getFirst().status());
        assertEquals(0, adapter.deliveries);
        var restored = fixture.restore(saves, saves.durable);
        current.set(true);
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(restored, adapter, current::get).status());
        assertEquals(1, events.get());
        assertEquals(2, adapter.deliveries);
    }

    @Test void actorInvalidatedDuringItemDeliveryCommitsAndSavesThatReceiptBeforeStopping() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        var current = new AtomicBoolean(true);
        adapter.beforeDelivery = _ -> current.set(false);

        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, claim(world, adapter, current::get).status());
        assertEquals(1, adapter.deliveries);
        assertEquals(1, adapter.commits);
        assertEquals(1, adapter.releases);
        assertEquals(RewardFragment.Status.CONFIRMED, fragments(saves.durable).getFirst().status());
        assertEquals(RewardFragment.Status.PLANNED, fragments(saves.durable).getLast().status());
        assertEquals(RewardClaim.ChildStatus.PENDING, reward(saves.durable).children().getFirst().status());
        var restored = fixture.restore(saves, saves.durable);
        adapter.beforeDelivery = _ -> { };
        current.set(true);
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(restored, adapter, current::get).status());
        assertEquals(2, adapter.deliveries);
        assertEquals(0, adapter.scans);
        assertEquals(2, adapter.released.size());
    }

    @Test void dissolvedTeamSourceConsumptionAndForgetRetainEligibilityInANewUnqualifiedTeam() {
        var events = new AtomicInteger();
        var fixture = fixture(event("event", (_, _) -> { events.incrementAndGet(); return RewardEventAction.Result.APPLIED; }));
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var before = reward(world.snapshot());
        world.tick(11, Map.of(ALICE, OwnerTeamResolution.rejected(ALICE, OwnerTeamResolutionStatus.NO_TEAM, "team dissolved"),
            BOB, OwnerTeamResolution.rejected(BOB, OwnerTeamResolutionStatus.NO_TEAM, "team dissolved")), Set.of(), _ -> null, () -> true);
        assertTrue(world.snapshot().teams().getFirst().queue().entries().isEmpty());
        var alice = world.player(ALICE);
        var consumed = alice.prepareSourceMutation(RESEARCH, 1, 0);
        consumed.commit(); consumed.complete();
        assertEquals(before, alice.rewardClaimsSnapshot().getFirst());
        assertTrue(alice.completeAtLeast(RESEARCH, 1));
        var forgotten = alice.prepareForgetResearch(RESEARCH, 1);
        forgotten.commit(); forgotten.complete();
        world.checkpoint(12);
        assertEquals(0, alice.completedLevel(RESEARCH));
        assertEquals(before, reward(saves.durable));
        var membership = OwnerTeamResolution.resolved(ALICE, "new_team", OwnerTeamType.PLAYER,
            new ObjectOpenHashSet<>(ObjectList.of(ALICE)), Optional.of(ALICE));
        var permission = new ResearchPermissionContext(ALICE, "new_team", null, "new_team", true, false, false);
        assertEquals(TeamResearchStart.Status.STARTED, world.start(membership, permission, WORK, 1,
            new TeamResearchStart.Environment(2, 1, 1, Object2LongMaps.emptyMap()), 13, Set.of(ALICE), () -> true).status());
        var newTeam = world.snapshot().teams().stream().filter(team -> team.teamId().equals("new_team")).findFirst().orElseThrow();
        assertTrue(newTeam.research().levels().isEmpty());
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, new Adapter(saves)).status());
        assertEquals(1, events.get());
        assertEquals(0, player(saves.durable, ALICE).completedLevels().getInt(RESEARCH));
    }

    @Test void neverInvokedDeliveryPersistsRetryPlanThroughFailedWorldSaveAndNbtRestart() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        adapter.proveAbsence = false;
        var current = new AtomicBoolean(true);
        saves.beforeWrite = snapshot -> {
            if (fragments(snapshot).getFirst().status() == RewardFragment.Status.DELIVERING) current.set(false);
        };
        saves.reject = snapshot -> fragments(snapshot).getFirst().status() == RewardFragment.Status.RETRY_PLANNED;

        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter, current::get)));
        var pending = saves.attempts.getLast();
        var originalPlan = fragments(saves.attempts.getFirst()).getFirst();
        assertEquals(RewardFragment.Status.DELIVERING, fragments(saves.durable).getFirst().status());
        assertEquals(RewardFragment.Status.RETRY_PLANNED, fragments(pending).getFirst().status());
        assertEquals(originalPlan.started().retryPlanned(), fragments(pending).getFirst());
        assertEquals(0, adapter.deliveries);
        assertEquals(0, adapter.commits);
        assertTrue(world.savePending());
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter, current::get)));
        assertSame(pending, saves.attempts.getLast());
        saves.reject = _ -> false;
        saves.beforeWrite = _ -> { };
        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, claim(world, adapter, current::get).status());
        assertSame(pending, saves.attempts.getLast());
        assertFalse(world.savePending());
        var restored = fixture.restore(saves, saves.durable);
        current.set(true);
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(restored, adapter, current::get).status());
        assertEquals(1, adapter.plans);
        assertEquals(0, adapter.scans);
        assertEquals(2, adapter.deliveries);
        assertEquals(2, adapter.physical.size());
    }

    @Test void explicitZeroEffectRetrySaveFailureRewritesBeforeRestartWithoutAbsenceScan() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        adapter.zeroEffect = true;
        adapter.proveAbsence = false;
        saves.reject = snapshot -> fragments(snapshot).getFirst().status() == RewardFragment.Status.RETRY_PLANNED;

        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var pending = saves.attempts.getLast();
        assertEquals(RewardFragment.Status.RETRY_PLANNED, fragments(pending).getFirst().status());
        assertEquals(1, adapter.deliveries);
        assertEquals(0, adapter.commits);
        assertEquals(0, adapter.releases);
        assertTrue(adapter.physical.isEmpty());
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        assertSame(pending, saves.attempts.getLast());
        assertEquals(1, adapter.deliveries);
        saves.reject = _ -> false;
        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, claim(world, adapter, () -> false).status());
        assertSame(pending, saves.attempts.getLast());
        var restored = fixture.restore(saves, saves.durable);
        adapter.zeroEffect = false;
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(restored, adapter).status());
        assertEquals(1, adapter.plans);
        assertEquals(0, adapter.scans);
        assertEquals(3, adapter.deliveries);
        assertEquals(2, adapter.commits);
        assertEquals(2, adapter.released.size());
    }

    @Test void prepareFailureRetainsTheDurableCompleteSelectionWithoutStartingAnyDelivery() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        var failure = new IllegalStateException("prepare flush unavailable");
        adapter.beforePrepare = _ -> { throw failure; };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var selected = saves.durable;
        assertEquals(1, saves.attempts.size());
        assertEquals(2, fragments(selected).size());
        assertTrue(fragments(selected).stream().allMatch(fragment -> fragment.status() == RewardFragment.Status.PLANNED));
        assertEquals(1, adapter.prepares);
        assertEquals(0, adapter.deliveries);
        assertFalse(world.busy());
        assertFalse(world.savePending());
        adapter.beforePrepare = _ -> { };
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
        assertEquals(1, adapter.plans);
        assertEquals(3, adapter.prepares);
        assertEquals(0, adapter.scans);
        assertEquals(2, adapter.deliveries);
        assertEquals(fragments(selected).getFirst().id(), fragments(saves.durable).getFirst().id());
    }

    @Test void prepareCallbackLosingActorProofStopsBeforeTheDeliveringCheckpoint() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        var current = new AtomicBoolean(true);
        adapter.beforePrepare = _ -> current.set(false);

        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, claim(world, adapter, current::get).status());
        assertEquals(1, saves.attempts.size());
        assertEquals(1, adapter.prepares);
        assertEquals(0, adapter.deliveries);
        assertTrue(fragments(saves.durable).stream().allMatch(fragment -> fragment.status() == RewardFragment.Status.PLANNED));
        assertFalse(world.savePending());
        current.set(true);
        adapter.beforePrepare = _ -> { };
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter, current::get).status());
        assertEquals(1, adapter.plans);
        assertEquals(3, adapter.prepares);
        assertEquals(2, adapter.deliveries);
    }

    @Test void absenceProofCheckpointFailureMustRewriteTheOriginalWorldBeforePreparingAgain() {
        var fixture = fixture(items());
        var saves = new Saves(fixture);
        var world = fixture.world(saves);
        var adapter = new Adapter(saves);
        saves.reject = snapshot -> fragments(snapshot).getFirst().status() == RewardFragment.Status.DELIVERING;
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        var started = saves.attempts.getLast();
        assertEquals(1, adapter.prepares);
        assertEquals(0, adapter.deliveries);
        saves.reject = snapshot -> fragments(snapshot).getFirst().status() == RewardFragment.Status.RETRY_PLANNED;

        int recoveryIndex = saves.attempts.size();
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        assertSame(started, saves.attempts.get(recoveryIndex));
        var provenAbsent = saves.attempts.getLast();
        assertEquals(fragments(started).getFirst().retryPlanned(), fragments(provenAbsent).getFirst());
        assertEquals(1, adapter.scans);
        assertEquals(1, adapter.prepares);
        assertEquals(0, adapter.deliveries);
        assertSame(saves.failure, assertThrows(IllegalStateException.class, () -> claim(world, adapter)));
        assertSame(provenAbsent, saves.attempts.getLast());
        assertEquals(1, adapter.prepares);
        saves.reject = _ -> false;
        int retryIndex = saves.attempts.size();
        var preparationStates = new ObjectArrayList<RewardFragment.Status>();
        adapter.beforePrepare = fragment -> preparationStates.add(fragment.status());
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(world, adapter).status());
        assertSame(provenAbsent, saves.attempts.get(retryIndex));
        assertEquals(ObjectList.of(RewardFragment.Status.RETRY_PLANNED, RewardFragment.Status.PLANNED), preparationStates);
        assertEquals(1, adapter.scans);
        assertEquals(1, adapter.plans);
        assertEquals(3, adapter.prepares);
        assertEquals(2, adapter.deliveries);
        assertEquals(world.snapshot(), saves.durable);
    }

    private static ResearchRewardClaimer.Result claim(ResearchWorld world, Adapter adapter) {
        return claim(world, adapter, () -> true);
    }

    private static ResearchRewardClaimer.Result claim(ResearchWorld world, Adapter adapter, BooleanSupplier current) {
        return world.claimReward(ALICE, RESEARCH, 1, GROUP, 20, adapter, current);
    }

    private static PlayerResearchState.Snapshot player(ResearchWorldSnapshot snapshot, UUID id) {
        return snapshot.players().stream().filter(player -> player.playerUuid().equals(id)).findFirst().orElseThrow();
    }

    private static RewardClaim reward(ResearchWorldSnapshot snapshot) { return player(snapshot, ALICE).rewardClaims().getFirst(); }

    private static ObjectList<RewardFragment> fragments(ResearchWorldSnapshot snapshot) {
        return new ObjectArrayList<>(reward(snapshot).children().stream().filter(child -> child.kind() == RewardClaim.Kind.ITEM)
            .findFirst().orElseThrow().fragments().values());
    }

    private static RewardChild.Item items() { return new RewardChild.Item(id("items"), ITEM, 5); }
    private static RewardChild.Event event(String name, RewardEventAction action) { return new RewardChild.Event(id(name), action); }
    private static ResourceLocation id(String path) { return new ResourceLocation("world_reward_test", path); }

    private static Fixture fixture(RewardChild... children) {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        var stage = ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build())
            .rewardGroup(1, new RewardGroup(GROUP, ObjectList.of(children)))
            .rewardGroup(1, new RewardGroup(OTHER_GROUP, ObjectList.of(children))).build();
        registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(1)
            .deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage).build());
        registry.register(ResearchDefinition.builder(WORK).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze(resources);
        return new Fixture(registry, resources, new ResearchWorldNbt(registry, resources,
            new ResourceKeyNbt(type -> type.equals(TYPE.getId()) ? TYPE : null)));
    }

    private record Fixture(ResearchRegistry registry, NetworkResourceRegistry resources, ResearchWorldNbt codec) {
        ResearchWorldSnapshot roundTrip(ResearchWorldSnapshot snapshot) { return codec.decode(codec.encode(snapshot)); }

        ResearchWorld world(Saves saves) {
            var alice = new PlayerResearchState(ALICE);
            var completion = alice.prepareCompletion(RESEARCH, true, 1, IntArrayList.of(1), 10);
            completion.commit(); completion.complete();
            var bob = new PlayerResearchState(BOB);
            var queue = new TeamResearchQueue(registry, resources, "old_team");
            queue.enqueue(WORK, 1, ALICE, 10);
            var team = new ResearchWorldSnapshot.Team("old_team", new ObjectOpenHashSet<>(ObjectList.of(ALICE, BOB)),
                new ObjectOpenHashSet<>(), new TeamOwnedResearch("old_team", registry).snapshot(), queue.snapshot(), ObjectList.of());
            return restore(saves, new ResearchWorldSnapshot(10, ObjectList.of(alice.snapshot(), bob.snapshot()), ObjectList.of(team)));
        }

        ResearchWorld restore(Saves saves, ResearchWorldSnapshot snapshot) {
            var world = new ResearchWorld(registry, resources, ResearchPermissionPolicy.DEFAULT, saves);
            var restored = roundTrip(snapshot);
            world.restore(restored);
            saves.durable = restored;
            return world;
        }
    }

    private static final class Saves implements Consumer<ResearchWorldSnapshot> {
        private final Fixture fixture;
        private final ObjectList<ResearchWorldSnapshot> attempts = new ObjectArrayList<>();
        private final IllegalStateException failure = new IllegalStateException("world flush unavailable");
        private Predicate<ResearchWorldSnapshot> reject = _ -> false;
        private Consumer<ResearchWorldSnapshot> beforeWrite = _ -> { };
        private ResearchWorldSnapshot durable;

        private Saves(Fixture fixture) { this.fixture = fixture; }

        @Override public void accept(ResearchWorldSnapshot snapshot) {
            attempts.add(snapshot);
            assertEquals(snapshot, fixture.roundTrip(snapshot));
            beforeWrite.accept(snapshot);
            if (reject.test(snapshot)) { throw failure; }
            durable = fixture.roundTrip(snapshot);
        }
    }

    private static final class Adapter implements RewardItemDeliveryAdapter {
        private final Saves saves;
        private final Object2ObjectOpenHashMap<UUID, RewardFragment> physical = new Object2ObjectOpenHashMap<>();
        private final Object2ObjectOpenHashMap<UUID, UUID> entities = new Object2ObjectOpenHashMap<>();
        private final ObjectOpenHashSet<UUID> released = new ObjectOpenHashSet<>();
        private Consumer<RewardFragment> beforePrepare = _ -> { };
        private Consumer<RewardFragment> beforeDelivery = _ -> { };
        private Consumer<RewardFragment> afterCommit = _ -> { };
        private Consumer<RewardFragment> afterRelease = _ -> { };
        private Runnable afterPlan = () -> { };
        private boolean zeroEffect;
        private boolean proveAbsence = true;
        private int plans;
        private int scans;
        private int prepares;
        private int deliveries;
        private int commits;
        private int releases;

        private Adapter(Saves saves) { this.saves = saves; }

        @Override public ObjectList<RewardFragment> plan(RewardEventKey parent, RewardChild.Item reward) {
            plans++;
            assertEquals(ITEM, reward.resource());
            assertEquals(5, reward.amount());
            var inventory = fragment(parent, new RewardFragment.Inventory(4), 2);
            var drop = fragment(parent, new RewardFragment.WorldDrop(0, 1, -1, 17.5, 64, -0.5), 3);
            afterPlan.run();
            return ObjectList.of(inventory, drop);
        }

        private static RewardFragment fragment(RewardEventKey parent, RewardFragment.Target target, int count) {
            var tag = new NBTTagCompound();
            tag.setString("payload", "business");
            return new RewardFragment(UUID.randomUUID(), parent, ITEM, count, tag, target, Optional.empty(), RewardFragment.Status.PLANNED);
        }

        @Override public Scan scan(ObjectList<RewardFragment> delivering) {
            scans++;
            var observed = new ObjectOpenHashSet<UUID>();
            var absent = new ObjectOpenHashSet<UUID>();
            var worldEntities = new Object2ObjectOpenHashMap<UUID, UUID>();
            for (var fragment : delivering) {
                assertEquals(RewardFragment.Status.DELIVERING, fragment.status());
                var found = physical.get(fragment.id());
                if (found == null) { if (proveAbsence) absent.add(fragment.id()); }
                else {
                    assertEquals(fragment, found);
                    observed.add(fragment.id());
                    if (entities.containsKey(fragment.id())) { worldEntities.put(fragment.id(), entities.get(fragment.id())); }
                }
            }
            return new Scan(observed, worldEntities, absent);
        }

        @Override public boolean canDeliver(RewardFragment fragment) { return !physical.containsKey(fragment.id()); }

        @Override public void prepare(RewardFragment fragment) {
            RewardItemDeliveryAdapter.super.prepare(fragment);
            prepares++;
            assertEquals(RewardClaim.Status.CLAIMING, reward(saves.durable).status());
            var durable = fragments(saves.durable);
            assertEquals(2, durable.size(), "The entire selected plan must be durable before preparation");
            assertEquals(fragment, durable.stream().filter(saved -> saved.id().equals(fragment.id())).findFirst().orElseThrow());
            beforePrepare.accept(fragment);
        }

        @Override public Delivery deliver(RewardFragment fragment) {
            var durable = fragments(saves.durable).stream().filter(saved -> saved.id().equals(fragment.id())).findFirst().orElseThrow();
            assertEquals(fragment, durable);
            assertEquals(RewardFragment.Status.DELIVERING, durable.status());
            beforeDelivery.accept(fragment);
            deliveries++;
            if (zeroEffect) return new Delivery(0, Optional.empty());
            assertNull(physical.putIfAbsent(fragment.id(), fragment), "A real item fragment must never be issued twice");
            UUID entity = null;
            if (fragment.target() instanceof RewardFragment.WorldDrop) {
                entity = UUID.randomUUID();
                entities.put(fragment.id(), entity);
            }
            return new Delivery(fragment.count(), Optional.ofNullable(entity));
        }

        @Override public void commit(RewardFragment fragment, Delivery delivery) {
            commits++;
            assertEquals(fragment, physical.get(fragment.id()));
            assertEquals(fragment.count(), delivery.acceptedCount());
            assertEquals(Optional.ofNullable(entities.get(fragment.id())), delivery.entityUuid());
            afterCommit.accept(fragment);
        }

        @Override public void release(RewardFragment fragment) {
            releases++;
            assertEquals(RewardFragment.Status.CONFIRMED, fragment.status());
            var durable = fragments(saves.durable).stream().filter(saved -> saved.id().equals(fragment.id())).findFirst().orElseThrow();
            assertEquals(fragment, durable, "Delivery protection can be removed only after exact world confirmation");
            released.add(fragment.id());
            afterRelease.accept(fragment);
        }
    }
}
