package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ResearchWorldLevelActionsTest {
    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2), UNKNOWN = new UUID(0, 3);
    private static final ResourceLocation PERSONAL = id("personal"), DIRECT = id("direct"), SPECIAL = id("special"), MANUAL = id("manual"), GROUP = id("group");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("level_actions", name);
    }

    private static OwnerTeamResolution member(UUID owner, String team, UUID... members) {
        return OwnerTeamResolution.resolved(owner, team, OwnerTeamType.PLAYER, new ObjectOpenHashSet<>(ObjectList.of(members)), owner);
    }

    private static ResearchPermissionContext permission(String actorTeam, UUID target, String targetTeam, boolean operator) {
        return new ResearchPermissionContext(ALICE, actorTeam, target, targetTeam, false, false, operator);
    }

    @Test
    void personalSuffixAndAdminAbsoluteRepairPreserveAllClaimsAndOtherMemberCloud() {
        var fixture = new Fixture(_ -> {
        });
        var world = fixture.world;
        var claims = world.player(ALICE).rewardClaimsSnapshot();
        assertEquals(ResearchLevelResult.APPLIED, world.forgetPersonal(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", false), PERSONAL, 4, 5, 11, () -> true));
        assertEquals(3, world.player(ALICE).completedLevel(PERSONAL));
        var cloud = world.captureCloud(member(ALICE, "old", ALICE, BOB), () -> true);
        assertNotNull(cloud);
        assertEquals(5, Objects.requireNonNull(cloud.view()).completedLevel(PERSONAL));
        assertEquals(ResearchLevelResult.STALE_CONTEXT, world.forgetPersonal(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", false), PERSONAL, 1, 5, 12, () -> true));
        assertEquals(ResearchLevelResult.UNCHANGED, world.forgetPersonal(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", false), PERSONAL, 5, 13, () -> true));
        assertEquals(ResearchLevelResult.APPLIED, world.forgetPersonal(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", false), PERSONAL, 1, 14, () -> true));
        assertEquals(0, world.player(ALICE).completedLevel(PERSONAL));
        assertEquals(ResearchLevelResult.APPLIED, world.setPlayerResearchLevel(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", true), PERSONAL, 4, 15, () -> true));
        assertEquals(4, world.player(ALICE).completedLevel(PERSONAL));
        assertEquals(claims, world.player(ALICE).rewardClaimsSnapshot());
        assertEquals(5, world.player(BOB).completedLevel(PERSONAL));
    }

    @Test
    void offlinePlayerAndEmptyHistoricalTeamRepairsUseOnlyTheCorrectSource() {
        var fixture = new Fixture(_ -> {
        });
        var world = fixture.world;
        assertEquals(ResearchLevelResult.APPLIED, world.setPlayerResearchLevel(member(UNKNOWN, "offline", UNKNOWN), permission("old", UNKNOWN, "offline", true), PERSONAL, 2, 11, () -> true));
        assertTrue(world.player(UNKNOWN).rewardClaimsSnapshot().isEmpty());
        assertEquals(ResearchLevelResult.APPLIED, world.setPlayerResearchLevel(member(UNKNOWN, "offline", UNKNOWN), permission("old", UNKNOWN, "offline", true), PERSONAL, 0, 12, () -> true));
        assertEquals(0, world.player(UNKNOWN).completedLevel(PERSONAL));
        var teamPermission = permission("old", null, "historical", true);
        assertEquals(ResearchLevelResult.APPLIED, world.setTeamResearchLevel("historical", Set.of(), teamPermission, DIRECT, 5, 13, () -> true));
        assertEquals(5, fixture.historicalTeam().research().levels().getInt(DIRECT));
        assertTrue(fixture.historicalTeam().knownMembers().isEmpty());
        assertEquals(ResearchLevelResult.APPLIED, world.setTeamResearchLevel("historical", Set.of(), teamPermission, DIRECT, 2, 14, () -> true));
        assertEquals(ResearchLevelResult.APPLIED, world.setTeamResearchLevel("historical", Set.of(), teamPermission, DIRECT, 0, 15, () -> true));
        assertFalse(fixture.historicalTeam().research().levels().containsKey(DIRECT));
        assertEquals(0, world.player(ALICE).completedLevel(DIRECT));
        assertEquals(0, world.player(BOB).completedLevel(DIRECT));
    }

    @Test
    void permissionSourceRangeAndRevokedProofDoNotCreateUnauthorizedTargets() {
        var fixture = new Fixture(_ -> {
        });
        var world = fixture.world;
        var target = member(UNKNOWN, "unseen", UNKNOWN);
        assertEquals(ResearchLevelResult.PERMISSION_DENIED, world.setPlayerResearchLevel(target, permission("old", UNKNOWN, "unseen", false), PERSONAL, 1, 11, () -> true));
        assertEquals(ResearchLevelResult.SOURCE_MISMATCH, world.setPlayerResearchLevel(target, permission("old", UNKNOWN, "unseen", true), DIRECT, 1, 12, () -> true));
        assertEquals(ResearchLevelResult.SOURCE_MISMATCH, world.setPlayerResearchLevel(target, permission("old", UNKNOWN, "unseen", true), SPECIAL, 1, 13, () -> true));
        assertEquals(ResearchLevelResult.OUT_OF_RANGE, world.setPlayerResearchLevel(target, permission("old", UNKNOWN, "unseen", true), PERSONAL, 6, 14, () -> true));
        assertEquals(ResearchLevelResult.STALE_CONTEXT, world.forgetPersonal(member(BOB, "old", ALICE, BOB), permission("old", BOB, "old", true), PERSONAL, 1, 15, () -> true));
        var calls = new AtomicInteger();
        assertEquals(ResearchLevelResult.STALE_CONTEXT, world.setPlayerResearchLevel(target, permission("old", UNKNOWN, "unseen", true), PERSONAL, 1, 16, () -> calls.incrementAndGet() == 1));
        assertEquals(2, world.snapshot().players().size());
        assertEquals(1, world.snapshot().teams().size());
    }

    @Test
    void sourceReservationsSurviveTeamChangeAndUnchangedAdminDoesNotAcquireThem() {
        var fixture = new Fixture(_ -> {
        });
        var world = fixture.world;
        var source = world.player(ALICE);
        var download = UUID.randomUUID();
        assertTrue(source.tryReserveSource(PERSONAL, download));
        var moved = member(ALICE, "new", ALICE);
        assertEquals(ResearchLevelResult.SOURCE_RESERVED, world.forgetPersonal(moved, permission("new", ALICE, "new", false), PERSONAL, 1, 11, () -> true));
        assertEquals(ResearchLevelResult.SOURCE_RESERVED, world.setPlayerResearchLevel(moved, permission("new", ALICE, "new", true), PERSONAL, 2, 12, () -> true));
        assertEquals(ResearchLevelResult.UNCHANGED, world.setPlayerResearchLevel(moved, permission("new", ALICE, "new", true), PERSONAL, 5, 13, () -> true));
        assertTrue(source.sourceReservedBy(PERSONAL, download));
        assertEquals(5, source.completedLevel(PERSONAL));
        assertTrue(source.releaseSource(PERSONAL, download));
        assertEquals(ResearchLevelResult.APPLIED, world.setPlayerResearchLevel(moved, permission("new", ALICE, "new", true), PERSONAL, 2, 14, () -> true));
    }

    @Test
    void unrelatedRewardOrCompletionWriterBlocksTheFullWorldCheckpoint() {
        var fixture = new Fixture(_ -> {
        });
        var world = fixture.world;
        var alice = world.player(ALICE);
        try (var reward = alice.tryRewardSession(new RewardClaim.Key(ALICE, PERSONAL, 1))) {
            assertNotNull(reward);
            assertEquals(ResearchLevelResult.BUSY, world.setPlayerResearchLevel(member(BOB, "old", ALICE, BOB), permission("old", BOB, "old", true), PERSONAL, 1, 11, () -> true));
        }
        var held = alice.prepareCompletion(PERSONAL, true, 5, IntSets.emptySet(), 12);
        assertEquals(ResearchLevelResult.BUSY, world.setTeamResearchLevel("historical", Set.of(), permission("old", null, "historical", true), DIRECT, 1, 12, () -> true));
        held.rollback();
        assertEquals(1, world.snapshot().teams().size());
    }

    @Test
    void precommitAuthorityFaultReleasesPersonalAndTeamWritersWithoutPublishing() {
        var fixture = new Fixture(_ -> {
        });
        var world = fixture.world;
        var personal = member(ALICE, "old", ALICE, BOB);
        var personalPermission = permission("old", ALICE, "old", true);
        var teamPermission = permission("old", null, "historical", true);
        assertEquals(ResearchLevelResult.APPLIED, world.setTeamResearchLevel("historical", Set.of(), teamPermission, DIRECT, 4, 11, () -> true));
        var baseline = world.snapshot();
        var failure = new IllegalStateException("authority lookup failure");
        var personalCalls = new AtomicInteger();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> world.setPlayerResearchLevel(personal, personalPermission, PERSONAL, 2, 12,
            () -> {
                if (personalCalls.incrementAndGet() > 1) throw failure;
                return true;
            })));
        assertFalse(world.player(ALICE).busy());
        assertEquals(baseline.players(), world.snapshot().players());
        assertEquals(baseline.teams(), world.snapshot().teams());
        var teamCalls = new AtomicInteger();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> world.setTeamResearchLevel("historical", Set.of(), teamPermission, DIRECT, 1, 13,
            () -> {
                if (teamCalls.incrementAndGet() > 1) throw failure;
                return true;
            })));
        assertEquals(baseline.players(), world.snapshot().players());
        assertEquals(baseline.teams(), world.snapshot().teams());
        assertFalse(world.savePending());
        assertEquals(ResearchLevelResult.APPLIED, world.setPlayerResearchLevel(personal, personalPermission, PERSONAL, 2, 14, () -> true));
        assertEquals(ResearchLevelResult.APPLIED, world.setTeamResearchLevel("historical", Set.of(), teamPermission, DIRECT, 1, 15, () -> true));
    }

    @Test
    void committedLevelAndExactPendingSaveSurviveWriterFaultAndRetryWithoutReapplying() {
        var reject = new AtomicBoolean(false);
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var failure = new IllegalStateException("checkpoint failure");
        var fixture = new Fixture(snapshot -> {
            writes.add(snapshot);
            if (reject.get()) throw failure;
        });
        var world = fixture.world;
        var source = world.player(ALICE);
        reject.set(true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> world.setPlayerResearchLevel(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", true), PERSONAL, 2, 11, () -> true)));
        assertEquals(2, source.completedLevel(PERSONAL));
        assertFalse(source.busy());
        assertTrue(world.savePending());
        var pending = writes.getLast();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> world.setPlayerResearchLevel(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", true), PERSONAL, 1, 12, () -> true)));
        assertSame(pending, writes.getLast());
        reject.set(false);
        assertEquals(ResearchLevelResult.UNCHANGED, world.setPlayerResearchLevel(member(ALICE, "old", ALICE, BOB), permission("old", ALICE, "old", true), PERSONAL, 2, 12, () -> true));
        assertSame(pending, writes.getLast());
        assertFalse(world.savePending());
        assertEquals(2, source.completedLevel(PERSONAL));
    }

    @SuppressWarnings("ClassCanBeRecord")
    // This fixture initializes a mutable world and original restored material sessions.
    private static final class Fixture {
        private final ResearchWorld world;

        private Fixture(Consumer<ResearchWorldSnapshot> writer) {
            var registry = new ResearchRegistry();
            var event = new RewardChild.Event(id("event"), (_, _) -> {
                fail("Level repair delivered a reward event");
                return RewardEventAction.Result.ALREADY_APPLIED;
            });
            var stage = ResearchLevelStage.builder(1, 5).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build());
            for (int level = 1; level <= 3; level++)
                stage.rewardGroup(level, new RewardGroup(GROUP, ObjectList.of(event)));
            registry.register(ResearchDefinition.builder(PERSONAL).maxLevel(5).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .startCondition(_ -> {
                                                    fail("Level repair ran an admission condition");
                                                    return false;
                                                }).stage(stage.build()).build());
            registry.register(ResearchDefinition.builder(DIRECT).maxLevel(5).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                                .stage(ResearchLevelStage.builder(1, 5).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
            registry.register(ResearchDefinition.builder(SPECIAL).maxLevel(5).specialOnly().deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, 5).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
            registry.register(ResearchDefinition.builder(MANUAL).maxLevel(5).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, 5).durationTicks(1).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
            registry.freeze();
            var claims = new ObjectArrayList<RewardClaim>();
            claims.add(RewardClaim.unclaimed(new RewardClaim.Key(ALICE, PERSONAL, 1), 1));
            var pending = new RewardClaim.Child(new RewardEventKey(ALICE, PERSONAL, 2, GROUP, event.id()), RewardClaim.Kind.EVENT, RewardClaim.ChildStatus.PENDING, Object2ObjectMaps.emptyMap());
            claims.add(RewardClaim.unclaimed(new RewardClaim.Key(ALICE, PERSONAL, 2), 2).select(GROUP, 3, ObjectList.of(pending)));
            var done = new RewardClaim.Child(new RewardEventKey(ALICE, PERSONAL, 3, GROUP, event.id()), RewardClaim.Kind.EVENT, RewardClaim.ChildStatus.COMPLETED, Object2ObjectMaps.emptyMap());
            claims.add(RewardClaim.unclaimed(new RewardClaim.Key(ALICE, PERSONAL, 3), 4).select(GROUP, 5, ObjectList.of(done)).completed(6));
            var alice = new Object2IntOpenHashMap<ResourceLocation>();
            alice.put(PERSONAL, 5);
            alice.put(MANUAL, 3);
            var bob = new Object2IntOpenHashMap<ResourceLocation>();
            bob.put(PERSONAL, 5);
            world = new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, writer);
            var queue = new TeamResearchQueue(registry, "old").snapshot();
            var team = new ResearchWorldSnapshot.Team("old", new ObjectOpenHashSet<>(ObjectList.of(ALICE, BOB)), new ObjectOpenHashSet<>(),
                new TeamOwnedResearch.Snapshot("old", new Object2IntOpenHashMap<>()), queue, ObjectList.of());
            world.restore(new ResearchWorldSnapshot(10, ObjectList.of(new PlayerResearchState.Snapshot(ALICE, alice, claims), new PlayerResearchState.Snapshot(BOB, bob, ObjectList.of())), ObjectList.of(team)));
        }

        private ResearchWorldSnapshot.Team historicalTeam() {
            return world.snapshot().teams().stream().filter(team -> team.teamId().equals("historical")).findFirst().orElseThrow();
        }
    }
}
