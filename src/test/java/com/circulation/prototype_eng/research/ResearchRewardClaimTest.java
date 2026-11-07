package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.PlayerResearchStateNbt;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.reward.*;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchRewardClaimTest {
    private static final ResourceLocation RESEARCH = id("research");
    private static final ResourceLocation GROUP = id("group");
    private static final TestResources.KeyType TYPE = new TestResources.KeyType("reward");
    private static final TestResources.Key ITEM = new TestResources.Key(TYPE, "item");
    private final ResearchRewardClaimer executor = new ResearchRewardClaimer();

    @Test void completionIsEligibilityOnlyAndForgetPreservesClaimsAndExclusiveUndo() {
        var player = new PlayerResearchState(UUID.randomUUID());
        var before = player.snapshot();
        var completion = player.prepareCompletion(RESEARCH, true, 3, IntArrayList.of(1, 3), 10);
        assertTrue(player.busy());
        completion.commit();
        assertTrue(completion.levelRaised());
        assertTrue(completion.rewardClaimEligibilityCreated());
        assertEquals(3, player.completedLevel(RESEARCH));
        assertEquals(2, player.rewardClaimsSnapshot().size());
        assertTrue(player.rewardClaimsSnapshot().stream().allMatch(claim -> claim.status() == RewardClaim.Status.UNCLAIMED));
        assertThrows(IllegalStateException.class, () -> player.prepareForgetResearch(RESEARCH, 1));
        completion.rollback();
        assertEquals(before, player.snapshot());
        assertEquals(ReversibleTransaction.Resolution.ROLLED_BACK, completion.resolution());
        assertThrows(IllegalStateException.class, completion::complete);
        var again = player.prepareCompletion(RESEARCH, true, 3, IntArrayList.of(1, 3), 10);
        again.commit(); again.complete(); again.complete();
        assertThrows(IllegalStateException.class, again::rollback);
        var claims = player.rewardClaimsSnapshot();
        var forget = player.prepareForgetResearch(RESEARCH, 1);
        forget.commit(); forget.complete();
        assertEquals(0, player.completedLevel(RESEARCH));
        assertEquals(claims, player.rewardClaimsSnapshot());
    }

    @Test void selectionCheckpointFailureFixesChoiceAndBlocksAllEffectsUntilRetryBarrier() {
        var events = new AtomicInteger();
        var one = group(new RewardChild.Event(id("event"), (_, _) -> { events.incrementAndGet(); return RewardEventAction.Result.APPLIED; }));
        var two = new RewardGroup(id("other"), one.children());
        var definition = definition(one, two);
        var player = eligible();
        var adapter = new Adapter();
        var attempts = new AtomicInteger();
        var checkpointFailure = new IllegalStateException("offline");
        RewardClaimCheckpoint checkpoint = _ -> { if (attempts.getAndIncrement() < 2) throw checkpointFailure; };
        assertSame(checkpointFailure, assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, checkpoint, adapter)));
        assertFalse(player.busy());
        assertEquals(RewardClaim.Status.CLAIMING, player.rewardClaimsSnapshot().getFirst().status());
        assertEquals(ResearchRewardClaimer.Status.CHOICE_FIXED, claim(player, definition, two.id(), checkpoint, adapter).status());
        assertSame(checkpointFailure, assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, checkpoint, adapter)));
        assertEquals(0, events.get());
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(player, definition, GROUP, checkpoint, adapter).status());
        assertEquals(1, events.get());
    }

    @Test void eventUnknownRestartsWithSameDurableKeyAndDeduplicatesRealEffect() {
        var applied = new ObjectOpenHashSet<RewardEventKey>();
        var attempts = new AtomicInteger();
        var keys = new ObjectArrayList<RewardEventKey>();
        var eventFailure = new IllegalStateException("reply lost");
        var definition = definition(group(new RewardChild.Event(id("event"), (key, _) -> {
            keys.add(key);
            if (applied.add(key)) { attempts.incrementAndGet(); throw eventFailure; }
            return RewardEventAction.Result.ALREADY_APPLIED;
        })));
        var player = eligible();
        var durable = new AtomicReference<RewardClaim>();
        assertSame(eventFailure, assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, durable::set, new Adapter())));
        assertFalse(player.busy());
        var restarted = new PlayerResearchState(player.playerUuid());
        restarted.restore(new PlayerResearchState.Snapshot(player.playerUuid(), player.completedLevelsSnapshot(), ObjectList.of(durable.get())));
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(restarted, definition, GROUP, durable::set, new Adapter()).status());
        assertEquals(1, attempts.get());
        assertEquals(keys.get(0), keys.get(1));
    }

    @Test void completePlanAndStartedLeafMustBeDurableBeforeDeliveryAndFinalCheckpointCanRecover() {
        var player = eligible();
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        var adapter = new Adapter();
        var durable = new AtomicReference<RewardClaim>();
        adapter.beforeDelivery = fragment -> {
            var claim = durable.get();
            assertEquals(2, claim.children().getFirst().fragments().size());
            assertEquals(RewardFragment.Status.DELIVERING, claim.children().getFirst().fragments().get(fragment.id()).status());
        };
        var finalFailures = new AtomicInteger();
        RewardClaimCheckpoint checkpoint = claim -> {
            if (claim.status() == RewardClaim.Status.CLAIMED && finalFailures.getAndIncrement() == 0) throw new IllegalStateException("final write unavailable");
            durable.set(claim);
        };
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, checkpoint, adapter));
        assertEquals(2, adapter.deliveries);
        assertEquals(ResearchRewardClaimer.Status.ALREADY_CLAIMED, claim(player, definition, GROUP, checkpoint, adapter).status());
        assertEquals(RewardClaim.Status.CLAIMED, durable.get().status());
        assertEquals(2, adapter.deliveries);
    }

    @Test void unknownItemCannotRetryUntilAuthoritativeAbsenceAndObservedItemOnlyCommits() {
        var player = eligible();
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        var adapter = new Adapter();
        adapter.failBeforeEffect = true;
        adapter.proveAbsence = false;
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, _ -> { }, adapter));
        assertEquals(1, adapter.deliveries);
        adapter.failBeforeEffect = false;
        assertEquals(ResearchRewardClaimer.Status.RETRY_LATER, claim(player, definition, GROUP, _ -> { }, adapter).status());
        assertEquals(1, adapter.deliveries);
        adapter.proveAbsence = true;
        adapter.failCommit = true;
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, _ -> { }, adapter));
        assertEquals(2, adapter.deliveries);
        adapter.failCommit = false;
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(player, definition, GROUP, _ -> { }, adapter).status());
        assertEquals(3, adapter.deliveries); // Two successful leaves; the observed first leaf was not redelivered.
        assertEquals(2, adapter.physical.size());
    }

    @Test void checkpointBeforeStartedEffectFailureNeverCallsDelivery() {
        var player = eligible();
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        var adapter = new Adapter();
        RewardClaimCheckpoint checkpoint = claim -> {
            if (claim.children().getFirst().fragments().values().stream().anyMatch(fragment -> fragment.status() == RewardFragment.Status.DELIVERING)) {
                throw new IllegalStateException("started checkpoint");
            }
        };
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, checkpoint, adapter));
        assertEquals(0, adapter.deliveries);
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, checkpoint, adapter));
        assertEquals(0, adapter.deliveries);
    }

    @Test void malformedPlanAndConflictingScanFailClosedWithoutRedelivery() {
        var player = eligible();
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        var adapter = new Adapter();
        adapter.duplicatePlan = true;
        assertEquals(ResearchRewardClaimer.Status.RECOVERY_ERROR, claim(player, definition, GROUP, _ -> { }, adapter).status());
        assertEquals(RewardClaim.Status.UNCLAIMED, player.rewardClaimsSnapshot().getFirst().status());
        adapter.duplicatePlan = false; adapter.failBeforeEffect = true;
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, _ -> { }, adapter));
        adapter.conflictScan = true;
        assertEquals(ResearchRewardClaimer.Status.RECOVERY_ERROR, claim(player, definition, GROUP, _ -> { }, adapter).status());
        assertEquals(1, adapter.deliveries);
    }

    @Test void restoredStatePreservesFullIdentityAndRejectsChangedDefinitionsAndMutableEntries() {
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        var registry = new ResearchRegistry();
        registry.register(definition);
        var resources = new NetworkResourceRegistry(); resources.freeze(); registry.freeze(resources);
        var codec = new PlayerResearchStateNbt(registry, new ResourceKeyNbt(typeId -> typeId.equals(TYPE.getId()) ? TYPE : null));
        var player = eligible();
        var adapter = new Adapter(); adapter.failBeforeEffect = true;
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, _ -> { }, adapter));
        var saved = codec.encode(player.snapshot());
        var loaded = codec.decode(saved);
        assertEquals(player.snapshot(), loaded);
        var fragment = loaded.rewardClaims().getFirst().children().getFirst().fragments().values().iterator().next();
        var independent = fragment.taggedStackNbt(); independent.setString("payload", "changed");
        assertEquals("business", fragment.taggedStackNbt().getString("payload"));
        assertThrows(UnsupportedOperationException.class, () -> loaded.completedLevels().object2IntEntrySet().iterator().next().setValue(2));
        assertThrows(UnsupportedOperationException.class, () -> loaded.rewardClaims().getFirst().children().getFirst().fragments().object2ObjectEntrySet().iterator().next().setValue(fragment));
        var corrupt = saved.copy();
        corrupt.getTagList("Claims", 10).getCompoundTagAt(0).setString("Group", id("unknown").toString());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(corrupt));
        var changed = definition(group(new RewardChild.Item(id("items"), ITEM, 6)));
        var restarted = new PlayerResearchState(player.playerUuid()); restarted.restore(loaded);
        assertEquals(ResearchRewardClaimer.Status.RECOVERY_ERROR, claim(restarted, changed, GROUP, _ -> { }, adapter).status());
        assertEquals(1, adapter.deliveries);
    }

    @Test void eventReentryCannotStartAnotherWriterOrDeliverDuplicateChildren() {
        var player = eligible();
        var order = new ObjectArrayList<String>();
        var definition = definition(group(new RewardChild.Event(id("first"), (_, _) -> {
            assertTrue(player.busy());
            assertNull(player.tryRewardSession(player.rewardClaimsSnapshot().getFirst().key()));
            order.add("first"); return RewardEventAction.Result.APPLIED;
        }), new RewardChild.Item(id("items"), ITEM, 5), new RewardChild.Event(id("last"), (_, _) -> { order.add("last"); return RewardEventAction.Result.APPLIED; })));
        var adapter = new Adapter(); adapter.beforeDelivery = _ -> order.add("item");
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(player, definition, GROUP, _ -> { }, adapter).status());
        assertEquals(ObjectList.of("first", "item", "item", "last"), order);
        assertFalse(player.busy());
    }

    @Test void explicitEventRetryPausesSubsequentChildrenAndRetainsSelectedKey() {
        var player = eligible();
        var calls = new AtomicInteger();
        var last = new AtomicInteger();
        var keys = new ObjectArrayList<RewardEventKey>();
        var definition = definition(group(new RewardChild.Event(id("first"), (key, _) -> {
            keys.add(key);
            return calls.getAndIncrement() == 0 ? RewardEventAction.Result.RETRY_LATER : RewardEventAction.Result.APPLIED;
        }), new RewardChild.Event(id("last"), (_, _) -> {
            last.incrementAndGet();
            return RewardEventAction.Result.APPLIED;
        })));
        assertEquals(ResearchRewardClaimer.Status.RETRY_LATER, claim(player, definition, GROUP, _ -> { }, new Adapter()).status());
        assertEquals(0, last.get());
        assertEquals(RewardClaim.Status.CLAIMING, player.rewardClaimsSnapshot().getFirst().status());
        assertFalse(player.busy());
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(player, definition, GROUP, _ -> { }, new Adapter()).status());
        assertEquals(1, last.get());
        assertEquals(keys.getFirst(), keys.getLast());
    }

    @Test void ambiguousDeliveryRepliesNeverRecordNoEffectAndObservedRecoveryDoesNotRedeliver() {
        var replies = ObjectList.<Function<RewardFragment, RewardItemDeliveryAdapter.Delivery>>of(
            _ -> null,
            fragment -> new RewardItemDeliveryAdapter.Delivery(fragment.count() - 1, Optional.empty()),
            _ -> new RewardItemDeliveryAdapter.Delivery(0, Optional.of(UUID.randomUUID())),
            fragment -> new RewardItemDeliveryAdapter.Delivery(fragment.count(), Optional.of(UUID.randomUUID())));
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        for (var reply : replies) {
            var player = eligible();
            var adapter = new Adapter();
            adapter.reply = reply;
            var durable = new AtomicReference<RewardClaim>();
            assertEquals(ResearchRewardClaimer.Status.RETRY_LATER, claim(player, definition, GROUP, durable::set, adapter).status());
            var first = durable.get().children().getFirst().fragments().values().iterator().next();
            assertEquals(RewardFragment.Status.DELIVERING, first.status());
            assertEquals(1, adapter.deliveries);
            assertEquals(1, adapter.physical.size());
            adapter.reply = fragment -> new RewardItemDeliveryAdapter.Delivery(fragment.count(), Optional.empty());
            assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(player, definition, GROUP, durable::set, adapter).status());
            assertEquals(2, adapter.deliveries);
            assertEquals(2, adapter.physical.size());
        }
    }

    @Test void losingCurrentAfterObservationLeavesTheExistingEffectDeliveringUntilConfirmed() {
        var player = eligible();
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        var adapter = new Adapter();
        adapter.failCommit = true;
        var durable = new AtomicReference<RewardClaim>();
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, durable::set, adapter));
        var first = durable.get().children().getFirst().fragments().values().iterator().next();
        var current = new AtomicBoolean(true);
        adapter.afterScan = () -> current.set(false);
        adapter.failCommit = false;
        assertEquals(ResearchRewardClaimer.Status.STALE_CONTEXT, executor.claim(new ResearchRewardClaimer.Request(
            player, definition, 1, GROUP, 20, durable::set, adapter, current::get)).status());
        assertEquals(first, durable.get().children().getFirst().fragments().get(first.id()));
        assertEquals(RewardFragment.Status.DELIVERING, first.status());
        assertEquals(1, adapter.deliveries);
        assertTrue(adapter.physical.containsKey(first.id()));
        adapter.afterScan = () -> { };
        current.set(true);
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(player, definition, GROUP, durable::set, adapter).status());
        assertEquals(2, adapter.deliveries);
    }

    @Test void retryPlanKeepsIdentityAndCannotReopenTerminalOrEntityBearingFragments() {
        var parent = new RewardEventKey(UUID.randomUUID(), RESEARCH, 1, GROUP, id("items"));
        var planned = Adapter.fragment(parent, 0, 2);
        var delivering = planned.started();
        var retry = delivering.retryPlanned();
        var fragments = new Object2ObjectOpenHashMap<UUID, RewardFragment>();
        fragments.put(delivering.id(), delivering);
        var child = new RewardClaim.Child(parent, RewardClaim.Kind.ITEM, RewardClaim.ChildStatus.PENDING, fragments);
        var claim = RewardClaim.unclaimed(new RewardClaim.Key(parent.playerUuid(), RESEARCH, 1), 10)
            .select(GROUP, 20, ObjectList.of(child));
        assertThrows(IllegalStateException.class, () -> claim.replaceChild(0, child.replaceFragment(planned)));
        var retryClaim = claim.replaceChild(0, child.replaceFragment(retry));
        claim.requireTransitionTo(retryClaim);
        assertEquals(delivering, retry.started());
        assertThrows(IllegalStateException.class, () -> retryClaim.replaceChild(0, child.replaceFragment(planned)));
        assertThrows(IllegalStateException.class, () -> retryClaim.replaceChild(0, child.replaceFragment(delivering.confirmed(null))));
        assertThrows(IllegalStateException.class, planned::retryPlanned);
        assertThrows(IllegalStateException.class, () -> delivering.confirmed(null).retryPlanned());
        var entityBearing = new RewardFragment(UUID.randomUUID(), parent, ITEM, 2, planned.taggedStackNbt(),
            new RewardFragment.WorldDrop(0, 0, 0, 1, 64, 1), Optional.of(UUID.randomUUID()), RewardFragment.Status.DELIVERING);
        assertThrows(IllegalStateException.class, entityBearing::retryPlanned);
        var terminal = claim.replaceChild(0, child.replaceFragment(delivering.confirmed(null)));
        assertThrows(IllegalStateException.class, () -> terminal.replaceChild(0, child.replaceFragment(retry)));
    }

    @Test void authoritativeAbsencePersistsRetryPlanBeforePrepareAndPreparationFailureCanRetryIt() {
        var player = eligible();
        var definition = definition(group(new RewardChild.Item(id("items"), ITEM, 5)));
        var adapter = new Adapter();
        adapter.failBeforeEffect = true;
        var durable = new AtomicReference<RewardClaim>();
        assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, durable::set, adapter));
        var first = durable.get().children().getFirst().fragments().values().iterator().next();
        assertEquals(RewardFragment.Status.DELIVERING, first.status());
        var failure = new IllegalStateException("retry preparation flush failed");
        adapter.failBeforeEffect = false;
        adapter.beforePrepare = fragment -> {
            assertEquals(first.retryPlanned(), fragment);
            assertEquals(fragment, durable.get().children().getFirst().fragments().get(fragment.id()));
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> claim(player, definition, GROUP, durable::set, adapter)));
        assertEquals(first.retryPlanned(), durable.get().children().getFirst().fragments().get(first.id()));
        assertEquals(1, adapter.deliveries);
        assertTrue(adapter.physical.isEmpty());
        assertFalse(player.busy());
        adapter.beforePrepare = fragment -> assertEquals(fragment, durable.get().children().getFirst().fragments().get(fragment.id()));
        assertEquals(ResearchRewardClaimer.Status.CLAIMED, claim(player, definition, GROUP, durable::set, adapter).status());
        assertEquals(ObjectList.of(RewardFragment.Status.PLANNED, RewardFragment.Status.RETRY_PLANNED,
            RewardFragment.Status.RETRY_PLANNED, RewardFragment.Status.PLANNED),
            new ObjectArrayList<>(adapter.prepared.stream().map(RewardFragment::status).toList()));
        assertEquals(3, adapter.deliveries);
        assertEquals(2, adapter.physical.size());
    }

    private ResearchRewardClaimer.Result claim(PlayerResearchState player, ResearchDefinition definition, ResourceLocation choice, RewardClaimCheckpoint checkpoint, Adapter adapter) {
        return executor.claim(new ResearchRewardClaimer.Request(player, definition, 1, choice, 20, checkpoint, adapter));
    }
    private static PlayerResearchState eligible() {
        var player = new PlayerResearchState(UUID.randomUUID());
        var completion = player.prepareCompletion(RESEARCH, true, 1, IntArrayList.of(1), 10);
        completion.commit(); completion.complete();
        return player;
    }
    private static RewardGroup group(RewardChild... children) { return new RewardGroup(GROUP, ObjectList.of(children)); }
    private static ResearchDefinition definition(RewardGroup... groups) {
        var stage = ResearchLevelStage.builder(1, 3).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build());
        for (var group : groups) stage.rewardGroup(1, group);
        return ResearchDefinition.builder(RESEARCH).maxLevel(3).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage.build()).build();
    }
    private static ResourceLocation id(String path) { return new ResourceLocation("reward_test", path); }

    private static final class Adapter implements RewardItemDeliveryAdapter {
        private final Object2ObjectOpenHashMap<UUID, RewardFragment> physical = new Object2ObjectOpenHashMap<>();
        private Consumer<RewardFragment> beforeDelivery = _ -> { };
        private Consumer<RewardFragment> beforePrepare = _ -> { };
        private final ObjectList<RewardFragment> prepared = new ObjectArrayList<>();
        private Runnable afterScan = () -> { };
        private Function<RewardFragment, Delivery> reply = fragment -> new Delivery(fragment.count(), Optional.empty());
        private int deliveries;
        private boolean failBeforeEffect;
        private boolean failCommit;
        private boolean proveAbsence = true;
        private boolean duplicatePlan;
        private boolean conflictScan;

        @Override public ObjectList<RewardFragment> plan(RewardEventKey parent, RewardChild.Item reward) {
            var first = fragment(parent, 0, 2);
            return ObjectList.of(first, duplicatePlan ? first : fragment(parent, 1, (int) reward.amount() - 2));
        }
        private static RewardFragment fragment(RewardEventKey key, int slot, int count) {
            var tag = new NBTTagCompound(); tag.setString("payload", "business");
            return new RewardFragment(UUID.randomUUID(), key, ITEM, count, tag, new RewardFragment.Inventory(slot), Optional.empty(), RewardFragment.Status.PLANNED);
        }
        @Override public Scan scan(ObjectList<RewardFragment> fragments) {
            var observed = new ObjectOpenHashSet<UUID>();
            var absent = new ObjectOpenHashSet<UUID>();
            for (var fragment : fragments) {
                if (physical.containsKey(fragment.id()) || conflictScan) observed.add(fragment.id());
                if (proveAbsence && !physical.containsKey(fragment.id())) absent.add(fragment.id());
            }
            afterScan.run();
            return new Scan(observed, new Object2ObjectOpenHashMap<>(), absent);
        }
        @Override public boolean canDeliver(RewardFragment fragment) { return !physical.containsKey(fragment.id()); }
        @Override public void prepare(RewardFragment fragment) {
            RewardItemDeliveryAdapter.super.prepare(fragment);
            prepared.add(fragment);
            beforePrepare.accept(fragment);
        }
        @Override public Delivery deliver(RewardFragment fragment) {
            beforeDelivery.accept(fragment);
            deliveries++;
            if (failBeforeEffect) throw new IllegalStateException("unknown delivery");
            assertNull(physical.putIfAbsent(fragment.id(), fragment));
            return reply.apply(fragment);
        }
        @Override public void commit(RewardFragment fragment, Delivery delivery) {
            assertEquals(fragment, physical.get(fragment.id()));
            assertEquals(fragment.count(), delivery.acceptedCount());
            if (failCommit) throw new IllegalStateException("unavailable persistence");
        }
    }
}
