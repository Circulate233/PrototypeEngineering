package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchCompletion;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TeamResearchCompletionTest {
    private static final ResourceLocation PERSONAL = new ResourceLocation("test", "personal");
    private static final ResourceLocation TEAM = new ResourceLocation("test", "team");
    private static final UUID FIRST = new UUID(1, 1);
    private static final UUID SECOND = new UUID(1, 2);

    private static ResearchRegistry registry() {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        for (var id : List.of(PERSONAL, TEAM)) {
            var rewards = new RewardGroup(new ResourceLocation("test", "group"), new ObjectArrayList<>(List.of(
                new RewardChild.Event(new ResourceLocation("test", "event"), (_, _) -> {
                    fail("Research completion must not deliver rewards");
                    return RewardEventAction.Result.APPLIED;
                }))));
            registry.register(ResearchDefinition.builder(id).maxLevel(Integer.MAX_VALUE)
                                                .deliveryPolicy(id.equals(TEAM) ? ResearchDownloadDeliveryPolicy.ITEM_ONLY : ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, Integer.MAX_VALUE).researchTier(0)
                                                                         .resourceFormula(ResearchResourceFormula.builder().build()).rewardGroup(2, rewards)
                                                                         .rewardGroup(Integer.MAX_VALUE, rewards).build()).build());
        }
        registry.freeze(resources);
        return registry;
    }

    private static Object2ObjectOpenHashMap<UUID, PlayerResearchState> players() {
        var players = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
        players.put(FIRST, new PlayerResearchState(FIRST));
        players.put(SECOND, new PlayerResearchState(SECOND));
        return players;
    }

    @Test
    void completionCreatesEligibilityForBothPoliciesWithoutDeliveringRewards() {
        var registry = registry();
        var source = new TeamOwnedResearch("team", registry);
        var players = players();
        var completion = new TeamResearchCompletion(registry);
        for (var id : List.of(PERSONAL, TEAM)) {
            var prepared = completion.prepare(id, 2, List.of(FIRST, SECOND), players, source, 5);
            assertNotNull(prepared);
            assertEquals(List.of(FIRST, SECOND), prepared.result().rewardEligiblePlayers());
            assertTrue(players.get(FIRST).busy());
            prepared.commit();
            prepared.complete();
            assertFalse(players.get(FIRST).busy());
            for (var player : players.values()) {
                assertEquals(id.equals(PERSONAL) ? 2 : 0, player.completedLevel(id));
                var claims = player.rewardClaimsSnapshot().stream().filter(claim -> claim.key().researchId().equals(id)).toList();
                assertEquals(1, claims.size());
                assertEquals(2, claims.getFirst().key().level());
                assertEquals(RewardClaim.Status.UNCLAIMED, claims.getFirst().status());
            }
        }
        assertEquals(2, source.completedLevel(TEAM));
        assertEquals(0, source.completedLevel(PERSONAL));
    }

    @Test
    void rollbackRestoresEverySourceAndClaimThenAllowsAnotherCompletion() {
        var registry = registry();
        var source = new TeamOwnedResearch("team", registry);
        var players = players();
        var before = source.snapshot();
        var firstBefore = players.get(FIRST).snapshot();
        var completion = new TeamResearchCompletion(registry);
        var prepared = completion.prepare(TEAM, 2, List.of(FIRST, SECOND), players, source, 5);
        assertNotNull(prepared);
        prepared.commit();
        assertEquals(2, source.completedLevel(TEAM));
        prepared.rollback();
        assertEquals(before, source.snapshot());
        assertEquals(firstBefore, players.get(FIRST).snapshot());
        assertEquals(ReversibleTransaction.Resolution.ROLLED_BACK, prepared.resolution());
        assertThrows(IllegalStateException.class, prepared::complete);
        var retry = completion.prepare(TEAM, 2, List.of(FIRST, SECOND), players, source, 6);
        assertNotNull(retry);
        retry.commit();
        retry.complete();
        assertThrows(IllegalStateException.class, retry::rollback);
    }

    @Test
    void aReservedDownloadSourceBlocksCompletionWithoutTakingOtherPlayerWriters() {
        var registry = registry();
        var source = new TeamOwnedResearch("team", registry);
        var players = players();
        var initial = source.prepareCompletion(TEAM, 2);
        initial.commit();
        initial.complete();
        var download = source.prepareSourceMutation(TEAM, 2, 1);
        assertNull(new TeamResearchCompletion(registry).prepare(TEAM, 2, List.of(FIRST, SECOND), players, source, 8));
        assertFalse(players.get(FIRST).busy());
        assertFalse(players.get(SECOND).busy());
        download.commit();
        download.rollback();
        assertEquals(2, source.completedLevel(TEAM));
        assertFalse(source.busy());
    }

    @Test
    void frozenBaselinesGrantOriginalEligibilityAfterAnotherCompletionWithoutReplayingClaims() {
        var registry = registry();
        var source = new TeamOwnedResearch("team", registry);
        var players = players();
        players.get(FIRST).completeAtLeast(PERSONAL, 3);
        var baselines = new Object2IntOpenHashMap<UUID>();
        baselines.put(FIRST, 0);
        baselines.put(SECOND, 0);
        var completion = new TeamResearchCompletion(registry);
        var prepared = completion.prepareFrozen(PERSONAL, 2, List.of(FIRST, SECOND), players, source, 10, baselines);
        assertNotNull(prepared);
        prepared.commit();
        prepared.complete();
        assertEquals(3, players.get(FIRST).completedLevel(PERSONAL));
        assertEquals(1, players.get(FIRST).rewardClaimsSnapshot().size());
        var retry = completion.prepareFrozen(PERSONAL, 2, List.of(FIRST, SECOND), players, source, 11, baselines);
        assertNotNull(retry);
        assertTrue(retry.result().rewardEligiblePlayers().isEmpty());
        retry.commit();
        retry.complete();
        assertEquals(1, players.get(FIRST).rewardClaimsSnapshot().size());
    }

    @Test
    void sparseRewardsSupportMaxLevelAndMissingEligibilityRepairWithoutLevelSizedAllocation() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var registry = registry();
            var source = new TeamOwnedResearch("team", registry);
            var players = players();
            players.get(FIRST).completeAtLeast(PERSONAL, Integer.MAX_VALUE);
            var prepared = new TeamResearchCompletion(registry).prepare(PERSONAL, Integer.MAX_VALUE,
                List.of(FIRST, SECOND), players, source, 5);
            assertNotNull(prepared);
            prepared.commit();
            prepared.complete();
            assertEquals(2, players.get(FIRST).rewardClaimsSnapshot().size());
            assertEquals(2, players.get(SECOND).rewardClaimsSnapshot().size());
        });
    }

    @Test
    void invalidMembersAndForeignRegistryFailBeforeAnyWriterIsAcquired() {
        var registry = registry();
        var source = new TeamOwnedResearch("team", registry);
        var players = players();
        var completion = new TeamResearchCompletion(registry);
        assertThrows(IllegalArgumentException.class,
            () -> completion.prepare(TEAM, 2, List.of(FIRST, FIRST), players, source, 5));
        assertFalse(source.busy());
        assertFalse(players.get(FIRST).busy());
        assertThrows(IllegalArgumentException.class,
            () -> completion.prepare(TEAM, 2, List.of(FIRST), players, new TeamOwnedResearch("team", registry()), 5));
    }

    @Test
    void savedTeamSourceRoundTripsAndRejectsWrongOwnershipOrDuplicateEntries() {
        var registry = registry();
        var source = new TeamOwnedResearch("team", registry);
        var mutation = source.prepareCompletion(TEAM, 2);
        mutation.commit();
        mutation.complete();
        var tag = TeamOwnedResearch.CODEC.encode(source.snapshot());
        var restored = new TeamOwnedResearch("team", registry);
        restored.restore(TeamOwnedResearch.CODEC.decode(tag));
        assertEquals(2, restored.completedLevel(TEAM));
        var levels = tag.getTagList("Levels", 10);
        levels.appendTag(levels.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> TeamOwnedResearch.CODEC.decode(tag));
        var personal = new Object2IntOpenHashMap<ResourceLocation>();
        personal.put(PERSONAL, 2);
        assertThrows(IllegalArgumentException.class, () -> restored.restore(new TeamOwnedResearch.Snapshot("team", personal)));
        assertEquals(2, restored.completedLevel(TEAM));
    }
}
