package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchStartContext;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchStructuralEligibilityTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    private static void checkBlacklistCascade(ResearchEligibility eligibility, List<UUID> members,
                                              Map<UUID, PlayerResearchState> players, Object2IntOpenHashMap<ResourceLocation> team,
                                              ResearchDefinition gate, ResearchDefinition owned, ResearchDefinition leaf) {
        var blacklist = new ObjectOpenHashSet<>(Set.of(gate.id()));
        var blocked = eligibility.calculateStructural("team", members, players, team, blacklist);
        blacklist.clear();
        for (var research : List.of(gate, owned, leaf)) {
            assertEquals(0, blocked.completedLevel(research.id()));
        }
        assertEquals(Set.of(gate.id()), blocked.blacklistedResearchIds());
        assertFalse(eligibility.structurallyEligible(blocked, gate.id(), 1));
        assertTrue(blocked.startableResearchTargets().isEmpty());
    }

    private static ResearchDefinition.Builder definition(String path, int maxLevel, ResearchDownloadDeliveryPolicy policy) {
        return ResearchDefinition.builder(id(path)).maxLevel(maxLevel).deliveryPolicy(policy);
    }

    private static ResearchLevelStage.Builder stage(int first, int last) {
        return ResearchLevelStage.builder(first, last).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static ResearchRegistry registry(ResearchDefinition... definitions) {
        var registry = new ResearchRegistry();
        for (var definition : definitions) {
            registry.register(definition);
        }
        registry.freeze();
        return registry;
    }

    private static Object2IntOpenHashMap<ResourceLocation> levels() {
        return new Object2IntOpenHashMap<>();
    }

    private static Object2IntOpenHashMap<ResourceLocation> levels(ResourceLocation id, int level) {
        var result = levels();
        result.put(id, level);
        return result;
    }

    private static Reference2DoubleOpenHashMap<NetworkResourceType> resources(long compute) {
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        if (compute > 0) {
            result.put(ComputingResourceFixtures.COMPUTE, compute);
        }
        return result;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("structural_eligibility_test", path);
    }

    @Test
    void structuralProjectionSkipsConditionsAndStillAuthorizesTheActualRequesterThroughCanStart() {
        var calls = new AtomicInteger();
        var seen = new AtomicReference<ResearchStartContext>();
        var target = definition("target", 2, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 2).build())
                                                                                              .startCondition(context -> {
                                                                                                  calls.incrementAndGet();
                                                                                                  seen.set(context);
                                                                                                  return context.networkCenterLevel() >= 2 && context.availableResources().getDouble(ComputingResourceFixtures.COMPUTE) >= 7;
                                                                                              }).build();
        var eligibility = new ResearchEligibility(registry(target));
        var player = new PlayerResearchState(FIRST);
        var players = Map.of(FIRST, player);
        var structural = eligibility.calculateStructural("team", List.of(FIRST), players, levels(), Set.of());
        assertEquals(0, calls.get());
        assertTrue(structural.startableResearchTargets().isEmpty());
        assertTrue(eligibility.structurallyEligible(structural, target.id(), 1));
        assertFalse(eligibility.structurallyEligible(structural, target.id(), 2));
        assertTrue(eligibility.canStart(structural, target.id(), 1, FIRST, resources(7), 2));
        assertEquals(1, calls.get());
        assertEquals(FIRST, seen.get().playerId());

        var preview = eligibility.calculate("team", List.of(FIRST), players, levels(), 2, Set.of(), resources(7));
        assertEquals(2, calls.get());
        assertNull(seen.get().playerId());
        assertTrue(preview.isStartable(target.id()));
        assertEquals(structural.sharedLevels(), preview.sharedLevels());
        assertEquals(structural.members(), preview.members());
        assertEquals(structural.blacklistedResearchIds(), preview.blacklistedResearchIds());
    }

    @Test
    void structuralProjectionRemainsUsableWhenTheAnonymousPreviewConditionThrows() {
        var failure = new IllegalStateException("preview condition failed");
        var calls = new AtomicInteger();
        var target = definition("throwing", 1, ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage(1, 1).build())
                                                                                        .startCondition(_ -> {
                                                                                            calls.incrementAndGet();
                                                                                            throw failure;
                                                                                        }).build();
        var eligibility = new ResearchEligibility(registry(target));
        var structural = eligibility.calculateStructural("team", List.of(), Map.of(), levels(), Set.of());
        assertTrue(eligibility.structurallyEligible(structural, target.id(), 1));
        assertEquals(0, calls.get());
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> eligibility.calculate("team", List.of(), Map.of(), levels(), 0, Set.of(), resources(0))));
        assertEquals(1, calls.get());
    }

    @Test
    void prerequisitesAndBlacklistCascadeAcrossBothSourcesUsingOnlyCapturedMembers() {
        var gate = definition("gate", 2, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 2).build()).build();
        var owned = definition("owned", 5, ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            .stage(stage(1, 2).prerequisite(gate.id(), 1).build()).stage(stage(3, 5).prerequisite(gate.id(), 2).build()).build();
        var leaf = definition("leaf", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
            .stage(stage(1, 1).prerequisite(owned.id(), 3).build()).build();
        var eligibility = new ResearchEligibility(registry(gate, owned, leaf));
        var first = new PlayerResearchState(FIRST);
        first.completeAtLeast(gate.id(), 1);
        first.completeAtLeast(leaf.id(), 1);
        var second = new PlayerResearchState(SECOND);
        second.completeAtLeast(gate.id(), 2);
        var players = Map.of(FIRST, first, SECOND, second);
        var team = levels(owned.id(), 5);
        var members = new ObjectArrayList<>(List.of(FIRST));
        var partial = eligibility.calculateStructural("team", members, players, team, Set.of());
        assertEquals(1, partial.completedLevel(gate.id()));
        assertEquals(2, partial.completedLevel(owned.id()));
        assertEquals(0, partial.completedLevel(leaf.id()));
        assertEquals(Set.of(FIRST), partial.members());
        assertFalse(eligibility.structurallyEligible(partial, owned.id(), 3));
        members.add(SECOND);
        var complete = eligibility.calculateStructural("team", members, players, team, Set.of());
        assertEquals(2, complete.completedLevel(gate.id()));
        assertEquals(5, complete.completedLevel(owned.id()));
        assertEquals(1, complete.completedLevel(leaf.id()));
        assertEquals(Set.of(FIRST), partial.members());
        checkBlacklistCascade(eligibility, members, players, team, gate, owned, leaf);
        assertEquals(5, team.getInt(owned.id()));
        assertEquals(1, first.completedLevel(leaf.id()));
    }

    @Test
    void structuralProjectionKeepsSourcePolicyAndIdentityValidation() {
        var personal = definition("personal", 2, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage(1, 2).build()).build();
        var owned = definition("owned", 2, ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage(1, 2).build()).build();
        var eligibility = new ResearchEligibility(registry(personal, owned));
        var first = new PlayerResearchState(FIRST);
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculateStructural("team", List.of(), Map.of(), levels(personal.id(), 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculateStructural("team", List.of(), Map.of(), levels(owned.id(), 3), Set.of()));
        first.completeAtLeast(owned.id(), 1);
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculateStructural("team", List.of(FIRST), Map.of(FIRST, first), levels(owned.id(), 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculateStructural("team", List.of(FIRST), Map.of(), levels(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculateStructural("team", List.of(FIRST), Map.of(FIRST, new PlayerResearchState(SECOND)), levels(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> eligibility.calculateStructural("team", List.of(), Map.of(), levels(), Set.of(id("unknown"))));
    }
}
