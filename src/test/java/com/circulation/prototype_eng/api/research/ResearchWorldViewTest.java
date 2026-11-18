package com.circulation.prototype_eng.api.research;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision.ALLOWED;
import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM;
import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision.NOT_TEAM_LEADER;
import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER;
import static com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision.TARGET_PLAYER_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ResearchWorldViewTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID NEWCOMER = new UUID(0, 3);
    private static final ResourceLocation BASE = id("base");
    private static final ResourceLocation DEPENDENT = id("dependent");
    private static final ResourceLocation OWNED = id("owned");
    private static final ResourceLocation REWARD = id("reward");
    private static final ResourceLocation GROUP = id("choice");
    private static final ResourceLocation OTHER_GROUP = id("other_choice");
    private static final NetworkResourceType COMPUTE = ComputingResourceFixtures.network(id("compute"));

    private static ResearchWorld world(ResearchPermissionPolicy permissions, Consumer<ResearchWorldSnapshot> writer) {
        var registry = new ResearchRegistry();
        var ordinary = ResearchResourceFormula.builder().constant(COMPUTE, 10).build();
        registry.register(ResearchDefinition.builder(BASE).maxLevel(3).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .startCondition(_ -> fail("GUI refreshed an external start condition"))
                                            .stage(ResearchLevelStage.builder(1, 3).durationTicks(1).researchTier(1).resourceFormula(ordinary).build()).build());
        registry.register(ResearchDefinition.builder(DEPENDENT).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(1).resourceFormula(ordinary).prerequisite(BASE, 2).build()).build());
        registry.register(ResearchDefinition.builder(OWNED).maxLevel(3).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 3).durationTicks(1).researchTier(1).resourceFormula(ordinary).build()).build());
        var event = new RewardChild.Event(id("event"), (_, _) -> RewardEventAction.Result.ALREADY_APPLIED);
        registry.register(ResearchDefinition.builder(REWARD).maxLevel(3).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 3).durationTicks(1).researchTier(1).resourceFormula(ordinary)
                                                                     .rewardGroup(1, new RewardGroup(GROUP, ObjectList.of(event)))
                                                                     .rewardGroup(1, new RewardGroup(OTHER_GROUP, ObjectList.of(event))).build()).build());
        registry.freeze();
        var child = new RewardClaim.Child(new RewardEventKey(ALICE, REWARD, 1, GROUP, event.id()), RewardClaim.Kind.EVENT,
            RewardClaim.ChildStatus.PENDING, Object2ObjectMaps.emptyMap());
        var aliceClaim = RewardClaim.unclaimed(new RewardClaim.Key(ALICE, REWARD, 1), 1).select(GROUP, 2, ObjectList.of(child));
        var bobClaim = RewardClaim.unclaimed(new RewardClaim.Key(BOB, REWARD, 3), 1);
        var players = ObjectList.of(new PlayerResearchState.Snapshot(ALICE, levels(BASE, 1), ObjectList.of(aliceClaim)),
            new PlayerResearchState.Snapshot(BOB, levels(BASE, 2), ObjectList.of(bobClaim)));
        var queue = queue(registry);
        var team = new ResearchWorldSnapshot.Team("old", members(ALICE, BOB), new ObjectOpenHashSet<>(),
            new TeamOwnedResearch.Snapshot("old", levels(OWNED, 2)), queue, ObjectList.of());
        var world = new ResearchWorld(registry, permissions, writer);
        world.restore(new ResearchWorldSnapshot(10, players, ObjectList.of(team)));
        return world;
    }

    private static TeamResearchQueue.Snapshot queue(ResearchRegistry registry) {
        var first = new TeamResearchQueue.Key(BASE, 3, 1);
        return new TeamResearchQueue.Snapshot("old", ObjectList.of(new TeamResearchQueue.Entry(first, BOB, 2),
            new TeamResearchQueue.Entry(new TeamResearchQueue.Key(DEPENDENT, 1, 2), ALICE, 3)),
            ObjectList.of(new ResearchProgress.Snapshot(first.target(), 0, ImmutableResourceAmounts.empty(), Reference2DoubleMaps.singleton(COMPUTE, 6)),
                new ResearchProgress(registry, new ResearchProgress.Target(DEPENDENT, 1)).snapshot()), 3, 7, 10, 1, 9);
    }

    private static ResearchPermissionContext permission(String team) {
        return new ResearchPermissionContext(ALICE, team, null, team, true, false, false);
    }

    private static OwnerTeamResolution membership(UUID actor, String team, UUID... currentMembers) {
        return OwnerTeamResolution.resolved(actor, team, OwnerTeamType.PLAYER, members(currentMembers), actor);
    }

    private static ObjectSet<UUID> members(UUID... values) {
        return new ObjectOpenHashSet<>(ObjectList.of(values));
    }

    private static Object2IntMap<ResourceLocation> levels(ResourceLocation id, int value) {
        var result = new Object2IntOpenHashMap<ResourceLocation>();
        result.put(id, value);
        return result;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test_view", path);
    }

    @Test
    void currentMembersDetermineEffectiveLevelsAndTargetsWithoutRunningConditionsOrWriting() {
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var world = world(ResearchPermissionPolicy.DEFAULT, writes::add);
        var before = world.snapshot();
        var capture = membership(ALICE, "old", ALICE, NEWCOMER);
        var view = world.project(ALICE, capture, permission("old"), () -> true);

        assertNotNull(view);
        assertEquals(1, view.team().sharedLevels().getInt(BASE));
        assertEquals(2, view.team().sharedLevels().getInt(OWNED));
        assertEquals(2, view.team().structuralTargets().getInt(BASE));
        assertFalse(view.team().structuralTargets().containsKey(DEPENDENT));
        assertEquals(ResearchPlayerView.TeamStatus.AVAILABLE, view.team().status());
        assertEquals(before, world.snapshot());
        assertTrue(writes.isEmpty());
        assertTrue(world.snapshot().players().stream().noneMatch(player -> player.playerUuid().equals(NEWCOMER)));
    }

    @Test
    void currentTeamSelectsItsOwnQueueAndPreservesOnlyTheActorsPermanentRewardChoices() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> fail("View wrote the world"));
        var before = world.snapshot();
        var view = world.project(ALICE, membership(ALICE, "new", ALICE), permission("new"), () -> true);

        assertNotNull(view);
        assertEquals("new", view.team().id());
        assertTrue(view.team().queueRows().isEmpty());
        assertFalse(view.team().sharedLevels().containsKey(OWNED));
        assertFalse(view.personalLevels().containsKey(REWARD));
        assertEquals(1, view.claims().size());
        var claim = view.claims().getFirst();
        assertEquals(REWARD, claim.researchId());
        assertEquals(1, claim.level());
        assertEquals(RewardClaim.Status.CLAIMING, claim.status());
        assertEquals(GROUP, claim.selectedGroupId());
        assertEquals(ObjectList.of(GROUP, OTHER_GROUP), claim.choiceGroupIds());
        assertEquals(before, world.snapshot());
    }

    @Test
    void noTeamOrUnavailableProviderStillReturnsOwnRewardsWithoutHistoricalTeamData() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        for (var status : ObjectList.of(OwnerTeamResolutionStatus.NO_TEAM, OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE)) {
            var rejected = OwnerTeamResolution.rejected(ALICE, status, "Current membership unavailable");
            var view = world.project(ALICE, rejected, permission(null), () -> true);
            assertNotNull(view);
            assertEquals(ALLOWED, view.personalPermission());
            assertEquals(1, view.personalLevels().getInt(BASE));
            assertEquals(1, view.claims().size());
            assertEquals("", view.team().id());
            assertTrue(view.team().sharedLevels().isEmpty());
            assertTrue(view.team().queueRows().isEmpty());
            assertEquals(MISSING_ACTOR_TEAM, view.team().startPermission());
            assertEquals(status == OwnerTeamResolutionStatus.NO_TEAM ? ResearchPlayerView.TeamStatus.MISSING_TEAM
                : ResearchPlayerView.TeamStatus.UNAVAILABLE, view.team().status());
        }
    }

    @Test
    void queueProjectionRetainsOrderTargetsAndActualNumericProgressByResourceId() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var view = world.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> true);
        assertNotNull(view);
        assertEquals(7, view.team().queueEpoch());
        assertEquals(ObjectList.of(new ResearchPlayerView.QueueRow(1, BASE, 3, BOB, 2),
            new ResearchPlayerView.QueueRow(2, DEPENDENT, 1, ALICE, 3)), view.team().queueRows());
        assertEquals(6, view.team().numericProgress().getDouble(COMPUTE));
        assertEquals(1, view.team().numericProgress().size());
        assertTrue(view.team().manualRows().isEmpty());
    }

    @Test
    void manualStationProjectionReadsOnlyItsContributorAndNeverMutatesOrUsesMachineSupply() {
        var manualId = id("manual_inventory_view");
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(manualId).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(10).researchTier(0)
                                                                     .resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var world = new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, writes::add);
        var bobMembership = membership(BOB, "manual_team", ALICE, BOB);
        var bobPermission = new ResearchPermissionContext(BOB, "manual_team", null, "manual_team", true, false, false);
        var started = world.start(bobMembership, bobPermission, manualId, 1,
            new TeamResearchStart.Environment(0, 0, Reference2DoubleMaps.emptyMap()), 0, Set.of(ALICE, BOB), () -> true);
        assertEquals(TeamResearchStart.Status.STARTED, started.status());
        var inventory = (ManualResearchInventory) Proxy.newProxyInstance(ManualResearchInventory.class.getClassLoader(),
            new Class<?>[]{ManualResearchInventory.class}, (_, method, args) -> switch (method.getName()) {
                case "playerId" -> BOB;
                case "availableMaterials" -> {
                    assertTrue(((AEKey2LongMap) args[0]).isEmpty());
                    yield ImmutableResourceAmounts.empty();
                }
                default -> throw new AssertionError("Material projection mutated the contributor: " + method.getName());
            });
        try (var identities = new MachineIdentityRegistry(() -> {
        })) {
            var stations = new ResearchStations(identities, () -> fail("Manual projection queried station inventories"));
            var requested = new ObjectArrayList<UUID>();
            var before = world.snapshot();
            int beforeWrites = writes.size();
            var view = world.stationView(permission("manual_team"), stations, 0, player -> {
                assertEquals(BOB, player);
                requested.add(player);
                return inventory;
            });
            assertNotNull(view);
            assertTrue(view.visible());
            assertEquals(ObjectList.of(BOB), requested);
            assertEquals(before, world.snapshot());
            assertEquals(beforeWrites, writes.size());

            long entryId = view.queue().getFirst().key().queueEntryId();
            assertEquals(ManualResearch.Status.PAUSED, world.pauseManual(bobMembership, BOB, entryId, 1, Set.of(ALICE, BOB), () -> true));
            var paused = world.snapshot();
            int pausedWrites = writes.size();
            var pausedView = world.stationView(permission("manual_team"), stations, 1,
                _ -> fail("Paused manual research queried a player's inventory"));
            assertNotNull(pausedView);
            assertTrue(pausedView.availableMaterials().isEmpty());
            assertEquals(entryId, pausedView.queue().getFirst().key().queueEntryId());
            assertEquals(paused, world.snapshot());
            assertEquals(pausedWrites, writes.size());
        }
    }

    @Test
    void eachPermissionRemovesOnlyItsOwnPayloadAndReportsItsDecision() {
        var denyPersonalAndQueue = world((action, context) -> switch (action) {
            case VIEW_OWN_RESEARCH -> TARGET_PLAYER_MISMATCH;
            case REQUEST_QUEUE_STATE -> NOT_TEAM_MEMBER;
            case START_RESEARCH -> NOT_TEAM_LEADER;
            default -> ResearchPermissionPolicy.DEFAULT.can(action, context);
        }, _ -> {
        });
        var view = denyPersonalAndQueue.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> true);
        assertNotNull(view);
        assertEquals(TARGET_PLAYER_MISMATCH, view.personalPermission());
        assertTrue(view.personalLevels().isEmpty());
        assertTrue(view.claims().isEmpty());
        assertEquals(1, view.team().sharedLevels().getInt(BASE));
        assertEquals(NOT_TEAM_MEMBER, view.team().queuePermission());
        assertEquals(NOT_TEAM_LEADER, view.team().startPermission());
        assertEquals(0, view.team().queueEpoch());
        assertTrue(view.team().queueRows().isEmpty());
        assertTrue(view.team().numericProgress().isEmpty());

        var denyTeamOnly = world((action, context) -> action == ResearchPermissionPolicy.Action.VIEW_TEAM_RESEARCH
            ? NOT_TEAM_MEMBER : ResearchPermissionPolicy.DEFAULT.can(action, context), _ -> {
        });
        var queueView = denyTeamOnly.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> true);
        assertNotNull(queueView);
        assertTrue(queueView.team().sharedLevels().isEmpty());
        assertTrue(queueView.team().structuralTargets().isEmpty());
        assertTrue(queueView.team().blacklist().isEmpty());
        assertEquals(2, queueView.team().queueRows().size());
        assertEquals(1, queueView.claims().size());
    }

    @Test
    void missingPlayerAndTeamAreProjectedAsEmptyWithoutCreatingAnyWorldRecords() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> fail("Read created a checkpoint"));
        var before = world.snapshot();
        var permission = new ResearchPermissionContext(NEWCOMER, "unseen", null, "unseen", false, false, false);
        var view = world.project(NEWCOMER, membership(NEWCOMER, "unseen", NEWCOMER), permission, () -> true);
        assertNotNull(view);
        assertTrue(view.personalLevels().isEmpty());
        assertTrue(view.claims().isEmpty());
        assertEquals(1, view.team().structuralTargets().getInt(BASE));
        assertTrue(view.team().queueRows().isEmpty());
        assertEquals(before, world.snapshot());
    }

    @Test
    void permissionsCannotReenterWorldMutationsAndLostCurrentCaptureIsDiscarded() {
        var worldRef = new AtomicReference<ResearchWorld>();
        var current = new AtomicBoolean(true);
        var world = world((action, context) -> {
            assertTrue(worldRef.get().busy());
            assertThrows(IllegalStateException.class, () -> worldRef.get().playerLogin(NEWCOMER, 11));
            current.set(false);
            return ResearchPermissionPolicy.DEFAULT.can(action, context);
        }, _ -> fail("Permission callback wrote the world"));
        worldRef.set(world);
        var before = world.snapshot();
        assertNull(world.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), current::get));
        assertEquals(before, world.snapshot());
        assertFalse(world.busy());
    }

    @Test
    void borrowedSourceChangesDuringPermissionCallbackCannotPublishAnObsoleteView() {
        var sourceRef = new AtomicReference<PlayerResearchState>();
        var world = world((action, context) -> {
            sourceRef.get().completeAtLeast(BASE, 2);
            return ResearchPermissionPolicy.DEFAULT.can(action, context);
        }, _ -> {
        });
        sourceRef.set(world.player(ALICE));
        assertNull(world.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> true));
        assertEquals(2, sourceRef.get().completedLevel(BASE));
        assertFalse(world.busy());
    }

    @Test
    void busySourcesStaleProofAndClosedWorldSkipCaptureWithoutPermissionCallbacks() {
        var world = world((_, _) -> fail("Unavailable view evaluated permission"), _ -> {
        });
        var source = world.player(BOB);
        var mutation = source.prepareSourceMutation(BASE, 2, 1);
        try {
            assertNull(world.project(ALICE, membership(ALICE, "old", ALICE, BOB), permission("old"), () -> true));
        } finally {
            mutation.rollback();
        }
        assertNull(world.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> false));
        world.close(10);
        assertNull(world.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> true));
    }

    @Test
    void pendingSaveIsReportedWithoutRetryingTheWriterOrAdvancingWorldTime() {
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var failure = new IllegalStateException("Retained checkpoint failure");
        var world = world(ResearchPermissionPolicy.DEFAULT, snapshot -> {
            writes.add(snapshot);
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> world.playerLogin(NEWCOMER, 11)));
        var before = world.snapshot();
        assertTrue(world.savePending());
        var view = world.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> true);
        assertNotNull(view);
        assertTrue(view.savePending());
        assertEquals(11, view.serverTime());
        assertEquals(1, writes.size());
        assertEquals(before, world.snapshot());
    }

    @Test
    void retainedResearchTickPreventsViewCaptureUntilItsOwnerHasSettled() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var capture = membership(ALICE, "old", ALICE, BOB);
        var failure = new IllegalStateException("Retained owner operation");
        try (var identities = new MachineIdentityRegistry(() -> {
        })) {
            var deadline = PlannedDeadline.after(Duration.ofSeconds(5));
            var execution = world.prepareTick(11, Map.of(ALICE, capture, BOB, membership(BOB, "old", ALICE, BOB)),
                Set.of(ALICE, BOB), _ -> null, () -> {
                    throw failure;
                }, new ResearchStations(identities, () -> {
                }),
                () -> {
                }, deadline);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> execution.advance(deadline)));
            assertTrue(world.busy());
            assertNull(world.project(ALICE, capture, permission("old"), () -> true));
            execution.cancel(Reason.SERVER_STOP);
            execution.awaitExit();
            assertTrue(execution.advance(deadline));
            assertNotNull(world.project(ALICE, capture, permission("old"), () -> true));
        }
    }

    @Test
    void ownerIdentityAndPersonalTargetAreValidatedBeforePrivateDataIsRead() {
        var world = world(ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        assertThrows(IllegalArgumentException.class, () -> world.project(ALICE, membership(BOB, "old", BOB), permission("old"), () -> true));
        var otherTarget = new ResearchPermissionContext(ALICE, "old", BOB, "old", true, false, true);
        assertThrows(IllegalArgumentException.class, () -> world.project(ALICE, membership(ALICE, "old", ALICE), otherTarget, () -> true));
        var async = CompletableFuture.runAsync(() -> world.project(ALICE, membership(ALICE, "old", ALICE), permission("old"), () -> true));
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, async::join).getCause());
    }

}
