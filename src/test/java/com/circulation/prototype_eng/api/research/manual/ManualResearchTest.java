package com.circulation.prototype_eng.api.research.manual;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.research.manual.ManualMaterials;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The package compiler seam isolates game key construction while executing the real state machine.
 */
class ManualResearchTest {
    private static final ResourceLocation TARGET = id("target");
    private static final ResourceLocation TAIL = id("tail");
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final UUID LATE = new UUID(0, 3);
    private static final TestResources.KeyType TYPE = new TestResources.KeyType("manual_runtime");
    private static final TestResources.Key MATERIAL = new TestResources.Key(TYPE, "material");

    private static void checkPauseAndTakeover(ManualResearch.Context unavailable, ManualResearchSession.PauseReason reason) {
        var fixture = fixture(3, 2);
        fixture.begin();
        var original = new FakeInventory(FIRST, 10);
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2, original));
        assertEquals(ManualResearch.Status.PAUSED, fixture.runtime.advance(fixture.entry.key().queueEntryId(), unavailable, original));
        var paused = fixture.session();
        assertEquals(reason, paused.pauseReason());
        assertEquals(2, paused.work().size());
        assertEquals(1, paused.work().getLong(material(0)));
        long resumedAt = unavailable.serverTime() + 1;
        assertEquals(ManualResearch.Status.RESUMED, fixture.runtime.takeover(fixture.entry.key().queueEntryId(), SECOND, context(resumedAt)));
        var successor = new FakeInventory(SECOND, 10);
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(resumedAt + 1, successor));
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(resumedAt + 2, successor));
        assertEquals(8, original.amount);
        assertEquals(7, successor.amount);
    }

    private static void checkWorldCheckpointRecovery(ManualResearchSession.Status boundary) {
        var fixture = fixture(1);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        fixture.checkpoints.failStatus = boundary;
        assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory)));
        assertTrue(fixture.runtime.checkpointPending());
        var failedState = fixture.checkpoints.attempts.getLast();
        int consumed = inventory.consumed;
        fixture.checkpoints.failStatus = boundary;
        assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, () -> fixture.advance(3, inventory)));
        assertSame(failedState, fixture.checkpoints.attempts.getLast());
        assertEquals(consumed, inventory.consumed);
        fixture.runtime.retryCheckpoint();
        assertSame(failedState, fixture.checkpoints.attempts.getLast());
        assertEquals(consumed, inventory.consumed);
        assertFalse(fixture.runtime.checkpointPending());
        fixture.reopen();
        if (boundary == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING) {
            // The world intent was saved before recordPrepared was called; absence remains uncertain.
            assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(3, inventory));
            assertEquals(0, inventory.consumed);
            assertEquals(10, inventory.amount);
            return;
        }
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(3, inventory));
        assertEquals(1, inventory.consumed);
        assertEquals(9, inventory.amount);
        assertCompletion(fixture, FIRST, 1);
    }

    private static void assertCompletion(Fixture fixture, UUID player, int personalLevel) {
        var source = fixture.players.get(player);
        assertEquals(personalLevel, source.completedLevel(TARGET));
        assertEquals(1, source.rewardClaimsSnapshot().size());
        assertEquals(RewardClaim.Status.UNCLAIMED, source.rewardClaimsSnapshot().getFirst().status());
        assertEquals(TARGET, source.rewardClaimsSnapshot().getFirst().key().researchId());
    }

    private static Fixture fixture(long... targets) {
        return fixture(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM, targets);
    }

    private static Fixture fixture(ResearchDownloadDeliveryPolicy policy, long... targets) {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        var rewards = new RewardGroup(id("reward"), List.of(new RewardChild.Event(id("event"), (_, _) -> {
            fail("Manual completion only creates reward eligibility");
            return RewardEventAction.Result.APPLIED;
        })));
        for (var research : List.of(TARGET, TAIL)) {
            registry.register(ResearchDefinition.builder(research).maxLevel(1).deliveryPolicy(policy)
                                                .stage(ResearchLevelStage.builder(1, 1).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build())
                                                                         .rewardGroup(1, rewards).build()).build());
        }
        registry.freeze(resources);
        var inputs = new ObjectArrayList<GenericStack>();
        for (long target : targets) {
            inputs.add(new GenericStack(MATERIAL, target));
        }
        var materials = ManualMaterials.from(List.of(RecipeRequirements.RESOURCES.requirement(id("materials"), new ResourceRequirement(inputs, List.of()))));
        return new Fixture(registry, resources, materials);
    }

    private static ManualResearch.Context context(long time) {
        return context(time, Set.of(FIRST, SECOND), Set.of(FIRST, SECOND));
    }

    private static ManualResearch.Context context(long time, Set<UUID> members, Set<UUID> online) {
        return new ManualResearch.Context(time, members, online, () -> true);
    }

    private static ManualMaterialKey material(int index) {
        return new ManualMaterialKey(id("materials"), index);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("manual_test", name);
    }

    @Test
    void advancesEveryUnfinishedInputAndGrantsLevelsAndEligibilityWithoutDeliveringRewards() {
        var fixture = fixture(2, 3);
        var inventory = new FakeInventory(FIRST, 20);
        fixture.begin();
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2, inventory));
        assertEquals(18, inventory.amount);
        assertEquals(1, fixture.session().work().getLong(material(0)));
        assertEquals(1, fixture.session().work().getLong(material(1)));
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(3, inventory));
        assertEquals(16, inventory.amount);
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(4, inventory));
        assertEquals(15, inventory.amount);
        assertEquals(List.of(0L, 2L, 4L), inventory.sequences);
        assertEquals(3, inventory.consumed);
        assertNull(inventory.receipt);
        assertTrue(fixture.queue.queueSnapshot().isEmpty());
        assertTrue(fixture.runtime.sessions().isEmpty());
        for (var member : List.of(FIRST, SECOND)) {
            assertCompletion(fixture, member, 1);
        }
        assertEquals(0, fixture.team.completedLevel(TARGET));
    }

    @Test
    void teamOwnedCompletionGrantsRewardEligibilityWithoutPersonalLevels() {
        var fixture = fixture(ResearchDownloadDeliveryPolicy.ITEM_ONLY, 1);
        fixture.begin();
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(2, new FakeInventory(FIRST, 10)));
        assertEquals(1, fixture.team.completedLevel(TARGET));
        for (var member : List.of(FIRST, SECOND)) {
            assertCompletion(fixture, member, 0);
        }
    }

    @Test
    void noMaterialsLeavesNoIntentReceiptOrCheckpointAndNonHeadDoesNotInspectInventory() {
        var fixture = fixture(2);
        fixture.begin();
        var tail = fixture.queue.enqueue(TAIL, 1, FIRST, 1);
        assertEquals(ManualResearch.Status.STARTED, fixture.runtime.begin(tail, FIRST, context(1)));
        var inventory = new FakeInventory(FIRST, 0);
        int saved = fixture.checkpoints.attempts.size();
        assertEquals(ManualResearch.Status.NO_MATERIALS, fixture.advance(2, inventory));
        assertEquals(ManualResearch.Status.WAITING_FOR_TURN, fixture.runtime.advance(tail.key().queueEntryId(), context(2), inventory));
        assertEquals(saved, fixture.checkpoints.attempts.size());
        assertEquals(0, inventory.prepared);
        assertEquals(0, inventory.consumed);
        assertNull(fixture.session().materialIntent());
    }

    @Test
    void offlineTeamChangeAndTimeoutPauseThenTakeoverPreservesPaidProgress() {
        checkPauseAndTakeover(context(3, Set.of(FIRST, SECOND), Set.of(SECOND)), ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE);
        checkPauseAndTakeover(context(3, Set.of(SECOND), Set.of(FIRST, SECOND)), ManualResearchSession.PauseReason.CONTRIBUTOR_TEAM_CHANGED);
        checkPauseAndTakeover(context(1_202), ManualResearchSession.PauseReason.SESSION_TIMEOUT);
    }

    @Test
    void restoredActiveWorkRequiresTakeoverAndRejectsOtherContributorInventory() {
        var fixture = fixture(3);
        fixture.begin();
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2, new FakeInventory(FIRST, 10)));
        fixture.reopen();
        assertEquals(ManualResearchSession.PauseReason.WORLD_RESTORED, fixture.session().pauseReason());
        assertEquals(ManualResearch.Status.PAUSED, fixture.advance(3, new FakeInventory(FIRST, 10)));
        assertEquals(ManualResearch.Status.RESUMED, fixture.runtime.takeover(fixture.entry.key().queueEntryId(), SECOND, context(3)));
        assertThrows(IllegalArgumentException.class, () -> fixture.advance(4, new FakeInventory(FIRST, 10)));
        assertEquals(1, fixture.session().work().getLong(material(0)));
    }

    @Test
    void committedPlayerSaveFailureEscapesUnchangedAndRestorationDoesNotConsumeAgain() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        var failure = new IllegalStateException("player COMMITTED save failed");
        inventory.failCommitted = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory)));
        assertEquals(9, inventory.amount);
        assertEquals(ManualResearchMaterialReceipt.Status.COMMITTED, Objects.requireNonNull(inventory.receipt).status());
        assertEquals(ManualResearchSession.Status.MATERIAL_COMMIT_PENDING, fixture.session().status());
        fixture.reopen();
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(3, inventory));
        assertEquals(9, inventory.amount);
        assertEquals(1, inventory.consumed);
        assertNull(inventory.receipt);
        assertEquals(1, fixture.session().work().getLong(material(0)));
    }

    @Test
    void missingPreparedReceiptAndChangedBeforeInventoryRetainIntentWithoutConsumption() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        inventory.allowConsume = false;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(2, inventory));
        var receipt = inventory.receipt;
        inventory.receipt = null;
        fixture.reopen();
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(3, inventory));
        inventory.receipt = receipt;
        inventory.amount++;
        inventory.allowConsume = true;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(4, inventory));
        assertEquals(0, inventory.consumed);
        assertEquals(11, inventory.amount);
        assertEquals(ManualResearchSession.Status.MATERIAL_COMMIT_PENDING, fixture.session().status());
    }

    @Test
    void preparedIntentAbortsAfterContributorLeavesTeamWithoutLosingWork() {
        var fixture = fixture(3);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2, inventory));
        inventory.allowConsume = false;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(3, inventory));
        assertEquals(ManualResearch.Status.PAUSED, fixture.runtime.advance(fixture.entry.key().queueEntryId(),
            context(4, Set.of(SECOND), Set.of(FIRST, SECOND)), inventory));
        assertEquals(1, inventory.consumed);
        assertEquals(9, inventory.amount);
        assertNull(inventory.receipt);
        assertEquals(1, fixture.session().work().getLong(material(0)));
        assertEquals(ManualResearch.Status.RESUMED, fixture.runtime.takeover(fixture.entry.key().queueEntryId(), SECOND,
            context(5, Set.of(SECOND), Set.of(SECOND))));
    }

    @Test
    void finalCompletionUsesFrozenMembersAndTimeAfterPlayerSaveFailure() {
        var fixture = fixture(1);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        inventory.failCommitted = new IllegalStateException("defer final material save");
        assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory));
        fixture.reopen();
        assertEquals(ManualResearch.Status.COMPLETED, fixture.runtime.advance(fixture.entry.key().queueEntryId(),
            context(3, Set.of(FIRST, LATE), Set.of(FIRST, LATE)), inventory));
        for (var member : List.of(FIRST, SECOND)) {
            assertCompletion(fixture, member, 1);
            assertEquals(2, fixture.players.get(member).rewardClaimsSnapshot().getFirst().eligibleAtWorldTime());
        }
        assertEquals(0, fixture.players.get(LATE).completedLevel(TARGET));
        assertTrue(fixture.players.get(LATE).rewardClaimsSnapshot().isEmpty());
        assertEquals(1, inventory.consumed);
    }

    @Test
    void worldCheckpointFailuresRetryTheExactStateBeforeAnyFurtherInventoryOperation() {
        for (var boundary : List.of(ManualResearchSession.Status.MATERIAL_COMMIT_PENDING, ManualResearchSession.Status.MATERIAL_COMMITTED,
            ManualResearchSession.Status.MATERIAL_SETTLED_RECEIPT_PENDING, ManualResearchSession.Status.COMPLETION_PENDING,
            ManualResearchSession.Status.COMPLETION_PROPAGATED, ManualResearchSession.Status.QUEUE_HEAD_REMOVED)) {
            checkWorldCheckpointRecovery(boundary);
        }
    }

    @Test
    void finalSessionDeletionCheckpointRetriesWithoutReplayingCompletion() {
        var fixture = fixture(1);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        fixture.checkpoints.failEmpty = true;
        assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory)));
        assertTrue(fixture.runtime.sessions().isEmpty());
        assertTrue(fixture.runtime.checkpointPending());
        fixture.runtime.retryCheckpoint();
        assertFalse(fixture.runtime.checkpointPending());
        assertEquals(1, inventory.consumed);
        assertCompletion(fixture, FIRST, 1);
        fixture.reopen();
        assertTrue(fixture.runtime.sessions().isEmpty());
        assertTrue(fixture.queue.queueSnapshot().isEmpty());
    }

    @Test
    void anUnpreparedIntentContinuesInTheSameProcessAfterItsWorldCheckpointSucceeds() {
        var fixture = fixture(1);
        fixture.begin();
        fixture.checkpoints.failStatus = ManualResearchSession.Status.MATERIAL_COMMIT_PENDING;
        var inventory = new FakeInventory(FIRST, 10);
        assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory)));
        var failedState = fixture.checkpoints.attempts.getLast();
        int retryIndex = fixture.checkpoints.attempts.size();
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(3, inventory));
        assertSame(failedState, fixture.checkpoints.attempts.get(retryIndex));
        assertEquals(1, inventory.prepared);
        assertEquals(1, inventory.consumed);
        assertEquals(9, inventory.amount);
        assertCompletion(fixture, FIRST, 1);
    }

    @Test
    void checkpointCallbackCannotReenterTheRuntime() {
        var fixture = fixture(2);
        fixture.checkpoints.callback = _ -> assertThrows(IllegalStateException.class,
            () -> fixture.runtime.begin(fixture.entry, FIRST, context(1)));
        fixture.begin();
        assertEquals(1, fixture.runtime.sessions().size());
        assertEquals(1, fixture.checkpoints.attempts.size());
        assertFalse(fixture.runtime.checkpointPending());
    }

    @Test
    void losingQueueOwnershipRejectsAnOldPendingCheckpointBeforeCallingPersistenceAgain() {
        var fixture = fixture(2);
        fixture.checkpoints.failStatus = ManualResearchSession.Status.ACTIVE;
        assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, fixture::begin));
        int attempts = fixture.checkpoints.attempts.size();
        fixture.queue.enqueue(TAIL, 1, FIRST, 2);
        assertThrows(IllegalStateException.class, fixture.runtime::retryCheckpoint);
        assertEquals(attempts, fixture.checkpoints.attempts.size());
        assertTrue(fixture.runtime.checkpointPending());
    }

    @Test
    void aPendingQueueMutationBlocksSessionWritesBeforeTheyBecomeUnsavedState() {
        var fixture = fixture(2);
        var view = new TeamResearchView(fixture.registry, "team", Set.of(FIRST, SECOND),
            Object2IntMaps.emptyMap(), Object2IntMaps.emptyMap(), List.of());
        var blocker = fixture.queue.preparePrune(view);
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.runtime.begin(fixture.entry, FIRST, context(1)));
        assertTrue(fixture.runtime.sessions().isEmpty());
        assertTrue(fixture.checkpoints.attempts.isEmpty());
        blocker.rollback();
        fixture.begin();
        var before = fixture.session();
        var secondBlocker = fixture.queue.preparePrune(view);
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.runtime.takeover(fixture.entry.key().queueEntryId(), SECOND, context(2)));
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.runtime.pause(fixture.entry.key().queueEntryId(), context(2)));
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(2, new FakeInventory(FIRST, 10)));
        assertSame(before, fixture.session());
        assertEquals(1, fixture.checkpoints.attempts.size());
        secondBlocker.rollback();
    }

    @Test
    void pendingCompletionCheckpointRejectsAPlayerWriterWithAnUnchangedSnapshot() {
        var fixture = fixture(1);
        fixture.begin();
        fixture.checkpoints.failStatus = ManualResearchSession.Status.COMPLETION_PROPAGATED;
        var inventory = new FakeInventory(FIRST, 10);
        assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory));
        int attempts = fixture.checkpoints.attempts.size();
        var blocker = fixture.players.get(FIRST).prepareSourceMutation(TARGET, 1, 0);
        assertThrows(IllegalStateException.class, fixture.runtime::retryCheckpoint);
        assertEquals(attempts, fixture.checkpoints.attempts.size());
        blocker.rollback();
        fixture.runtime.retryCheckpoint();
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(3, inventory));
        assertEquals(1, inventory.consumed);
        assertCompletion(fixture, FIRST, 1);
    }

    @Test
    void invalidationRemovesOnlyItsExactEntryWithoutRefundingPreviouslyPaidWork() {
        var fixture = fixture(3);
        fixture.begin();
        var tail = fixture.queue.enqueue(TAIL, 1, FIRST, 1);
        var inventory = new FakeInventory(FIRST, 10);
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2, inventory));
        assertEquals(ManualResearch.Status.CANCELLED, fixture.cancel(3, inventory));
        assertEquals(9, inventory.amount);
        assertEquals(1, inventory.consumed);
        assertEquals(List.of(tail), fixture.queue.queueSnapshot());
        assertTrue(fixture.runtime.sessions().isEmpty());
        assertEquals(0, fixture.players.get(FIRST).completedLevel(TARGET));
        assertTrue(fixture.players.get(FIRST).rewardClaimsSnapshot().isEmpty());
        assertEquals(List.of(tail), Objects.requireNonNull(fixture.checkpoints.latest).queue().entries());
    }

    @Test
    void namedOfflinePauseAndInvalidationCanHandleANonHeadActiveSession() {
        var fixture = fixture(2);
        fixture.begin();
        var tail = fixture.queue.enqueue(TAIL, 1, SECOND, 1);
        assertEquals(ManualResearch.Status.STARTED, fixture.runtime.begin(tail, SECOND, context(1)));
        assertEquals(ManualResearch.Status.PAUSED, fixture.runtime.pause(tail.key().queueEntryId(), context(2),
            ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE));
        assertEquals(ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE,
            Objects.requireNonNull(fixture.runtime.session(tail.key().queueEntryId())).pauseReason());
        assertEquals(ManualResearch.Status.CANCELLED, fixture.runtime.cancelInvalidated(tail.key().queueEntryId(), context(3), null));
        assertEquals(List.of(fixture.entry), fixture.queue.queueSnapshot());
        assertEquals(ManualResearchSession.Status.ACTIVE, fixture.session().status());
    }

    @Test
    void invalidatedPreparedReceiptIsAbortedAndRemovedWithoutConsumingMaterials() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        inventory.allowConsume = false;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(2, inventory));
        inventory.allowConsume = true;
        assertEquals(ManualResearch.Status.CANCELLED, fixture.cancel(3, inventory));
        assertEquals(0, inventory.consumed);
        assertEquals(10, inventory.amount);
        assertNull(inventory.receipt);
        assertTrue(fixture.queue.queueSnapshot().isEmpty());
    }

    @Test
    void liveUnpreparedProofPermitsInvalidationWithoutCreatingANewReceipt() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        fixture.checkpoints.failStatus = ManualResearchSession.Status.MATERIAL_COMMIT_PENDING;
        assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory));
        assertEquals(ManualResearch.Status.CANCELLED, fixture.cancel(3, inventory));
        assertEquals(0, inventory.prepared);
        assertEquals(0, inventory.consumed);
        assertEquals(10, inventory.amount);
    }

    @Test
    void invalidationRetainsRestoredMissingReceiptsChangedBeforeImagesAndOfflineContributors() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        inventory.allowConsume = false;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(2, inventory));
        var original = inventory.receipt;
        inventory.receipt = null;
        fixture.reopen();
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.cancel(3, inventory));
        inventory.receipt = original;
        inventory.amount++;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.cancel(4, inventory));
        inventory.amount--;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.runtime.cancelInvalidated(fixture.entry.key().queueEntryId(),
            context(5, Set.of(SECOND), Set.of(SECOND)), inventory));
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.cancel(6, null));
        assertEquals(ManualResearchSession.Status.MATERIAL_COMMIT_PENDING, fixture.session().status());
        assertEquals(List.of(fixture.entry), fixture.queue.queueSnapshot());
        assertEquals(0, inventory.consumed);
    }

    @Test
    void invalidationRejectsStaleContextAndForeignReceiptWithoutWritingAnAbort() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        inventory.allowConsume = false;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(2, inventory));
        var before = fixture.session();
        int saves = fixture.checkpoints.attempts.size();
        assertEquals(ManualResearch.Status.STALE_CONTEXT, fixture.runtime.cancelInvalidated(fixture.entry.key().queueEntryId(),
            new ManualResearch.Context(3, Set.of(FIRST), Set.of(FIRST), () -> false), inventory));
        var receipt = Objects.requireNonNull(inventory.receipt);
        inventory.receipt = new ManualResearchMaterialReceipt(new UUID(0, 99), receipt.teamId(), receipt.queueKey(), receipt.intent(),
            receipt.inventoryBefore(), null, ManualResearchMaterialReceipt.Status.PREPARED, receipt.preparedAt(), -1, -1);
        assertThrows(IllegalStateException.class, () -> fixture.cancel(4, inventory));
        assertSame(before, fixture.session());
        assertEquals(saves, fixture.checkpoints.attempts.size());
        assertEquals(0, inventory.consumed);
    }

    @Test
    void nonHeadPendingMaterialIsRetainedWithoutTouchingItsReceipt() {
        var fixture = fixture(2);
        fixture.begin();
        var tail = fixture.queue.enqueue(TAIL, 1, SECOND, 1);
        var session = ManualResearchSession.newActive(new UUID(0, 50), "team", tail.key(), SECOND, 1);
        var plan = fixture.materials.plan(session.work());
        var intent = new ManualResearchSession.MaterialIntent(new UUID(0, 51), SECOND, plan.sequence(), plan.increments(), plan.expectedWork());
        var pending = session.beginMaterial(intent, 2, null);
        var firstSession = fixture.session();
        fixture.runtime = new ManualResearch(fixture.queue, fixture.team, fixture.players, fixture.checkpoints, _ -> fixture.materials);
        fixture.runtime.restore(List.of(firstSession, pending));
        var inventory = new FakeInventory(SECOND, 10);
        inventory.receipt = new ManualResearchMaterialReceipt(pending.id(), "team", tail.key(), intent, inventory.snapshotInventory(), null,
            ManualResearchMaterialReceipt.Status.PREPARED, 2, -1, -1);
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.runtime.cancelInvalidated(tail.key().queueEntryId(), context(3), inventory));
        assertEquals(ManualResearchSession.Status.MATERIAL_COMMIT_PENDING,
            Objects.requireNonNull(fixture.runtime.session(tail.key().queueEntryId())).status());
        assertEquals(0, inventory.consumed);
        assertNotNull(inventory.receipt);
        assertEquals(List.of(fixture.entry, tail), fixture.queue.queueSnapshot());
    }

    @Test
    void invalidationSettlesCommittedMaterialOnceThenCancelsTheRemainingWork() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        inventory.failCommitted = new IllegalStateException("committed save failed");
        assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory));
        fixture.reopen();
        assertEquals(ManualResearch.Status.CANCELLED, fixture.cancel(3, inventory));
        assertEquals(1, inventory.consumed);
        assertEquals(9, inventory.amount);
        assertNull(inventory.receipt);
        assertTrue(fixture.runtime.sessions().isEmpty());
        assertEquals(0, fixture.players.get(FIRST).completedLevel(TARGET));
    }

    @Test
    void invalidationRetainsACommittedWorldIntentUntilItsReceiptIsAvailable() {
        var fixture = fixture(2);
        fixture.begin();
        var inventory = new FakeInventory(FIRST, 10);
        fixture.checkpoints.failStatus = ManualResearchSession.Status.MATERIAL_COMMITTED;
        assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory));
        fixture.runtime.retryCheckpoint();
        fixture.reopen();
        var committed = inventory.receipt;
        inventory.receipt = null;
        assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.cancel(3, inventory));
        assertEquals(ManualResearchSession.Status.MATERIAL_COMMITTED, fixture.session().status());
        assertEquals(List.of(fixture.entry), fixture.queue.queueSnapshot());
        inventory.receipt = committed;
        assertEquals(ManualResearch.Status.CANCELLED, fixture.cancel(4, inventory));
        assertEquals(1, inventory.consumed);
        assertEquals(9, inventory.amount);
    }

    @Test
    void invalidationOfFinalCommittedOrCompletionWorkPropagatesFrozenEligibility() {
        for (var boundary : List.of(ManualResearchSession.Status.MATERIAL_COMMITTED, ManualResearchSession.Status.MATERIAL_SETTLED_RECEIPT_PENDING,
            ManualResearchSession.Status.COMPLETION_PENDING, ManualResearchSession.Status.COMPLETION_PROPAGATED,
            ManualResearchSession.Status.QUEUE_HEAD_REMOVED)) {
            var fixture = fixture(1);
            fixture.begin();
            var inventory = new FakeInventory(FIRST, 10);
            fixture.checkpoints.failStatus = boundary;
            assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory));
            assertEquals(ManualResearch.Status.COMPLETED, fixture.cancel(3, inventory));
            assertEquals(1, inventory.consumed);
            for (var player : List.of(FIRST, SECOND)) {
                assertCompletion(fixture, player, 1);
            }
        }
    }

    @Test
    void abortCheckpointFailuresPreserveTheInvalidationUntilReceiptsAreSafelyRemoved() {
        for (var boundary : List.of(ManualResearchSession.Status.MATERIAL_ABORT_RECEIPT_PENDING, ManualResearchSession.Status.PAUSED)) {
            var fixture = fixture(2);
            fixture.begin();
            var inventory = new FakeInventory(FIRST, 10);
            inventory.allowConsume = false;
            assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(2, inventory));
            fixture.checkpoints.failStatus = boundary;
            assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, () -> fixture.cancel(3, inventory)));
            assertEquals(ManualResearchSession.PauseReason.RESEARCH_INVALIDATED, fixture.session().pauseReason());
            fixture.runtime.retryCheckpoint();
            fixture.reopen();
            assertEquals(ManualResearch.Status.CANCELLED, fixture.cancel(4, inventory));
            assertEquals(0, inventory.consumed);
            assertEquals(10, inventory.amount);
            assertNull(inventory.receipt);
        }
    }

    @Test
    void cancellationDeletionFailureReturnsCancelledOnEitherRetryEntryWithoutCompletingResearch() {
        for (boolean retryWithAdvance : new boolean[]{false, true}) {
            var fixture = fixture(2);
            fixture.begin();
            fixture.checkpoints.failEmpty = true;
            assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, () -> fixture.cancel(2, null)));
            assertTrue(fixture.runtime.checkpointPending());
            assertTrue(fixture.runtime.sessions().isEmpty());
            var status = retryWithAdvance ? fixture.advance(3, new FakeInventory(FIRST, 10)) : fixture.cancel(3, null);
            assertEquals(ManualResearch.Status.CANCELLED, status);
            assertFalse(fixture.runtime.checkpointPending());
            assertEquals(0, fixture.players.get(FIRST).completedLevel(TARGET));
            assertTrue(fixture.players.get(FIRST).rewardClaimsSnapshot().isEmpty());
        }
    }

    @Test
    void pendingCompletionBlocksSourceReductionBeforeMaterialEffectsAndThroughRestoredSettlement() {
        for (var boundary : List.of(ManualResearchSession.Status.MATERIAL_COMMIT_PENDING, ManualResearchSession.Status.MATERIAL_COMMITTED)) {
            var fixture = fixture(1);
            fixture.begin();
            var inventory = new FakeInventory(FIRST, 10);
            assertFalse(fixture.runtime.blocksSourceReduction(TARGET, FIRST));
            fixture.checkpoints.failStatus = boundary;
            assertSame(fixture.checkpoints.failure, assertThrows(IllegalStateException.class, () -> fixture.advance(2, inventory)));
            assertNotNull(fixture.session().completionIntent());
            assertTrue(fixture.runtime.blocksSourceReduction(TARGET, FIRST));
            assertTrue(fixture.runtime.blocksSourceReduction(TARGET, SECOND));
            assertTrue(fixture.runtime.blocksSourceReduction(TARGET, null));
            assertFalse(fixture.runtime.blocksSourceReduction(TARGET, LATE));
            fixture.runtime.retryCheckpoint();
            fixture.reopen();
            assertTrue(fixture.runtime.blocksSourceReduction(TARGET, FIRST));
            if (boundary == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING) {
                assertEquals(ManualResearch.Status.RECOVERY_PENDING, fixture.advance(3, inventory));
                assertEquals(0, inventory.consumed);
                assertTrue(fixture.runtime.blocksSourceReduction(TARGET, FIRST));
                continue;
            }
            assertEquals(ManualResearch.Status.COMPLETED, fixture.runtime.advance(fixture.entry.key().queueEntryId(),
                context(3, Set.of(FIRST, LATE), Set.of(FIRST, LATE)), inventory));
            assertEquals(1, inventory.consumed);
            assertCompletion(fixture, FIRST, 1);
            assertCompletion(fixture, SECOND, 1);
            assertFalse(fixture.runtime.blocksSourceReduction(TARGET, FIRST));
        }
    }

    private static final class Fixture {
        private final ResearchRegistry registry;
        private final NetworkResourceRegistry resources;
        private final ManualMaterials materials;
        private final Checkpoints checkpoints = new Checkpoints();
        private final Object2ObjectOpenHashMap<UUID, PlayerResearchState> players = new Object2ObjectOpenHashMap<>();
        private final TeamResearchQueue.Entry entry;
        private TeamResearchQueue queue;
        private TeamOwnedResearch team;
        private ManualResearch runtime;

        private Fixture(ResearchRegistry registry, NetworkResourceRegistry resources, ManualMaterials materials) {
            this.registry = registry;
            this.resources = resources;
            this.materials = materials;
            for (var player : List.of(FIRST, SECOND, LATE)) {
                players.put(player, new PlayerResearchState(player));
            }
            queue = new TeamResearchQueue(registry, resources, "team");
            team = new TeamOwnedResearch("team", registry);
            runtime = new ManualResearch(queue, team, players, checkpoints, _ -> materials);
            entry = queue.enqueue(TARGET, 1, FIRST, 1);
        }

        private ManualResearchSession session() {
            return Objects.requireNonNull(runtime.session(entry.key().queueEntryId()));
        }

        private void begin() {
            assertEquals(ManualResearch.Status.STARTED, runtime.begin(entry, FIRST, context(1)));
        }

        private ManualResearch.Status advance(long time, FakeInventory inventory) {
            return runtime.advance(entry.key().queueEntryId(), context(time), inventory);
        }

        private ManualResearch.Status cancel(long time, @Nullable FakeInventory inventory) {
            return runtime.cancelInvalidated(entry.key().queueEntryId(), context(time), inventory);
        }

        private void reopen() {
            var saved = checkpoints.latest;
            assertNotNull(saved);
            queue = TeamResearchQueue.restore(registry, resources, saved.queue());
            team = new TeamOwnedResearch("team", registry);
            team.restore(saved.team());
            for (var player : players.keySet()) {
                var restored = new PlayerResearchState(player);
                var snapshot = checkpoints.playerSaves.get(player);
                if (snapshot != null) {
                    restored.restore(snapshot);
                }
                players.put(player, restored);
            }
            runtime = new ManualResearch(queue, team, players, checkpoints, _ -> materials);
            runtime.restore(saved.sessions());
        }
    }

    private static final class Checkpoints implements ManualResearch.Checkpoint {
        private final ObjectArrayList<ManualResearch.CheckpointState> attempts = new ObjectArrayList<>();
        private final Object2ObjectOpenHashMap<UUID, PlayerResearchState.Snapshot> playerSaves = new Object2ObjectOpenHashMap<>();
        private final IllegalStateException failure = new IllegalStateException("world checkpoint failed");
        @Nullable
        private ManualResearchSession.Status failStatus;
        @Nullable
        private ManualResearch.CheckpointState latest;
        @Nullable
        private Consumer<ManualResearch.CheckpointState> callback;
        private boolean failEmpty;

        @Override
        public void persist(ManualResearch.CheckpointState state) {
            attempts.add(state);
            if (callback != null) {
                callback.accept(state);
            }
            if (failEmpty && state.sessions().isEmpty() || failStatus != null && state.sessions().stream().anyMatch(session -> session.status() == failStatus)) {
                failEmpty = false;
                failStatus = null;
                throw failure;
            }
            latest = state;
            for (var player : state.players()) {
                playerSaves.put(player.playerUuid(), player);
            }
        }
    }

    private static final class FakeInventory implements ManualResearchInventory {
        private final UUID player;
        private final ObjectArrayList<Long> sequences = new ObjectArrayList<>();
        private long amount;
        private int prepared;
        private int consumed;
        private boolean allowConsume = true;
        @Nullable
        private ManualResearchMaterialReceipt receipt;
        @Nullable
        private RuntimeException failCommitted;

        private FakeInventory(UUID player, long amount) {
            this.player = player;
            this.amount = amount;
        }

        @Override
        public UUID playerId() {
            return player;
        }

        @Override
        @Nullable
        public ManualResearchMaterialReceipt find(UUID operationId) {
            return receipt != null && receipt.intent().operationId().equals(operationId) ? receipt : null;
        }

        @Override
        @Nullable
        public ManualResearchMaterialReceipt findBySession(UUID sessionId) {
            return receipt != null && receipt.sessionId().equals(sessionId) ? receipt : null;
        }

        @Override
        public byte[] snapshotInventory() {
            var image = new NBTTagCompound();
            var inventory = new NBTTagList();
            if (amount > 0) {
                var stack = new NBTTagCompound();
                stack.setByte("Slot", (byte) 0);
                stack.setString("id", MATERIAL.getId().toString());
                stack.setByte("Count", (byte) amount);
                stack.setShort("Damage", (short) 0);
                inventory.appendTag(stack);
            }
            image.setTag("inventory", inventory);
            image.setInteger("selectedSlot", 0);
            return ManualResearchMaterialReceiptNbt.encodeInventory(image);
        }

        @Override
        public boolean matches(byte[] expected) {
            return Arrays.equals(expected, snapshotInventory());
        }

        @Override
        public boolean hasMaterials(AEKey2LongMap quantities) {
            return amount >= quantities.getLong(MATERIAL);
        }

        @Override
        public void recordPrepared(ManualResearchMaterialReceipt preparedReceipt) {
            assertNull(receipt);
            assertEquals(ManualResearchMaterialReceipt.Status.PREPARED, preparedReceipt.status());
            receipt = preparedReceipt;
            prepared++;
        }

        @Override
        public boolean consume(ManualResearchMaterialReceipt preparedReceipt, AEKey2LongMap quantities, long worldTime) {
            if (!allowConsume || !matches(preparedReceipt.inventoryBefore()) || !hasMaterials(quantities)) {
                return false;
            }
            assertEquals(1, quantities.size());
            amount -= quantities.getLong(MATERIAL);
            consumed++;
            sequences.add(preparedReceipt.intent().sequence());
            receipt = preparedReceipt.committed(snapshotInventory(), worldTime);
            persistCommitted(preparedReceipt.intent().operationId());
            return true;
        }

        @Override
        public void persistCommitted(UUID operationId) {
            assertEquals(ManualResearchMaterialReceipt.Status.COMMITTED, requireReceipt(operationId).status());
            if (failCommitted != null) {
                var failure = failCommitted;
                failCommitted = null;
                throw failure;
            }
        }

        @Override
        public void confirmSettlement(UUID operationId, long worldTime) {
            var committed = requireReceipt(operationId);
            assertEquals(ManualResearchMaterialReceipt.Status.COMMITTED, committed.status());
            receipt = committed.confirmed(worldTime);
        }

        @Override
        public void removePrepared(UUID operationId) {
            if (receipt == null) {
                return;
            }
            assertEquals(ManualResearchMaterialReceipt.Status.PREPARED, requireReceipt(operationId).status());
            receipt = null;
        }

        @Override
        public void removeConfirmed(UUID operationId) {
            if (receipt == null) {
                return;
            }
            assertEquals(ManualResearchMaterialReceipt.Status.SETTLEMENT_CONFIRMED, requireReceipt(operationId).status());
            receipt = null;
        }

        private ManualResearchMaterialReceipt requireReceipt(UUID operationId) {
            return Objects.requireNonNull(find(operationId));
        }
    }
}
