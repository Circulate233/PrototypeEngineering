package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchWorldTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID NEWCOMER = new UUID(0, 3);
    private static final ResourceLocation FIRST = id("first");
    private static final ResourceLocation SECOND = id("second");
    private static final ResourceLocation BASE = id("base");
    private static final ResourceLocation NEED_BASE = id("need_base");
    private static final ResourceLocation EMPTY_MANUAL = id("empty_manual");

    private static TeamResearchStart.Result start(ResearchWorld world, OwnerTeamResolution membership, ResourceLocation id, long time) {
        return world.start(membership, permission(membership.ownerId(), membership.teamId()), id, 1, environment(), time, membership.members(), () -> true);
    }

    private static void completeAliceBase(ResearchWorld world) {
        var mutation = world.player(ALICE).prepareCompletion(BASE, true, 1, new IntOpenHashSet(new int[]{1}), 2);
        mutation.commit();
        mutation.complete();
        world.checkpoint(2);
    }

    private static ResearchWorldSnapshot.Team team(ResearchWorld world, String id) {
        return world.snapshot().teams().stream().filter(team -> team.teamId().equals(id)).findFirst().orElseThrow();
    }

    private static ResearchPermissionContext permission(UUID player, String team) {
        return new ResearchPermissionContext(player, team, null, team, true, false, false);
    }

    private static TeamResearchStart.Environment environment() {
        return new TeamResearchStart.Environment(2, 1, 1, Reference2DoubleMaps.emptyMap());
    }

    private static OwnerTeamResolution membership(UUID player, String team, UUID... members) {
        return OwnerTeamResolution.resolved(player, team, OwnerTeamType.PLAYER, new ObjectOpenHashSet<>(ObjectList.of(members)), Optional.of(player));
    }

    private static OwnerTeamResolution aliceRejected(OwnerTeamResolutionStatus status) {
        return OwnerTeamResolution.rejected(ALICE, status, "test capture rejection");
    }

    private static Fixture fixture(ResearchStartCondition condition) {
        var registry = new ResearchRegistry();
        for (var id : ObjectList.of(FIRST, SECOND)) {
            registry.register(ResearchDefinition.builder(id).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).startCondition(condition)
                                                .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        }
        var group = new RewardGroup(id("reward"), ObjectList.of(new RewardChild.Event(id("event"), (_, _) -> RewardEventAction.Result.ALREADY_APPLIED)));
        registry.register(ResearchDefinition.builder(BASE).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).rewardGroup(1, group).build()).build());
        registry.register(ResearchDefinition.builder(NEED_BASE).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).prerequisite(BASE, 1).build()).build());
        registry.register(ResearchDefinition.builder(EMPTY_MANUAL).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        return new Fixture(registry);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }

    @Test
    void positiveTierAdmissionRunsOnlyTheRealActorConditionAndTickDoesNotRerunIt() {
        var actors = new ObjectArrayList<UUID>();
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var fixture = fixture(context -> {
            actors.add(context.playerId());
            return ALICE.equals(context.playerId());
        });
        var world = fixture.world(writes::add);
        var result = start(world, membership(ALICE, "a", ALICE), FIRST, 1);
        assertEquals(TeamResearchStart.Status.STARTED, result.status());
        assertEquals(ObjectList.of(ALICE), actors);
        assertEquals(result.entry(), team(world, "a").queue().entries().getFirst());
        world.tick(2, Map.of(ALICE, membership(ALICE, "a", ALICE)), Set.of(ALICE), _ -> null, () -> true);
        assertEquals(ObjectList.of(ALICE), actors);
        assertEquals(1, team(world, "a").queue().entries().size());
        assertEquals(1, writes.size());
    }

    @Test
    void requesterConditionDenialNeverQueuesWork() {
        var calls = new AtomicInteger();
        var world = fixture(context -> {
            calls.incrementAndGet();
            return BOB.equals(context.playerId());
        }).world(_ -> {
        });
        assertEquals(TeamResearchStart.Status.NOT_STARTABLE, start(world, membership(ALICE, "a", ALICE), FIRST, 1).status());
        assertEquals(1, calls.get());
        assertTrue(team(world, "a").queue().entries().isEmpty());
    }

    @Test
    void migrationPrunesOnlyTheOldTeamAndPreservesPersonalLevelsAndRewardEvidence() {
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(_ -> {
        });
        start(world, membership(ALICE, "a", ALICE), FIRST, 1);
        start(world, membership(BOB, "b", BOB), FIRST, 1);
        completeAliceBase(world);
        var alice = world.player(ALICE).snapshot();
        var other = team(world, "b");
        world.tick(3, Map.of(ALICE, membership(ALICE, "c", ALICE), BOB, membership(BOB, "b", BOB)), Set.of(ALICE, BOB), _ -> null, () -> true);
        assertTrue(team(world, "a").queue().entries().isEmpty());
        assertTrue(team(world, "a").knownMembers().isEmpty());
        assertEquals(other, team(world, "b"));
        assertEquals(alice, world.player(ALICE).snapshot());
        assertEquals(Set.of(ALICE), team(world, "c").knownMembers());
    }

    @Test
    void explicitTeamDisappearanceRetainsSourcesAndOtherTeams() {
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(_ -> {
        });
        start(world, membership(ALICE, "a", ALICE), FIRST, 1);
        start(world, membership(BOB, "b", BOB), SECOND, 1);
        completeAliceBase(world);
        var alice = world.player(ALICE).snapshot();
        var other = team(world, "b");
        world.tick(3, Map.of(ALICE, aliceRejected(OwnerTeamResolutionStatus.NO_TEAM), BOB, membership(BOB, "b", BOB)), Set.of(BOB), _ -> null, () -> true);
        assertTrue(team(world, "a").queue().entries().isEmpty());
        assertEquals(other, team(world, "b"));
        assertEquals(alice, world.player(ALICE).snapshot());
        assertEquals(RewardClaim.Status.UNCLAIMED, alice.rewardClaims().getFirst().status());
    }

    @Test
    void unavailableUniversePreservesAllTeamsWithoutPruning() {
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(writes::add);
        start(world, membership(ALICE, "a", ALICE), FIRST, 1);
        start(world, membership(BOB, "b", BOB), SECOND, 1);
        var before = world.snapshot();
        var count = writes.size();
        world.tick(2, Map.of(ALICE, aliceRejected(OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE), BOB, membership(BOB, "b", BOB)), Set.of(ALICE, BOB), _ -> null, () -> true);
        assertEquals(before.teams(), world.snapshot().teams());
        assertEquals(before.players(), world.snapshot().players());
        assertEquals(count, writes.size());
    }

    @Test
    void contradictoryOrIncompletePeerCapturesCannotDissolveATeam() {
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(_ -> {
        });
        start(world, membership(ALICE, "a", ALICE, BOB), FIRST, 1);
        var before = world.snapshot();
        assertThrows(IllegalArgumentException.class, () -> world.tick(2, Map.of(ALICE, membership(ALICE, "a", ALICE, BOB)), Set.of(ALICE, BOB), _ -> null, () -> true));
        assertEquals(before.teams(), world.snapshot().teams());
        assertThrows(IllegalArgumentException.class, () -> world.tick(2, Map.of(ALICE, membership(ALICE, "a", ALICE, BOB), BOB, membership(BOB, "a", BOB)), Set.of(ALICE, BOB), _ -> null, () -> true));
        assertEquals(before.teams(), world.snapshot().teams());
        assertThrows(IllegalArgumentException.class, () -> world.tick(2, Map.of(BOB, membership(ALICE, "a", ALICE)), Set.of(ALICE, BOB), _ -> null, () -> true));
        assertEquals(before.teams(), world.snapshot().teams());
    }

    @Test
    void omittedTrackedOwnersAreNotEvidenceOfTeamDisappearance() {
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(_ -> {
        });
        start(world, membership(ALICE, "a", ALICE), FIRST, 1);
        start(world, membership(BOB, "b", BOB), SECOND, 1);
        var before = world.snapshot();
        assertThrows(IllegalArgumentException.class, () -> world.tick(2, Map.of(), Set.of(), _ -> null, () -> true));
        assertEquals(before.teams(), world.snapshot().teams());
        assertThrows(IllegalArgumentException.class, () -> world.tick(2, Map.of(ALICE, aliceRejected(OwnerTeamResolutionStatus.NO_TEAM)), Set.of(), _ -> null, () -> true));
        assertEquals(before.teams(), world.snapshot().teams());
    }

    @Test
    void failedWriterRetriesItsExactSnapshotBeforeAnyNewAdmission() {
        var calls = new AtomicInteger();
        var fixture = fixture(_ -> {
            calls.incrementAndGet();
            return true;
        });
        var failure = new IllegalStateException("original write failure");
        var reject = new AtomicBoolean(true);
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var world = fixture.world(snapshot -> {
            writes.add(snapshot);
            if (reject.get()) {
                throw failure;
            }
        });
        var membership = membership(ALICE, "a", ALICE);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> start(world, membership, FIRST, 1)));
        assertTrue(world.savePending());
        assertEquals(1, calls.get());
        var pending = writes.getFirst();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> start(world, membership, SECOND, 2)));
        assertSame(pending, writes.getLast());
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, () -> world.player(ALICE));
        reject.set(false);
        assertEquals(TeamResearchStart.Status.STARTED, start(world, membership, SECOND, 2).status());
        assertSame(pending, writes.get(2));
        assertEquals(1, writes.get(2).serverTime());
        assertEquals(2, writes.getLast().serverTime());
        assertEquals(2, team(world, "a").queue().entries().size());
        assertEquals(2, calls.get());
        assertFalse(world.savePending());
    }

    @Test
    void ownerCallbackFailureRetainsItsTickUntilCheckpointAndAllowsALaterTickAndClose() {
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(writes::add);
        var membership = membership(ALICE, "a", ALICE);
        var queued = start(world, membership, FIRST, 1).entry();
        var failure = new IllegalStateException("recoverable owner callback failure");
        var proofCalls = new AtomicInteger();
        var refreshes = new AtomicInteger();
        try (var identities = new MachineIdentityRegistry(() -> {
        });
             var workers = Executors.newSingleThreadExecutor();
             var lane = Executors.newSingleThreadExecutor()) {
            var stations = new ResearchStations(identities, () -> {
            });
            var tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});
            var execution = world.prepareTick(2, Map.of(ALICE, membership), Set.of(ALICE), _ -> null, () -> {
                if (proofCalls.incrementAndGet() == 2) {
                    throw failure;
                }
                return true;
            }, stations, refreshes::incrementAndGet, tasks, PlannedDeadline.after(Duration.ofSeconds(5)));
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> execution.advance(PlannedDeadline.after(Duration.ofSeconds(5)))));
            assertEquals(2, world.serverTime());
            assertTrue(world.busy());
            assertFalse(world.faulted());
            assertFalse(execution.finished());
            assertFalse(world.savePending());
            assertEquals(1, writes.size());
            assertEquals(0, refreshes.get());
            assertThrows(IllegalStateException.class, world::snapshot);
            assertThrows(IllegalStateException.class, () -> world.prepareTick(3, Map.of(ALICE, membership), Set.of(ALICE),
                _ -> null, () -> true, stations, refreshes::incrementAndGet, tasks, PlannedDeadline.after(Duration.ofSeconds(5))));
            world.checkpoint(2);
            execution.awaitExit();
            assertTrue(execution.advance(PlannedDeadline.after(Duration.ofSeconds(5))));
            assertTrue(execution.finished());
            assertFalse(world.busy());
            assertFalse(world.faulted());
            assertEquals(2, proofCalls.get(), "Cleanup must not replay the throwing owner callback");
            assertEquals(2, writes.size());
            assertEquals(world.snapshot(), writes.getLast());
            assertEquals(ObjectList.of(queued), team(world, "a").queue().entries());
            var next = world.prepareTick(3, Map.of(ALICE, membership), Set.of(ALICE), _ -> null, () -> true,
                stations, refreshes::incrementAndGet, tasks, PlannedDeadline.after(Duration.ofSeconds(5)));
            assertTrue(next.advance(PlannedDeadline.after(Duration.ofSeconds(5))));
            assertTrue(next.finished());
            assertFalse(world.busy());
            assertFalse(world.faulted());
            assertEquals(3, world.serverTime());
            assertEquals(ObjectList.of(queued), team(world, "a").queue().entries());
            world.close(4);
            assertEquals(4, writes.getLast().serverTime());
            assertEquals(ObjectList.of(queued), writes.getLast().teams().getFirst().queue().entries());
            assertFalse(world.faulted());
            assertFalse(world.savePending());
            int saves = writes.size();
            world.close(5);
            assertEquals(saves, writes.size());
        }
    }

    @Test
    void writerCallbackReentryIsRejectedWithoutPoisoningTheClosingFlag() {
        var reference = new AtomicReference<ResearchWorld>();
        var callbacks = new AtomicInteger();
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(snapshot -> {
            callbacks.incrementAndGet();
            var owner = reference.get();
            assertThrows(IllegalStateException.class, () -> owner.playerLogin(BOB, snapshot.serverTime()));
            assertThrows(IllegalStateException.class, () -> owner.player(ALICE));
            assertThrows(IllegalStateException.class, () -> owner.close(snapshot.serverTime()));
        });
        reference.set(world);
        world.playerLogin(ALICE, 1);
        world.playerLogin(BOB, 2);
        assertEquals(2, callbacks.get());
        assertEquals(2, world.snapshot().players().size());
    }

    @Test
    void failedCloseRetainsTheOwnerAndAllowsOnlyCheckpointOrCloseRetries() {
        var reject = new AtomicBoolean();
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var failure = new IllegalArgumentException("final write failed");
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(snapshot -> {
            writes.add(snapshot);
            if (reject.get()) {
                throw failure;
            }
        });
        world.playerLogin(ALICE, 1);
        reject.set(true);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> world.close(2)));
        var pending = writes.getLast();
        assertTrue(world.savePending());
        assertThrows(IllegalStateException.class, () -> world.playerLogin(BOB, 3));
        assertThrows(IllegalStateException.class, () -> world.player(ALICE));
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> world.checkpoint(3)));
        assertSame(pending, writes.getLast());
        reject.set(false);
        world.checkpoint(3);
        assertFalse(world.savePending());
        assertThrows(IllegalStateException.class, () -> world.playerLogin(BOB, 4));
        world.close(4);
        var count = writes.size();
        world.close(5);
        assertEquals(count, writes.size());
        assertThrows(IllegalStateException.class, () -> world.checkpoint(5));
    }

    @Test
    void restoredTeamsUseTheSameNewlyCreatedPlayerSource() {
        var fixture = fixture(ResearchStartCondition.ALWAYS_ALLOWED);
        var original = fixture.world(_ -> {
        });
        start(original, membership(ALICE, "a", ALICE), FIRST, 1);
        start(original, membership(BOB, "b", BOB), FIRST, 1);
        var world = fixture.world(_ -> {
        });
        world.restore(original.snapshot());
        world.playerLogin(NEWCOMER, 2);
        var source = world.player(NEWCOMER);
        assertTrue(source.completeAtLeast(BASE, 1));
        world.checkpoint(2);
        assertEquals(TeamResearchStart.Status.STARTED, start(world, membership(ALICE, "a", ALICE, NEWCOMER), NEED_BASE, 3).status());
        assertEquals(TeamResearchStart.Status.STARTED, start(world, membership(BOB, "b", BOB, NEWCOMER), NEED_BASE, 3).status());
        assertSame(source, world.player(NEWCOMER));
        assertEquals(3, world.snapshot().players().size());
        assertEquals(2, team(world, "a").queue().entries().size());
        assertEquals(2, team(world, "b").queue().entries().size());
    }

    @Test
    void invalidTierZeroMaterialsCompensateOnlyTheNewAdmissionEntry() {
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(_ -> {
        });
        var membership = membership(ALICE, "a", ALICE);
        var original = start(world, membership, FIRST, 1).entry();
        assertThrows(IllegalArgumentException.class, () -> start(world, membership, EMPTY_MANUAL, 2));
        assertEquals(ObjectList.of(original), team(world, "a").queue().entries());
        assertTrue(team(world, "a").manualSessions().isEmpty());
        assertEquals(3, team(world, "a").queue().nextEntryId());
        assertFalse(world.savePending());
    }

    @Test
    void staleCaptureDoesNotAdmitOrPruneAndBlacklistPrunesOnlyItsExactTarget() {
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(_ -> {
        });
        var membership = membership(ALICE, "a", ALICE);
        assertEquals(TeamResearchStart.Status.STALE_CONTEXT, world.start(membership, permission(ALICE, "a"), FIRST, 1, environment(), 1, Set.of(ALICE), () -> false).status());
        assertTrue(world.snapshot().teams().isEmpty());
        start(world, membership, FIRST, 1);
        var kept = start(world, membership, SECOND, 1).entry();
        assertEquals(ResearchPermissionPolicy.Decision.ALLOWED, world.setBlacklisted(membership, permission(ALICE, "a"), FIRST, true, 2, () -> true));
        world.tick(3, Map.of(ALICE, membership), Set.of(ALICE), _ -> null, () -> false);
        assertEquals(2, team(world, "a").queue().entries().size());
        world.tick(3, Map.of(ALICE, membership), Set.of(ALICE), _ -> null, () -> true);
        assertEquals(ObjectList.of(kept), team(world, "a").queue().entries());
    }

    @Test
    void ownerThreadAndMonotonicTimelineAreRequired() {
        var world = fixture(ResearchStartCondition.ALWAYS_ALLOWED).world(_ -> {
        });
        world.playerLogin(ALICE, 2);
        assertThrows(IllegalArgumentException.class, () -> world.playerLogin(BOB, 1));
        var async = CompletableFuture.runAsync(() -> world.playerLogin(BOB, 3));
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, async::join).getCause());
        assertEquals(1, world.snapshot().players().size());
    }

    private record Fixture(ResearchRegistry registry) {
        ResearchWorld world(Consumer<ResearchWorldSnapshot> writer) {
            return new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, writer);
        }
    }
}
