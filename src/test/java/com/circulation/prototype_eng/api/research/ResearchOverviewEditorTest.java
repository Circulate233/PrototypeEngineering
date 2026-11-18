package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ResearchOverviewEditorTest {
    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2);
    private static final ResourceLocation BASE = id("base"), TARGET = id("target"), GROUP = id("group");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("overview_editor", path);
    }

    private static OwnerTeamResolution membership() {
        return OwnerTeamResolution.resolved(ALICE, "team", OwnerTeamType.PLAYER, new ObjectOpenHashSet<>(ObjectList.of(ALICE, BOB)), ALICE);
    }

    private static ResearchPermissionContext permission(boolean admin) {
        return new ResearchPermissionContext(ALICE, "team", ALICE, "team", false, false, admin);
    }

    private static ResearchRegistry registry() {
        var registry = new ResearchRegistry();
        var group = new RewardGroup(GROUP, ObjectList.of(new RewardChild.Event(id("event"), (_, _) -> {
            fail("Editor completion delivered a reward callback");
            return RewardEventAction.Result.APPLIED;
        })));
        registry.register(ResearchDefinition.builder(BASE).maxLevel(2).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 2).researchTier(1).durationTicks(80).resourceFormula(ResearchResourceFormula.builder().build())
                                                                     .rewardGroup(1, group).rewardGroup(2, group).build()).build());
        registry.register(ResearchDefinition.builder(TARGET).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .startCondition(_ -> {
                                                fail("Editor invoked a normal start condition");
                                                return false;
                                            })
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(2).durationTicks(600).resourceFormula(ResearchResourceFormula.builder().build())
                                                                     .prerequisite(BASE, 2).rewardGroup(1, group).build()).build());
        registry.register(ResearchDefinition.builder(id("special")).maxLevel(1).specialOnly()
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(1).durationTicks(1)
                                                                     .resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        return registry;
    }

    private static ResearchWorld world(ResearchRegistry registry, Consumer<ResearchWorldSnapshot> writer) {
        var queue = new TeamResearchQueue(registry, "team");
        queue.enqueue(TARGET, 1, ALICE, 1);
        var team = new ResearchWorldSnapshot.Team("team", new ObjectOpenHashSet<>(ObjectList.of(ALICE, BOB)), new ObjectOpenHashSet<>(),
            new TeamOwnedResearch.Snapshot("team", new Object2IntOpenHashMap<>()), queue.snapshot(), ObjectList.of());
        var world = new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, writer);
        world.restore(new ResearchWorldSnapshot(1, ObjectList.of(new PlayerResearchState.Snapshot(ALICE, new Object2IntOpenHashMap<>(), ObjectList.of()),
            new PlayerResearchState.Snapshot(BOB, new Object2IntOpenHashMap<>(), ObjectList.of())), ObjectList.of(team)));
        return world;
    }

    @Test
    void completionFillsPrerequisitesCreatesAllMembersClaimsAndRemovesSatisfiedQueueEntry() {
        var registry = registry();
        var world = world(registry, _ -> {
        });
        assertEquals(ResearchLevelResult.APPLIED, world.completeForEditor(membership(), permission(true), TARGET, 1, 2, Set.of(ALICE, BOB), _ -> null, () -> true));
        var cloud = world.captureCloud(membership(), () -> true);
        assertNotNull(cloud);
        assertNotNull(cloud.view());
        assertEquals(2, cloud.view().completedLevel(BASE));
        assertEquals(1, cloud.view().completedLevel(TARGET));
        for (var player : ObjectList.of(ALICE, BOB)) {
            assertEquals(2, world.player(player).completedLevel(BASE));
            assertEquals(1, world.player(player).completedLevel(TARGET));
            assertEquals(3, world.player(player).rewardClaimsSnapshot().size());
            for (var claim : world.player(player).rewardClaimsSnapshot())
                assertEquals(RewardClaim.Status.UNCLAIMED, claim.status());
        }
        assertTrue(world.snapshot().teams().getFirst().queue().entries().isEmpty());
    }

    @Test
    void operatorRevocationAndSourceReservationLeaveLevelsAndQueueUnchanged() {
        var registry = registry();
        var world = world(registry, _ -> {
        });
        var before = world.snapshot();
        assertEquals(ResearchLevelResult.PERMISSION_DENIED, world.completeForEditor(membership(), permission(false), TARGET, 1, 2, Set.of(ALICE), _ -> null, () -> true));
        assertEquals(ResearchLevelResult.STALE_CONTEXT, world.completeForEditor(membership(), permission(true), TARGET, 1, 2, Set.of(ALICE), _ -> null, () -> false));
        var source = world.player(BOB);
        var reservation = UUID.randomUUID();
        assertTrue(source.tryReserveSource(BASE, reservation));
        assertEquals(ResearchLevelResult.SOURCE_RESERVED, world.completeForEditor(membership(), permission(true), TARGET, 1, 2, Set.of(ALICE), _ -> null, () -> true));
        assertEquals(before.players(), world.snapshot().players());
        assertEquals(before.teams(), world.snapshot().teams());
    }

    @Test
    void teamDebugCompletesEveryMemberThenForgetsLevelsWithoutDuplicatingRewardClaims() {
        var registry = registry();
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var world = world(registry, writes::add);
        assertEquals(ResearchLevelResult.APPLIED, world.setAllForEditor(membership(), permission(true), true, 2, Set.of(ALICE), _ -> null, () -> true));
        assertEquals(1, writes.size());
        for (var member : ObjectList.of(ALICE, BOB)) {
            assertEquals(2, world.player(member).completedLevel(BASE));
            assertEquals(1, world.player(member).completedLevel(TARGET));
            assertEquals(0, world.player(member).completedLevel(id("special")));
            assertEquals(3, world.player(member).rewardClaimsSnapshot().size());
        }
        assertTrue(world.snapshot().teams().getFirst().queue().entries().isEmpty());
        var claims = world.player(BOB).rewardClaimsSnapshot();
        assertEquals(ResearchLevelResult.APPLIED, world.setAllForEditor(membership(), permission(true), false, 3, Set.of(ALICE), _ -> null, () -> true));
        assertEquals(2, writes.size());
        var restored = new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        restored.restore(writes.getLast());
        for (var member : ObjectList.of(ALICE, BOB)) {
            assertEquals(0, restored.player(member).completedLevel(BASE));
            assertEquals(0, restored.player(member).completedLevel(TARGET));
        }
        assertEquals(claims, restored.player(BOB).rewardClaimsSnapshot());
        assertEquals(ResearchLevelResult.APPLIED, restored.setAllForEditor(membership(), permission(true), true, 4, Set.of(ALICE), _ -> null, () -> true));
        assertEquals(claims, restored.player(BOB).rewardClaimsSnapshot());
    }

    @Test
    void teamDebugPrechecksAuthorityAndEverySourceBeforeChangingAnyResearch() {
        var registry = registry();
        var world = world(registry, _ -> fail("Rejected debug operation saved changes"));
        var before = world.snapshot();
        assertEquals(ResearchLevelResult.PERMISSION_DENIED, world.setAllForEditor(membership(), permission(false), true, 2, Set.of(ALICE), _ -> null, () -> true));
        assertEquals(ResearchLevelResult.STALE_CONTEXT, world.setAllForEditor(membership(), permission(true), false, 2, Set.of(ALICE), _ -> null, () -> false));
        assertTrue(world.player(BOB).tryReserveSource(TARGET, UUID.randomUUID()));
        for (boolean complete : new boolean[]{true, false})
            assertEquals(ResearchLevelResult.SOURCE_RESERVED, world.setAllForEditor(membership(), permission(true), complete, 2, Set.of(ALICE), _ -> null, () -> true));
        assertEquals(before.players(), world.snapshot().players());
        assertEquals(before.teams(), world.snapshot().teams());
    }

    @Test
    void failedSaveRetainsCompletedSourcesAndClaimsWithoutReplayingPropagation() {
        var registry = registry();
        var reject = new AtomicBoolean(true);
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var world = world(registry, snapshot -> {
            writes.add(snapshot);
            if (reject.get()) throw new IllegalStateException("save");
        });
        var alice = world.player(ALICE);
        assertThrows(IllegalStateException.class, () -> world.completeForEditor(membership(), permission(true), TARGET, 1, 2, Set.of(ALICE), _ -> null, () -> true));
        assertTrue(world.savePending());
        assertEquals(3, alice.rewardClaimsSnapshot().size());
        var pending = writes.getLast();
        reject.set(false);
        world.checkpoint(3);
        assertSame(pending, writes.get(writes.size() - 2));
        assertEquals(pending.players(), writes.getLast().players());
        assertFalse(world.savePending());
        assertEquals(3, world.player(ALICE).rewardClaimsSnapshot().size());
    }
}
