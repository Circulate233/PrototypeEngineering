package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchStartContext;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ResearchEligibilityTest {
    @Test void personalMaximumUsesCurrentMembersAndTeamOwnedProgressRemainsWithTheTeam() {
        var personal = definition("personal", 5, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 5).build()).build();
        var owned = definition("owned", 5, ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage(1, 5).build()).build();
        var eligibility = new ResearchEligibility(registry(personal, owned));
        var first = player(personal.id(), 2);
        var second = player(personal.id(), 4);
        var outsider = player(personal.id(), 5);
        var players = Map.of(first.playerUuid(), first, second.playerUuid(), second, outsider.playerUuid(), outsider);
        var teamLevels = levels(owned.id(), 3);
        var resources = resources();
        var view = eligibility.calculate("team", List.of(first.playerUuid(), second.playerUuid()), players, teamLevels, 0, Set.of(), resources);
        assertEquals(4, view.completedLevel(personal.id()));
        assertEquals(3, view.completedLevel(owned.id()));
        assertEquals(5, view.startableTargetLevel(personal.id()));
        assertEquals(4, view.startableTargetLevel(owned.id()));
        assertTrue(view.isStartable(personal.id()), "An ordinary always-allowed research need not require a center");
        second.completeAtLeast(personal.id(), 5);
        teamLevels.put(owned.id(), 5);
        assertEquals(4, view.completedLevel(personal.id()));
        assertEquals(3, view.completedLevel(owned.id()));
        var afterDeparture = eligibility.calculate("team", List.of(first.playerUuid()), players, teamLevels, 0, Set.of(), resources);
        assertEquals(2, afterDeparture.completedLevel(personal.id()));
        assertEquals(5, afterDeparture.completedLevel(owned.id()));
        assertEquals(5, second.completedLevel(personal.id()), "Departure changes the projection, not the departing player's source");
        assertThrows(UnsupportedOperationException.class, () -> view.sharedLevels().put(personal.id(), 1));
        assertThrows(UnsupportedOperationException.class, () -> view.sharedLevels().object2IntEntrySet().iterator().next().setValue(1));
        assertThrows(UnsupportedOperationException.class, () -> view.startableResearchTargets().object2IntEntrySet().iterator().next().setValue(1));
    }

    @Test void stageBoundaryFallbackAndBlacklistCascadeAcrossBothSourcesWithoutScanningEveryLevel() {
        var gate = definition("gate", 2, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 2).build()).build();
        var middle = definition("middle", Integer.MAX_VALUE, ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            .stage(stage(1, 4).prerequisite(gate.id(), 1).build())
            .stage(stage(5, Integer.MAX_VALUE).prerequisite(gate.id(), 2).build()).build();
        var leaf = definition("leaf", 3, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
            .stage(stage(1, 3).prerequisite(middle.id(), 5).build()).build();
        var eligibility = new ResearchEligibility(registry(leaf, middle, gate));
        var member = player(gate.id(), 1);
        member.completeAtLeast(leaf.id(), 3);
        var players = Map.of(member.playerUuid(), member);
        var teamLevels = levels(middle.id(), Integer.MAX_VALUE);
        var view = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> eligibility.calculate("team", List.of(member.playerUuid()),
            players, teamLevels, 0, Set.of(), resources()));
        assertEquals(4, view.completedLevel(middle.id()));
        assertEquals(0, view.completedLevel(leaf.id()));
        assertEquals(Integer.MAX_VALUE, teamLevels.getInt(middle.id()));
        assertEquals(3, member.completedLevel(leaf.id()));
        var blacklisted = eligibility.calculate("team", List.of(member.playerUuid()), players, teamLevels, 0, Set.of(gate.id()), resources());
        assertEquals(0, blacklisted.completedLevel(gate.id()));
        assertEquals(0, blacklisted.completedLevel(middle.id()));
        assertEquals(0, blacklisted.completedLevel(leaf.id()));
        assertFalse(blacklisted.isStartable(gate.id()));
        member.completeAtLeast(gate.id(), 2);
        var recovered = eligibility.calculate("team", List.of(member.playerUuid()), players, teamLevels, 0, Set.of(), resources());
        assertEquals(Integer.MAX_VALUE, recovered.completedLevel(middle.id()));
        assertEquals(3, recovered.completedLevel(leaf.id()));
        assertFalse(recovered.isStartable(middle.id()), "A max-int completed level must not overflow into a new target");
        teamLevels.put(middle.id(), 3);
        var insideStage = eligibility.calculate("team", List.of(member.playerUuid()), players, teamLevels, 0, Set.of(), resources());
        assertEquals(3, insideStage.completedLevel(middle.id()), "A satisfied stage must preserve the actual source level");
    }

    @Test void stageMutexBlocksNewTargetsButDoesNotEraseAlreadyCompletedBranches() {
        var firstId = id("first_branch");
        var secondId = id("second_branch");
        var first = definition("first_branch", 3, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
            .stage(stage(1, 1).build()).stage(stage(2, 3).mutex(secondId, 2).build()).build();
        var second = definition("second_branch", 3, ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            .stage(stage(1, 1).build()).stage(stage(2, 3).mutex(firstId, 2).build()).build();
        var eligibility = new ResearchEligibility(registry(first, second));
        var member = player(first.id(), 1);
        var players = Map.of(member.playerUuid(), member);
        var teamLevels = levels(second.id(), 2);
        var view = eligibility.calculate("team", List.of(member.playerUuid()), players, teamLevels, 0, Set.of(), resources());
        assertEquals(1, view.completedLevel(first.id()));
        assertEquals(2, view.completedLevel(second.id()));
        assertFalse(view.isStartable(first.id()));
        assertFalse(eligibility.structurallyEligible(view, first.id(), 2));
        member.completeAtLeast(first.id(), 2);
        var bothCompleted = eligibility.calculate("team", List.of(member.playerUuid()), players, teamLevels, 0, Set.of(), resources());
        assertEquals(2, bothCompleted.completedLevel(first.id()));
        assertEquals(2, bothCompleted.completedLevel(second.id()));
        assertFalse(bothCompleted.isStartable(first.id()));
        assertFalse(bothCompleted.isStartable(second.id()));
    }

    @Test void realRequesterConditionsUseCurrentResourcesAndMembershipRatherThanTeamCandidateCache() {
        var seen = new AtomicReference<ResearchStartContext>();
        var gate = definition("context_gate", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 1).build()).build();
        var member = player(gate.id(), 1);
        var target = definition("context_target", 2, ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            .stage(stage(1, 2).prerequisite(gate.id(), 1).build()).startCondition(context -> {
                seen.set(context);
                return member.playerUuid().equals(context.playerId()) && context.networkCenterLevel() >= 2
                    && context.availableResources().getLong(NetworkResources.COMPUTE) >= 7;
            }).build();
        var eligibility = new ResearchEligibility(registry(gate, target));
        var amount = resources();
        amount.put(NetworkResources.COMPUTE, 7);
        var view = eligibility.calculate("actual_team", List.of(member.playerUuid()), Map.of(member.playerUuid(), member),
            levels(), 2, Set.of(), amount);
        assertFalse(view.isStartable(target.id()), "A player condition must not be fabricated for a team-wide candidate");
        assertNull(seen.get().playerId());
        assertTrue(eligibility.canStart(view, target.id(), 1, member.playerUuid(), amount, 2));
        var captured = seen.get();
        assertEquals(member.playerUuid(), captured.playerId());
        assertEquals("actual_team", captured.teamId());
        assertEquals(target.id(), captured.researchId());
        assertEquals(1, captured.targetLevel());
        assertEquals(1, captured.teamSharedResearchLevels().getInt(gate.id()));
        assertTrue(captured.teamHasNetworkCenter());
        amount.put(NetworkResources.COMPUTE, 1);
        assertEquals(7, captured.availableResources().getLong(NetworkResources.COMPUTE));
        assertFalse(eligibility.canStart(view, target.id(), 1, member.playerUuid(), amount, 2));
        assertThrows(IllegalArgumentException.class, () -> eligibility.canStart(view, target.id(), 1, UUID.randomUUID(), amount, 2));
        assertThrows(NullPointerException.class, () -> eligibility.canStart(view, target.id(), 1, null, amount, 2));
        assertThrows(UnsupportedOperationException.class, () -> captured.teamSharedResearchLevels().object2IntEntrySet().iterator().next().setValue(0));
        assertThrows(UnsupportedOperationException.class, () -> captured.availableResources().object2LongEntrySet().iterator().next().setValue(0));
    }

    @Test void conditionFailurePropagatesButExistingQueueStructuralCheckDoesNotRunItAgain() {
        var failCondition = new AtomicBoolean();
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("real condition failed");
        var definition = definition("condition", 2, ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage(1, 2).build())
            .startCondition(_ -> {
                calls.incrementAndGet();
                if (failCondition.get()) { throw failure; }
                return true;
            }).build();
        var eligibility = new ResearchEligibility(registry(definition));
        var member = new PlayerResearchState(UUID.randomUUID());
        var members = List.of(member.playerUuid());
        var players = Map.of(member.playerUuid(), member);
        var view = eligibility.calculate("team", members, players, levels(), 0, Set.of(), resources());
        assertEquals(1, calls.get());
        failCondition.set(true);
        assertTrue(eligibility.structurallyEligible(view, definition.id(), 1));
        assertFalse(eligibility.structurallyEligible(view, definition.id(), 2));
        assertEquals(1, calls.get());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> eligibility.canStart(view, definition.id(), 1,
            member.playerUuid(), resources(), 0)));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> eligibility.calculate("team", members, players,
            levels(), 0, Set.of(), resources())));
        assertEquals(3, calls.get());
    }

    @Test void sourcePolicySpecialOnlyUnknownAndExcessiveLevelsAreRejectedBeforeProjection() {
        var personal = definition("source_personal", 2, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 2).build()).build();
        var owned = definition("source_owned", 2, ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage(1, 2).build()).build();
        var special = definition("disk_only", 2, ResearchDownloadDeliveryPolicy.ITEM_ONLY).specialOnly().stage(stage(1, 2).build()).build();
        var eligibility = new ResearchEligibility(registry(personal, owned, special));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(), Map.of(), levels(personal.id(), 1), 0, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(), Map.of(), levels(special.id(), 1), 0, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(), Map.of(), levels(owned.id(), 3), 0, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(), Map.of(), levels(id("unknown"), 1), 0, Set.of(), resources()));
        for (var illegalId : List.of(owned.id(), special.id(), id("unknown"))) {
            var member = player(illegalId, 1);
            assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(member.playerUuid()),
                Map.of(member.playerUuid(), member), levels(), 0, Set.of(), resources()));
        }
        var excessive = player(personal.id(), 3);
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(excessive.playerUuid()),
            Map.of(excessive.playerUuid(), excessive), levels(), 0, Set.of(), resources()));
        var clean = eligibility.calculate("team", List.of(), Map.of(), levels(), 0, Set.of(), resources());
        assertFalse(clean.isStartable(special.id()));
        assertTrue(clean.isStartable(personal.id()));
    }

    @Test void frozenDefinitionAuthorityAndExactPlayerIdentityAreRequiredEvenWithoutEligibleTargets() {
        assertThrows(IllegalStateException.class, () -> new ResearchEligibility(new ResearchRegistry()));
        var definition = definition("identity", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 1).build()).build();
        var eligibility = new ResearchEligibility(registry(definition));
        var member = player(definition.id(), 1);
        var memberId = member.playerUuid();
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(memberId), Map.of(), levels(), 0, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(memberId), Map.of(memberId, new PlayerResearchState(UUID.randomUUID())), levels(), 0, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(memberId, memberId), Map.of(memberId, member), levels(), 0, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate(" team ", List.of(), Map.of(), levels(), 0, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(), Map.of(), levels(), -1, Set.of(), resources()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(), Map.of(), levels(), 0, Set.of(id("unknown_blacklist")), resources()));
        var malformedResources = resources();
        malformedResources.put(NetworkResources.COMPUTE, -1);
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculate("team", List.of(memberId), Map.of(memberId, member), levels(), 0, Set.of(), malformedResources));
        var view = eligibility.calculate("team", List.of(memberId), Map.of(memberId, member), levels(), 0, Set.of(), resources());
        var anotherAuthority = new ResearchEligibility(registry(definition));
        assertThrows(IllegalArgumentException.class, () -> anotherAuthority.structurallyEligible(view, definition.id(), 1));
    }

    @Test void resourceSnapshotIsFrozenBeforeTheFirstConditionCanChangeItsOriginalInput() {
        var amount = resources();
        amount.put(NetworkResources.COMPUTE, 7);
        var observed = new AtomicInteger();
        var first = definition("snapshot_first", 1, ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage(1, 1).build())
            .startCondition(_ -> { amount.put(NetworkResources.COMPUTE, 0); return true; }).build();
        var second = definition("snapshot_second", 1, ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage(1, 1).build())
            .startCondition(context -> {
                observed.set(Math.toIntExact(context.availableResources().getLong(NetworkResources.COMPUTE)));
                return observed.get() == 7;
            }).build();
        var eligibility = new ResearchEligibility(registry(first, second));
        TeamResearchView view = eligibility.calculate("team", List.of(), Map.of(), levels(), 0, Set.of(), amount);
        assertEquals(7, observed.get());
        assertTrue(view.isStartable(second.id()));
        assertEquals(0, amount.getLong(NetworkResources.COMPUTE));
    }

    private static ResearchDefinition.Builder definition(String path, int maxLevel, ResearchDownloadDeliveryPolicy policy) {
        return ResearchDefinition.builder(id(path)).maxLevel(maxLevel).deliveryPolicy(policy);
    }

    private static ResearchLevelStage.Builder stage(int first, int last) {
        return ResearchLevelStage.builder(first, last).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static ResearchRegistry registry(ResearchDefinition... definitions) {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        for (var definition : definitions) { registry.register(definition); }
        registry.freeze(resources);
        return registry;
    }

    private static PlayerResearchState player(ResourceLocation id, int level) {
        var state = new PlayerResearchState(UUID.randomUUID());
        state.completeAtLeast(id, level);
        return state;
    }

    private static Object2IntOpenHashMap<ResourceLocation> levels() { return new Object2IntOpenHashMap<>(); }
    private static Object2IntOpenHashMap<ResourceLocation> levels(ResourceLocation id, int level) {
        var result = levels();
        result.put(id, level);
        return result;
    }
    private static Object2LongOpenHashMap<NetworkResourceType> resources() { return new Object2LongOpenHashMap<>(); }
    private static ResourceLocation id(String path) { return new ResourceLocation("research_eligibility_test", path); }
}
