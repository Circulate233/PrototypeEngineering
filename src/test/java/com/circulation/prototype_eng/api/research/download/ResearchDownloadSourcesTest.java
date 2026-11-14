package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchCompletion;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.DATA_CENTER_LEVEL_TOO_LOW;
import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.MISSING_DATABASE_RESEARCH_LEVEL;
import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.PERSONAL_LEVEL_INSUFFICIENT;
import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.READY;
import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.SOURCE_BUSY;
import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.SOURCE_CHANGED;
import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.SPECIAL_ONLY_REJECTED;
import static com.circulation.prototype_eng.api.research.download.ResearchDownloadSources.Status.TEAM_LEVEL_INSUFFICIENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ResearchDownloadSourcesTest {
    private static final UUID FIRST = new UUID(1, 1);
    private static final UUID SECOND = new UUID(1, 2);
    private static final UUID THIRD = new UUID(1, 3);
    private static final UUID OPERATION = new UUID(3, 1);
    private static final UUID FOREIGN_OPERATION = new UUID(3, 2);
    private static final ResourceLocation PERSONAL = new ResourceLocation("test_download_sources", "personal");
    private static final ResourceLocation TEAM = new ResourceLocation("test_download_sources", "team");
    private static final ResourceLocation SPECIAL = new ResourceLocation("test_download_sources", "special");
    private static final ResourceLocation OTHER_PERSONAL = new ResourceLocation("test_download_sources", "other_personal");
    private static final ResourceLocation OTHER_TEAM = new ResourceLocation("test_download_sources", "other_team");

    private static ResearchDownloadSources.Prepared ready(ResearchDownloadSources.Preparation preparation) {
        assertEquals(READY, preparation.status());
        return Objects.requireNonNull(preparation.prepared());
    }

    private static OwnerTeamResolution membership() {
        return OwnerTeamResolution.resolved(FIRST, "team", OwnerTeamType.PLAYER,
            new ObjectOpenHashSet<>(ObjectList.of(FIRST, SECOND)), FIRST);
    }

    @Test
    void aggregateChargesEveryCurrentMemberItsOwnCostAndPreservesDatabaseAndHistoricalClaims() {
        var fixture = new Fixture(2);
        fixture.raise(FIRST, 4);
        fixture.raise(SECOND, 3);
        var firstClaims = fixture.players.get(FIRST).rewardClaimsSnapshot();
        var secondClaims = fixture.players.get(SECOND).rewardClaimsSnapshot();
        var database = fixture.database(4);
        var evaluated = fixture.sources.evaluate(fixture.registry.require(PERSONAL), 4, membership(), database, fixture.players, fixture.team);
        assertEquals(READY, evaluated.status());
        var plan = Objects.requireNonNull(evaluated.plan());
        assertEquals(ObjectList.of(new ResearchDownloadSources.MemberChange(FIRST, 4, 2),
            new ResearchDownloadSources.MemberChange(SECOND, 3, 1)), plan.memberChanges());
        assertFalse(fixture.players.get(FIRST).busy(), "Preflight does not acquire any writer");
        var held = ready(fixture.sources.prepare(plan, fixture.players, fixture.team));
        assertTrue(fixture.players.get(FIRST).busy());
        assertTrue(fixture.players.get(SECOND).busy());
        assertNull(new TeamResearchCompletion(fixture.registry)
            .prepare(PERSONAL, 4, ObjectList.of(FIRST, SECOND), fixture.players, fixture.team, 8));
        assertThrows(IllegalStateException.class, () -> fixture.players.get(FIRST).prepareForgetResearch(PERSONAL, 1));
        held.commit();
        assertEquals(2, fixture.players.get(FIRST).completedLevel(PERSONAL));
        assertEquals(1, fixture.players.get(SECOND).completedLevel(PERSONAL));
        assertEquals(firstClaims, fixture.players.get(FIRST).rewardClaimsSnapshot());
        assertEquals(secondClaims, fixture.players.get(SECOND).rewardClaimsSnapshot());
        assertEquals(4, database.storedResearch().getInt(PERSONAL));
        assertEquals(0, fixture.team.completedLevel(PERSONAL));
        assertSame(fixture.players.get(FIRST).snapshot(), held.snapshot(fixture.players.get(FIRST)));
        held.complete();
        assertFalse(fixture.players.get(FIRST).busy());
        assertFalse(held.owns(fixture.players.get(FIRST)));
        assertThrows(IllegalStateException.class, held::rollback);
        assertEquals(READY, fixture.sources.verifyCommitted(plan, fixture.players, fixture.team));
        assertEquals(2, fixture.players.get(FIRST).completedLevel(PERSONAL), "Committed recovery does not deduct again");
    }

    @Test
    void oneInsufficientOrMissingMemberRejectsTheWholeDownloadWithoutBorrowingAnySource() {
        var fixture = new Fixture(2);
        fixture.raise(FIRST, 4);
        fixture.raise(SECOND, 1);
        assertEquals(PERSONAL_LEVEL_INSUFFICIENT, fixture.evaluatePersonal(4).status());
        assertFalse(fixture.players.get(FIRST).busy());
        assertEquals(4, fixture.players.get(FIRST).completedLevel(PERSONAL));
        fixture.players.remove(SECOND);
        assertEquals(PERSONAL_LEVEL_INSUFFICIENT, fixture.evaluatePersonal(4).status());
        assertFalse(fixture.players.get(FIRST).busy());
    }

    @Test
    void realDatabaseStoredLevelAndBusySourcesAreCheckedBeforePreparation() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var definition = fixture.registry.require(PERSONAL);
        assertEquals(MISSING_DATABASE_RESEARCH_LEVEL,
            fixture.sources.evaluate(definition, 3, membership(), null, fixture.players, fixture.team).status());
        assertEquals(MISSING_DATABASE_RESEARCH_LEVEL,
            fixture.sources.evaluate(definition, 3, membership(), fixture.database(2), fixture.players, fixture.team).status());
        var foreign = fixture.players.get(SECOND).prepareSourceMutation(PERSONAL, 3, 2);
        try {
            assertEquals(SOURCE_BUSY, fixture.evaluatePersonal(3).status());
            assertFalse(fixture.players.get(FIRST).busy());
        } finally {
            foreign.rollback();
        }
        assertEquals(SPECIAL_ONLY_REJECTED, fixture.sources.evaluate(fixture.registry.require(SPECIAL), 1,
            membership(), fixture.database(3), fixture.players, fixture.team).status());
    }

    @Test
    void aBusyLaterMemberReleasesEveryPreviouslyPreparedWriterWithoutTouchingTheForeignWriter() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(3).plan());
        var foreign = fixture.players.get(SECOND).prepareSourceMutation(PERSONAL, 3, 2);
        try {
            assertEquals(SOURCE_BUSY, fixture.sources.prepare(plan, fixture.players, fixture.team).status());
            assertFalse(fixture.players.get(FIRST).busy(), "The earlier uncommitted transaction must also roll back");
            assertTrue(fixture.players.get(SECOND).busy());
            assertTrue(foreign.owns(fixture.players.get(SECOND)));
            assertEquals(3, fixture.players.get(FIRST).completedLevel(PERSONAL));
        } finally {
            foreign.rollback();
        }
        var retry = fixture.players.get(FIRST).prepareSourceMutation(PERSONAL, 3, 2);
        assertNotNull(retry);
        retry.rollback();
    }

    @Test
    void aProviderFailureAfterPreparingAnEarlierMemberPropagatesAndCleansUncommittedWriters() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(3).plan());
        var failure = new IllegalStateException("Player table failed at the second frozen source");
        Map<UUID, PlayerResearchState> failing = new AbstractMap<>() {
            @Override
            public PlayerResearchState get(Object key) {
                if (SECOND.equals(key)) {
                    assertTrue(fixture.players.get(FIRST).busy());
                    throw failure;
                }
                return fixture.players.get(key);
            }

            @Override
            public @NotNull Set<Entry<UUID, PlayerResearchState>> entrySet() {
                return fixture.players.entrySet();
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.sources.prepare(plan, failing, fixture.team)));
        assertFalse(fixture.players.get(FIRST).busy());
        assertFalse(fixture.players.get(SECOND).busy());
        assertEquals(3, fixture.players.get(FIRST).completedLevel(PERSONAL));
        ready(fixture.sources.prepare(plan, fixture.players, fixture.team)).rollback();
    }

    @Test
    void changesAfterFreezingOrAfterRestoringTheMaterialCommittedPlanNeverOverwriteNewerSources() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 2);
        fixture.raise(SECOND, 2);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(2).plan());
        fixture.raise(FIRST, 3);
        assertEquals(SOURCE_CHANGED, fixture.sources.prepare(plan, fixture.players, fixture.team).status());
        assertFalse(fixture.players.get(SECOND).busy());
        var saved = fixture.sources.codec().encode(plan);
        var afterMaterialPlan = fixture.sources.codec().decode(saved);
        fixture.raise(SECOND, 3);
        assertEquals(SOURCE_CHANGED, fixture.sources.prepare(afterMaterialPlan, fixture.players, fixture.team).status());
        assertEquals(3, fixture.players.get(FIRST).completedLevel(PERSONAL));
        assertEquals(3, fixture.players.get(SECOND).completedLevel(PERSONAL));
    }

    @Test
    void aChangedLaterSourceReleasesTheEarlierPreparedWriterAndKeepsTheNewerLevel() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(3).plan());
        fixture.raise(SECOND, 4);
        assertEquals(SOURCE_CHANGED, fixture.sources.prepare(plan, fixture.players, fixture.team).status());
        assertFalse(fixture.players.get(FIRST).busy());
        assertFalse(fixture.players.get(SECOND).busy());
        assertEquals(3, fixture.players.get(FIRST).completedLevel(PERSONAL));
        assertEquals(4, fixture.players.get(SECOND).completedLevel(PERSONAL));
    }

    @Test
    void heldSnapshotsBelongToExactObjectsAndRollbackRetainsTheOriginalSnapshotAndClaims() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 2);
        var first = fixture.players.get(FIRST);
        var before = first.snapshot();
        var held = ready(fixture.sources.prepare(Objects.requireNonNull(fixture.evaluatePersonal(3).plan()), fixture.players, fixture.team));
        assertTrue(held.owns(first));
        assertSame(before, held.snapshot(first));
        assertFalse(held.owns(new PlayerResearchState(FIRST)));
        assertThrows(IllegalStateException.class, () -> held.snapshot(new PlayerResearchState(FIRST)));
        assertFalse(held.owns(fixture.team));
        held.commit();
        assertNotSame(before, held.snapshot(first));
        assertEquals(2, held.snapshot(first).completedLevels().getInt(PERSONAL));
        held.rollback();
        assertSame(before, first.snapshot());
        assertEquals(ReversibleTransaction.Resolution.ROLLED_BACK, held.resolution());
        assertFalse(held.owns(first));
        assertThrows(IllegalStateException.class, () -> held.snapshot(first));
        assertThrows(IllegalStateException.class, held::commit);
        assertFalse(first.busy());
    }

    @Test
    void itemOnlyChargesOnlyTheDirectTeamSourceAndHeldZeroLevelRestoresWithoutAnotherCost() {
        var fixture = new Fixture(2);
        var initial = fixture.team.prepareCompletion(TEAM, 2);
        initial.commit();
        initial.complete();
        var claim = fixture.players.get(FIRST).prepareCompletion(TEAM, false, 2, new IntArrayList(new int[]{2}), 5);
        claim.commit();
        claim.complete();
        var personalBefore = fixture.players.get(FIRST).snapshot();
        var evaluated = fixture.sources.evaluate(fixture.registry.require(TEAM), 2, membership(), null, fixture.players, fixture.team);
        var plan = Objects.requireNonNull(evaluated.plan());
        assertEquals(2, plan.teamBeforeLevel());
        assertEquals(0, plan.teamAfterLevel());
        assertTrue(plan.memberChanges().isEmpty());
        var held = ready(fixture.sources.prepare(plan, fixture.players, fixture.team));
        assertTrue(held.owns(fixture.team));
        assertFalse(held.owns(fixture.players.get(FIRST)));
        assertFalse(fixture.players.get(FIRST).busy());
        held.commit();
        var heldTeam = held.snapshot(fixture.team);
        assertTrue(heldTeam.levels().isEmpty());
        assertSame(personalBefore, fixture.players.get(FIRST).snapshot());
        var savedTeam = TeamOwnedResearch.CODEC.encode(heldTeam);
        var savedPlan = fixture.sources.codec().encode(plan);
        held.complete();
        var restored = new TeamOwnedResearch("team", fixture.registry);
        restored.restore(TeamOwnedResearch.CODEC.decode(savedTeam));
        assertEquals(READY, fixture.sources.verifyCommitted(fixture.sources.codec().decode(savedPlan), fixture.players, restored));
        assertEquals(0, restored.completedLevel(TEAM));
        assertFalse(held.owns(fixture.team));
        assertEquals(TEAM_LEVEL_INSUFFICIENT,
            fixture.sources.evaluate(fixture.registry.require(TEAM), 1, membership(), null, fixture.players, restored).status());
    }

    @Test
    void directTeamDownloadRejectsForeignWritersAndRollbackRestoresTheExactFrozenSource() {
        var fixture = new Fixture(1);
        var initial = fixture.team.prepareCompletion(TEAM, 3);
        initial.commit();
        initial.complete();
        var definition = fixture.registry.require(TEAM);
        var plan = Objects.requireNonNull(fixture.sources.evaluate(definition, 3, membership(), null, fixture.players, fixture.team).plan());
        var before = fixture.team.snapshot();
        var foreign = fixture.team.prepareSourceMutation(TEAM, 3, 2);
        try {
            assertEquals(SOURCE_BUSY, fixture.sources.evaluate(definition, 3, membership(), null, fixture.players, fixture.team).status());
            assertEquals(SOURCE_BUSY, fixture.sources.prepare(plan, fixture.players, fixture.team).status());
            assertTrue(foreign.owns(fixture.team));
        } finally {
            foreign.rollback();
        }
        var held = ready(fixture.sources.prepare(plan, fixture.players, fixture.team));
        assertSame(before, held.snapshot(fixture.team));
        var sameIdentity = new TeamOwnedResearch("team", fixture.registry);
        assertFalse(held.owns(sameIdentity));
        assertThrows(IllegalStateException.class, () -> held.snapshot(sameIdentity));
        held.commit();
        assertEquals(2, held.snapshot(fixture.team).levels().getInt(TEAM));
        assertEquals(SOURCE_BUSY, fixture.sources.verifyCommitted(plan, fixture.players, fixture.team));
        held.rollback();
        assertSame(before, fixture.team.snapshot());
        assertFalse(fixture.team.busy());
        assertFalse(held.owns(fixture.team));
        var newer = fixture.team.prepareCompletion(TEAM, 4);
        newer.commit();
        newer.complete();
        assertEquals(SOURCE_CHANGED, fixture.sources.prepare(plan, fixture.players, fixture.team).status());
        assertEquals(4, fixture.team.completedLevel(TEAM));
        assertFalse(fixture.team.busy());
    }

    @Test
    void databaseTierMustCoverTheRequestedResearchTierBeforeAnySourceWriterIsAcquired() {
        var fixture = new Fixture(1, 1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        assertEquals(DATA_CENTER_LEVEL_TOO_LOW, fixture.evaluatePersonal(3).status());
        assertFalse(fixture.players.get(FIRST).busy());
        assertFalse(fixture.players.get(SECOND).busy());
        var database = fixture.database(3);
        var sufficient = new DataCenterResearchSnapshot(database.dataCenterId(), 1, database.capacity(),
            database.revision(), database.storedResearch());
        assertEquals(READY, fixture.sources.evaluate(fixture.registry.require(PERSONAL), 3, membership(),
            sufficient, fixture.players, fixture.team).status());
        assertEquals(3, fixture.players.get(FIRST).completedLevel(PERSONAL));
        assertEquals(3, fixture.players.get(SECOND).completedLevel(PERSONAL));
    }

    @Test
    void aDecodedPlanKeepsItsOriginalMembersAndCostWhenTheCurrentDefinitionUsesADifferentCost() {
        var old = new Fixture(2);
        old.raise(FIRST, 4);
        old.raise(SECOND, 3);
        var saved = old.sources.codec().encode(Objects.requireNonNull(old.evaluatePersonal(4).plan()));
        var now = new Fixture(1);
        now.raise(FIRST, 4);
        now.raise(SECOND, 3);
        var extra = new PlayerResearchState(THIRD);
        extra.completeAtLeast(PERSONAL, 4);
        now.players.put(THIRD, extra);
        var plan = now.sources.codec().decode(saved);
        assertEquals(2, plan.cost());
        assertEquals(ObjectList.of(FIRST, SECOND), plan.members());
        var held = ready(now.sources.prepare(plan, now.players, now.team));
        held.commit();
        held.complete();
        assertEquals(2, now.players.get(FIRST).completedLevel(PERSONAL));
        assertEquals(1, now.players.get(SECOND).completedLevel(PERSONAL));
        assertEquals(4, extra.completedLevel(PERSONAL));
        assertEquals(READY, now.sources.verifyCommitted(plan, now.players, now.team));
    }

    @Test
    void zeroCostMissingPersonalSourcesRemainAbsentWhileTheFrozenDownloadIsPreparedAndCompleted() {
        var fixture = new Fixture(0);
        fixture.players.remove(SECOND);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(2).plan());
        var held = ready(fixture.sources.prepare(plan, fixture.players, fixture.team));
        held.commit();
        held.complete();
        assertFalse(fixture.players.containsKey(SECOND));
        assertEquals(0, fixture.players.get(FIRST).completedLevel(PERSONAL));
        assertEquals(READY, fixture.sources.verifyCommitted(plan, fixture.players, fixture.team));
    }

    @Test
    void reservationsBlockOnlyTheirResearchAndRemainAbsentFromSourceSnapshots() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(3).plan());
        var before = fixture.players.get(FIRST).snapshot();
        assertEquals(READY, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertEquals(READY, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        var first = fixture.players.get(FIRST);
        assertFalse(first.busy());
        assertSame(before, first.snapshot());
        assertNull(first.tryPrepareCompletion(PERSONAL, true, 4, new IntArrayList(), 0));
        assertNull(first.tryPrepareForgetResearch(PERSONAL, 2));
        assertNull(first.tryPrepareSourceMutation(PERSONAL, 3, 2, null));
        assertFalse(first.completeAtLeast(PERSONAL, 4));
        assertThrows(IllegalStateException.class, () -> first.prepareCompletion(PERSONAL, true, 4, new IntArrayList(), 0));
        assertThrows(IllegalStateException.class, () -> first.prepareSourceMutation(PERSONAL, 3, 2));
        assertThrows(IllegalStateException.class, () -> first.prepareForgetResearch(PERSONAL, 2));
        assertThrows(IllegalStateException.class, () -> first.restore(before));
        var completion = new TeamResearchCompletion(fixture.registry);
        assertNull(completion.prepare(PERSONAL, 4, plan.members(), fixture.players, fixture.team, 7));
        var other = Objects.requireNonNull(completion.prepare(OTHER_PERSONAL, 2, plan.members(), fixture.players, fixture.team, 7));
        other.commit();
        other.complete();
        assertEquals(2, first.completedLevel(OTHER_PERSONAL));
        assertEquals(3, first.completedLevel(PERSONAL));
        var restored = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
        for (var member : plan.members()) {
            var detached = new PlayerResearchState(member);
            detached.restore(fixture.players.get(member).snapshot());
            assertFalse(detached.sourceReserved(PERSONAL));
            restored.put(member, detached);
        }
        var restoredPlan = fixture.sources.codec().decode(fixture.sources.codec().encode(plan));
        assertEquals(READY, fixture.sources.reserve(restoredPlan, OPERATION, restored, fixture.team));
        assertFalse(restored.get(FIRST).completeAtLeast(PERSONAL, 4));
        assertEquals(READY, fixture.sources.release(restoredPlan, OPERATION, restored, fixture.team));
        assertEquals(READY, fixture.sources.release(plan, OPERATION, fixture.players, fixture.team));
        assertTrue(first.completeAtLeast(PERSONAL, 4));
    }

    @Test
    void onlyTheReservedOperationCanAcquireAndReservationOutlivesItsShortCommittedWriter() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(3).plan());
        assertEquals(SOURCE_CHANGED, fixture.sources.prepare(plan, OPERATION, fixture.players, fixture.team).status());
        assertEquals(READY, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertEquals(SOURCE_BUSY, fixture.evaluatePersonal(3).status());
        assertEquals(SOURCE_BUSY, fixture.sources.prepare(plan, fixture.players, fixture.team).status());
        assertEquals(SOURCE_BUSY, fixture.sources.prepare(plan, FOREIGN_OPERATION, fixture.players, fixture.team).status());
        var held = ready(fixture.sources.prepare(plan, OPERATION, fixture.players, fixture.team));
        assertEquals(SOURCE_BUSY, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertEquals(SOURCE_BUSY, fixture.sources.release(plan, OPERATION, fixture.players, fixture.team));
        assertEquals(READY, fixture.sources.release(plan, FOREIGN_OPERATION, fixture.players, fixture.team));
        held.commit();
        assertEquals(2, held.snapshot(fixture.players.get(FIRST)).completedLevels().getInt(PERSONAL));
        assertEquals(SOURCE_BUSY, fixture.sources.release(plan, OPERATION, fixture.players, fixture.team));
        held.complete();
        assertFalse(fixture.players.get(FIRST).busy());
        assertTrue(fixture.players.get(FIRST).sourceReservedBy(PERSONAL, OPERATION));
        assertFalse(fixture.players.get(FIRST).completeAtLeast(PERSONAL, 4));
        assertEquals(READY, fixture.sources.release(plan, OPERATION, fixture.players, fixture.team));
        assertEquals(READY, fixture.sources.release(plan, OPERATION, fixture.players, fixture.team));
        assertTrue(fixture.players.get(FIRST).completeAtLeast(PERSONAL, 4));
    }

    @Test
    void partialReservationFailureReleasesOnlyNewEntriesAndKeepsExistingOperationOwners() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(3).plan());
        var first = fixture.players.get(FIRST);
        var second = fixture.players.get(SECOND);
        assertTrue(first.tryReserveSource(PERSONAL, OPERATION));
        assertTrue(second.tryReserveSource(PERSONAL, FOREIGN_OPERATION));
        assertEquals(SOURCE_BUSY, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertTrue(first.sourceReservedBy(PERSONAL, OPERATION));
        assertTrue(second.sourceReservedBy(PERSONAL, FOREIGN_OPERATION));
        assertTrue(first.releaseSource(PERSONAL, OPERATION));
        assertEquals(SOURCE_BUSY, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertFalse(first.sourceReserved(PERSONAL));
        assertTrue(second.sourceReservedBy(PERSONAL, FOREIGN_OPERATION));
        assertTrue(second.releaseSource(PERSONAL, FOREIGN_OPERATION));
        fixture.raise(SECOND, 4);
        assertEquals(SOURCE_CHANGED, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertFalse(first.sourceReserved(PERSONAL));
        assertFalse(second.sourceReserved(PERSONAL));
        assertEquals(4, second.completedLevel(PERSONAL));
    }

    @Test
    void reservationProviderFailureCleansEveryNewEntryAndPreservesTheOriginalFailure() {
        var fixture = new Fixture(1);
        fixture.raise(FIRST, 3);
        fixture.raise(SECOND, 3);
        var plan = Objects.requireNonNull(fixture.evaluatePersonal(3).plan());
        var failure = new IllegalStateException("Player provider failed during reservation");
        Map<UUID, PlayerResearchState> failing = new AbstractMap<>() {
            @Override
            public PlayerResearchState get(Object key) {
                if (SECOND.equals(key)) {
                    assertTrue(fixture.players.get(FIRST).sourceReservedBy(PERSONAL, OPERATION));
                    throw failure;
                }
                return fixture.players.get(key);
            }

            @Override
            public @NotNull Set<Entry<UUID, PlayerResearchState>> entrySet() {
                return fixture.players.entrySet();
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.sources.reserve(plan, OPERATION, failing, fixture.team)));
        assertFalse(fixture.players.get(FIRST).sourceReserved(PERSONAL));
        assertFalse(fixture.players.get(FIRST).busy());
        fixture.players.remove(SECOND);
        assertEquals(SOURCE_CHANGED, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertFalse(fixture.players.get(FIRST).sourceReserved(PERSONAL));
    }

    @Test
    void teamReservationAllowsOtherResearchAndRollbackKeepsTheReservationForRetry() {
        var fixture = new Fixture(1);
        var initial = fixture.team.prepareCompletion(TEAM, 3);
        initial.commit();
        initial.complete();
        var plan = Objects.requireNonNull(fixture.sources.evaluate(fixture.registry.require(TEAM), 3, membership(), null, fixture.players, fixture.team).plan());
        assertEquals(READY, fixture.sources.reserve(plan, OPERATION, fixture.players, fixture.team));
        assertFalse(fixture.team.busy());
        assertNull(fixture.team.tryPrepareCompletion(TEAM, 4));
        assertNull(fixture.team.tryPrepareSourceMutation(TEAM, 3, 2, null));
        assertThrows(IllegalStateException.class, () -> fixture.team.prepareCompletion(TEAM, 4));
        assertThrows(IllegalStateException.class, () -> fixture.team.prepareSourceMutation(TEAM, 3, 2));
        assertThrows(IllegalStateException.class, () -> fixture.team.restore(fixture.team.snapshot()));
        var completion = new TeamResearchCompletion(fixture.registry);
        assertNull(completion.prepare(TEAM, 4, plan.members(), fixture.players, fixture.team, 7));
        var other = Objects.requireNonNull(completion.prepare(OTHER_TEAM, 2, plan.members(), fixture.players, fixture.team, 7));
        other.commit();
        other.complete();
        assertEquals(2, fixture.team.completedLevel(OTHER_TEAM));
        assertEquals(SOURCE_BUSY, fixture.sources.reserve(plan, FOREIGN_OPERATION, fixture.players, fixture.team));
        assertEquals(SOURCE_BUSY, fixture.sources.prepare(plan, fixture.players, fixture.team).status());
        assertEquals(SOURCE_BUSY, fixture.sources.prepare(plan, FOREIGN_OPERATION, fixture.players, fixture.team).status());
        var held = ready(fixture.sources.prepare(plan, OPERATION, fixture.players, fixture.team));
        assertEquals(SOURCE_BUSY, fixture.sources.release(plan, OPERATION, fixture.players, fixture.team));
        held.commit();
        held.rollback();
        assertEquals(3, fixture.team.completedLevel(TEAM));
        assertTrue(fixture.team.sourceReservedBy(TEAM, OPERATION));
        assertFalse(fixture.team.busy());
        assertEquals(READY, fixture.sources.release(plan, OPERATION, fixture.players, fixture.team));
        var next = Objects.requireNonNull(fixture.team.tryPrepareCompletion(TEAM, 4));
        next.commit();
        next.complete();
        assertEquals(4, fixture.team.completedLevel(TEAM));
    }

    @Test
    void lateReservationContentionDuringCompletionReleasesEarlierWritersAndReturnsNormalBusy() {
        var fixture = new Fixture(1);
        Map<UUID, PlayerResearchState> racing = new AbstractMap<>() {
            @Override
            public PlayerResearchState get(Object key) {
                if (SECOND.equals(key) && fixture.players.get(FIRST).busy()) {
                    assertTrue(fixture.players.get(SECOND).tryReserveSource(PERSONAL, FOREIGN_OPERATION));
                }
                return fixture.players.get(key);
            }

            @Override
            public @NotNull Set<Entry<UUID, PlayerResearchState>> entrySet() {
                return fixture.players.entrySet();
            }
        };
        var completion = new TeamResearchCompletion(fixture.registry);
        assertNull(completion.prepare(PERSONAL, 3, ObjectList.of(FIRST, SECOND), racing, fixture.team, 7));
        assertFalse(fixture.players.get(FIRST).busy());
        assertFalse(fixture.players.get(SECOND).busy());
        assertEquals(0, fixture.players.get(FIRST).completedLevel(PERSONAL));
        assertTrue(fixture.players.get(FIRST).rewardClaimsSnapshot().isEmpty());
        assertTrue(fixture.players.get(SECOND).sourceReservedBy(PERSONAL, FOREIGN_OPERATION));
        assertTrue(fixture.players.get(SECOND).releaseSource(PERSONAL, FOREIGN_OPERATION));
    }

    private static final class Fixture {
        private final ResearchRegistry registry = new ResearchRegistry();
        private final Object2ObjectOpenHashMap<UUID, PlayerResearchState> players = new Object2ObjectOpenHashMap<>();
        private final TeamOwnedResearch team;
        private final ResearchDownloadSources sources;

        private Fixture(int cost) {
            this(cost, 0);
        }

        private Fixture(int cost, int researchTier) {
            var reward = new RewardGroup(new ResourceLocation("test_download_sources", "history"),
                new ObjectArrayList<>(ObjectList.of(new RewardChild.Event(new ResourceLocation("test_download_sources", "unused_event"), (_, _) -> {
                    fail("Downloading source levels must not deliver historical rewards");
                    return RewardEventAction.Result.APPLIED;
                }))));
            for (var id : ObjectList.of(PERSONAL, TEAM, SPECIAL, OTHER_PERSONAL, OTHER_TEAM)) {
                registry.register(ResearchDefinition.builder(id).maxLevel(4).itemDownloadLevelCost(cost)
                                                    .specialOnly(id.equals(SPECIAL))
                                                    .deliveryPolicy(id.equals(TEAM) || id.equals(OTHER_TEAM) ? ResearchDownloadDeliveryPolicy.ITEM_ONLY : ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                    .stage(ResearchLevelStage.builder(1, 4).durationTicks(1).researchTier(researchTier)
                                                                             .resourceFormula(ResearchResourceFormula.builder().build()).rewardGroup(2, reward).build()).build());
            }
            registry.freeze();
            players.put(FIRST, new PlayerResearchState(FIRST));
            players.put(SECOND, new PlayerResearchState(SECOND));
            team = new TeamOwnedResearch("team", registry);
            sources = new ResearchDownloadSources(registry);
        }

        private void raise(UUID id, int level) {
            var mutation = players.get(id).prepareCompletion(PERSONAL, true, level,
                level >= 2 ? new IntArrayList(new int[]{2}) : new IntArrayList(), 5);
            mutation.commit();
            mutation.complete();
        }

        private DataCenterResearchSnapshot database(int stored) {
            var levels = new Object2IntOpenHashMap<ResourceLocation>();
            levels.put(PERSONAL, stored);
            return new DataCenterResearchSnapshot(new UUID(2, 1), 0, 4, 0, levels);
        }

        private ResearchDownloadSources.Evaluation evaluatePersonal(int level) {
            return sources.evaluate(registry.require(PERSONAL), level, membership(), database(level), players, team);
        }
    }
}
