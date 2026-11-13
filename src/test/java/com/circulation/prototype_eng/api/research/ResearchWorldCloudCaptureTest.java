package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER;
import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision.ALLOWED;
import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision.NOT_SERVER_OPERATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ResearchWorldCloudCaptureTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID NEWCOMER = new UUID(0, 3);
    private static final ResourceLocation BASE = id("base");
    private static final ResourceLocation DEPENDENT = id("dependent");
    private static final ResourceLocation OWNED = id("owned");
    private static final ResourceLocation BLOCKED = id("blocked");

    private static ResearchWorld world(ResearchPermissionPolicy permissions, Consumer<ResearchWorldSnapshot> writer) {
        var registry = new ResearchRegistry();
        for (var research : ObjectList.of(BASE, BLOCKED))
            registry.register(ResearchDefinition.builder(research).maxLevel(3)
                                                .deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).startCondition(_ -> fail("Cloud capture ran an admission condition"))
                                                .stage(ResearchLevelStage.builder(1, 3).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.register(ResearchDefinition.builder(DEPENDENT).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).prerequisite(BASE, 2).build()).build());
        registry.register(ResearchDefinition.builder(OWNED).maxLevel(3).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 3).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        var aliceLevels = levels(BASE, 1);
        aliceLevels.put(BLOCKED, 1);
        var bobLevels = levels(BASE, 3);
        bobLevels.put(DEPENDENT, 1);
        var queue = new TeamResearchQueue.Snapshot("a", ObjectList.of(), null, Reference2DoubleMaps.emptyMap(), 1, 0, 0, -1, -1);
        var team = new ResearchWorldSnapshot.Team("a", members(ALICE, BOB), new ObjectOpenHashSet<>(ObjectList.of(BLOCKED)),
            new TeamOwnedResearch.Snapshot("a", levels(OWNED, 2)), queue, ObjectList.of());
        var world = new ResearchWorld(registry, permissions, writer);
        world.restore(new ResearchWorldSnapshot(10, ObjectList.of(new PlayerResearchState.Snapshot(ALICE, aliceLevels, ObjectList.of()),
            new PlayerResearchState.Snapshot(BOB, bobLevels, ObjectList.of())), ObjectList.of(team)));
        return world;
    }

    private static ResearchPermissionContext permission(String team) {
        return new ResearchPermissionContext(ALICE, team, null, team, true, false, false);
    }

    private static OwnerTeamResolution membership(UUID actor, String team, UUID... members) {
        return OwnerTeamResolution.resolved(actor, team, OwnerTeamType.PLAYER, members(members), Optional.of(actor));
    }

    private static ObjectSet<UUID> members(UUID... values) {
        return new ObjectOpenHashSet<>(ObjectList.of(values));
    }

    private static Object2IntMap<ResourceLocation> levels(ResourceLocation research, int level) {
        var result = new Object2IntOpenHashMap<ResourceLocation>();
        result.put(research, level);
        return result;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test_cloud", path);
    }

    @Test
    void currentMembersReplaceHistoricalMembersWithoutCreatingRecordsOrRunningConditions() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> fail("Cloud capture wrote a checkpoint"));
        var before = world.snapshot();
        var capture = Objects.requireNonNull(world.captureCloud(membership(ALICE, "a", ALICE, NEWCOMER), () -> true));
        var view = Objects.requireNonNull(capture.view());
        assertEquals(members(ALICE, NEWCOMER), view.members());
        assertEquals(1, view.completedLevel(BASE));
        assertEquals(2, view.completedLevel(OWNED));
        assertEquals(0, view.completedLevel(DEPENDENT));
        assertEquals(0, view.completedLevel(BLOCKED));
        assertEquals(members(ALICE, BOB), world.snapshot().teams().getFirst().knownMembers());
        assertEquals(before, world.snapshot());
        assertTrue(view.startableResearchTargets().isEmpty());
        assertTrue(capture.current().getAsBoolean());
        assertFalse(world.busy());
    }

    @Test
    void downloadPermissionIsIndependentOfGuiViewPermission() {
        var actions = new ObjectArrayList<ResearchPermissionPolicy.Action>();
        var world = world((action, _) -> {
            actions.add(action);
            return action == DOWNLOAD_RESEARCH_TO_DATA_CENTER ? ALLOWED : NOT_SERVER_OPERATOR;
        }, _ -> fail("Cloud permission wrote a checkpoint"));
        var capture = world.captureCloud(membership(ALICE, "a", ALICE), permission("a"), DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true);
        assertNotNull(capture);
        assertEquals(1, Objects.requireNonNull(capture.view()).completedLevel(BASE));
        assertTrue(capture.current().getAsBoolean());
        assertEquals(ObjectList.of(DOWNLOAD_RESEARCH_TO_DATA_CENTER), actions);
    }

    @Test
    void deniedOperationExposesNeitherLevelsNorUsableProof() {
        var world = world((_, _) -> NOT_SERVER_OPERATOR, _ -> fail("Denied capture wrote a checkpoint"));
        var before = world.snapshot();
        var capture = Objects.requireNonNull(world.captureCloud(membership(ALICE, "a", ALICE), permission("a"),
            DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true));
        assertEquals(NOT_SERVER_OPERATOR, capture.decision());
        assertNull(capture.view());
        assertFalse(capture.current().getAsBoolean());
        assertEquals(before, world.snapshot());
    }

    @Test
    void systemSynchronizationNeverCallsAPlayerPermissionPolicy() {
        var world = world((_, _) -> fail("System synchronization checked player permission"), _ -> {
        });
        var capture = Objects.requireNonNull(world.captureCloud(membership(BOB, "a", ALICE, BOB), () -> true));
        assertEquals(3, Objects.requireNonNull(capture.view()).completedLevel(BASE));
        assertTrue(capture.current().getAsBoolean());
    }

    @Test
    void sourceChangesAfterCaptureInvalidateItsProofAndLeaveItsViewDetached() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var source = world.player(ALICE);
        var membership = membership(ALICE, "a", ALICE);
        var old = Objects.requireNonNull(world.captureCloud(membership, () -> true));
        assertTrue(source.completeAtLeast(BASE, 2));
        assertFalse(old.current().getAsBoolean());
        assertEquals(1, Objects.requireNonNull(old.view()).completedLevel(BASE));
        var fresh = Objects.requireNonNull(world.captureCloud(membership, () -> true));
        assertEquals(2, Objects.requireNonNull(fresh.view()).completedLevel(BASE));
        assertTrue(fresh.current().getAsBoolean());
    }

    @Test
    void originallyMissingPlayerSourceAppearingEmptyStillInvalidatesItsProof() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var membership = membership(ALICE, "a", ALICE, NEWCOMER);
        var capture = Objects.requireNonNull(world.captureCloud(membership, () -> true));
        world.playerLogin(NEWCOMER, 11);
        assertFalse(capture.current().getAsBoolean());
        assertEquals(1, Objects.requireNonNull(capture.view()).completedLevel(BASE));
        assertTrue(Objects.requireNonNull(world.captureCloud(membership, () -> true)).current().getAsBoolean());
    }

    @Test
    void originallyMissingTeamAppearingAndBlacklistChangesInvalidateCapturedClouds() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var newTeam = membership(ALICE, "new", ALICE);
        var missing = Objects.requireNonNull(world.captureCloud(newTeam, () -> true));
        assertEquals(ALLOWED, world.setBlacklisted(newTeam, permission("new"), BASE, false, 11, () -> true));
        assertFalse(missing.current().getAsBoolean());
        var capture = Objects.requireNonNull(world.captureCloud(newTeam, () -> true));
        assertEquals(ALLOWED, world.setBlacklisted(newTeam, permission("new"), BASE, true, 12, () -> true));
        assertFalse(capture.current().getAsBoolean());
        var fresh = Objects.requireNonNull(world.captureCloud(newTeam, () -> true));
        assertEquals(0, Objects.requireNonNull(fresh.view()).completedLevel(BASE));
    }

    @Test
    void permissionCallbackCannotReenterAndBorrowedSourceChangesDiscardItsCapture() {
        var worldRef = new AtomicReference<ResearchWorld>();
        var sourceRef = new AtomicReference<PlayerResearchState>();
        var membership = membership(ALICE, "a", ALICE);
        var world = world((_, _) -> {
            var owner = worldRef.get();
            assertTrue(owner.busy());
            assertThrows(IllegalStateException.class, () -> owner.playerLogin(NEWCOMER, 11));
            assertNull(owner.captureCloud(membership, () -> true));
            sourceRef.get().completeAtLeast(BASE, 2);
            return ALLOWED;
        }, _ -> fail("Permission callback wrote a checkpoint"));
        worldRef.set(world);
        sourceRef.set(world.player(ALICE));
        assertNull(world.captureCloud(membership, permission("a"), DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true));
        assertFalse(world.busy());
    }

    @Test
    void returnedProofGuardsExternalCallbacksAndRejectsChangedExternalMembership() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> fail("Cloud proof wrote a checkpoint"));
        var current = new AtomicBoolean(true);
        var checkReentry = new AtomicBoolean();
        var capture = Objects.requireNonNull(world.captureCloud(membership(ALICE, "a", ALICE), () -> {
            if (checkReentry.get()) assertThrows(IllegalStateException.class, () -> world.playerLogin(NEWCOMER, 11));
            return current.get();
        }));
        checkReentry.set(true);
        assertTrue(capture.current().getAsBoolean());
        assertFalse(world.busy());
        current.set(false);
        assertFalse(capture.current().getAsBoolean());
        assertNull(world.captureCloud(membership(ALICE, "a", ALICE), current::get));
    }

    @Test
    void busySourcesAndRejectedMembershipSkipPermissionAndClosedWorldRevokesProof() {
        var world = world((_, _) -> fail("Unavailable capture checked permission"), _ -> {
        });
        var source = world.player(ALICE);
        var membership = membership(ALICE, "a", ALICE);
        var capture = Objects.requireNonNull(world.captureCloud(membership, () -> true));
        var mutation = source.prepareSourceMutation(BASE, 1, 0);
        try {
            assertFalse(capture.current().getAsBoolean());
            assertNull(world.captureCloud(membership, permission("a"), DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true));
        } finally {
            mutation.rollback();
        }
        assertTrue(capture.current().getAsBoolean());
        var rejected = OwnerTeamResolution.rejected(ALICE, OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE, "Provider unavailable");
        assertNull(world.captureCloud(rejected, permission("a"), DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true));
        world.close(10);
        assertFalse(capture.current().getAsBoolean());
        assertNull(world.captureCloud(membership, permission("a"), DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true));
    }

    @Test
    void pendingSaveIsReadWithoutRetryingItsWriterOrAdvancingTime() {
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var failure = new IllegalStateException("Retained save failure");
        var world = world(ResearchPermissionPolicy.DEFAULT, snapshot -> {
            writes.add(snapshot);
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> world.playerLogin(NEWCOMER, 11)));
        var before = world.snapshot();
        var capture = Objects.requireNonNull(world.captureCloud(membership(ALICE, "a", ALICE), () -> true));
        assertTrue(capture.current().getAsBoolean());
        assertTrue(world.savePending());
        assertEquals(1, writes.size());
        assertEquals(before, world.snapshot());
    }

    @Test
    void retainedWorldTickBlocksCaptureAndOldProofUntilItsOwnerSettles() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var membership = membership(ALICE, "a", ALICE, BOB);
        var capture = Objects.requireNonNull(world.captureCloud(membership, () -> true));
        var failure = new IllegalStateException("Retained research tick");
        try (var identities = new MachineIdentityRegistry(() -> {
        });
             var workers = Executors.newSingleThreadExecutor(); var lane = Executors.newSingleThreadExecutor()) {
            var tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});
            var deadline = PlannedDeadline.after(Duration.ofSeconds(5));
            var execution = world.prepareTick(11, Map.of(ALICE, membership, BOB, membership(BOB, "a", ALICE, BOB)),
                Set.of(ALICE, BOB), _ -> null, () -> {
                    throw failure;
                }, new ResearchStations(identities, () -> {
                }), () -> {
                }, tasks, deadline);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> execution.advance(deadline)));
            assertFalse(capture.current().getAsBoolean());
            assertNull(world.captureCloud(membership, () -> true));
            execution.cancel(Reason.SERVER_STOP);
            execution.awaitExit();
            assertTrue(execution.advance(deadline));
            assertTrue(capture.current().getAsBoolean());
        }
    }

    @Test
    void playerCloudRejectsMismatchedAuthorityAndProofRequiresItsOwnerThread() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var membership = membership(ALICE, "a", ALICE);
        assertThrows(IllegalArgumentException.class, () -> world.captureCloud(membership, permission("other"),
            DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true));
        var otherActor = new ResearchPermissionContext(BOB, "a", null, "a", false, false, true);
        assertThrows(IllegalArgumentException.class, () -> world.captureCloud(membership, otherActor,
            DOWNLOAD_RESEARCH_TO_DATA_CENTER, () -> true));
        var capture = Objects.requireNonNull(world.captureCloud(membership, () -> true));
        var async = CompletableFuture.runAsync(() -> capture.current().getAsBoolean());
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, async::join).getCause());
    }
}
