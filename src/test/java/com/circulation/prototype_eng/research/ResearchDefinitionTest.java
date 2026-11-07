package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchStartContext;
import com.circulation.prototype_eng.api.research.ResearchUnlockQuery;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResearchDefinitionTest {
    @Test void evaluatesStageRelativeDemandExactlyAndEnforcesIndependentSegmentCap() {
        var type = new NetworkResourceType(id("limited"), false, 20);
        var formula = ResearchResourceFormula.builder().affine(type, 100, 10, 20).build();
        assertEquals(100, formula.demandsAt(5, 5).getLong(type));
        assertEquals(130, formula.demandsAt(5, 8).getLong(type));
        assertEquals(20, formula.segmentGrantLimits().getLong(type));
        assertThrows(IllegalArgumentException.class, () -> ResearchResourceFormula.builder().constant(type, 100, 21));
        assertThrows(IllegalArgumentException.class, () -> ResearchResourceFormula.builder().constant(NetworkResources.THREAD, 1, 1));
        var overflow = ResearchResourceFormula.builder().affine(type, Long.MAX_VALUE, 1, 20).build();
        assertThrows(IllegalArgumentException.class, () -> overflow.validateRange(1, 2));
        assertThrows(IllegalArgumentException.class, () -> ResearchResourceFormula.builder().affine(type, 1, -1, 1));
        assertThrows(UnsupportedOperationException.class, () -> formula.baseDemands().object2LongEntrySet().iterator().next().setValue(0));
    }

    @Test void coversAnUnboundedTailWithoutPermittingAnExtraOverlappingMaxLevelStage() {
        var tail = stage(3, Integer.MAX_VALUE).build();
        var definition = definition("tail", Integer.MAX_VALUE).stage(stage(1, 2).build()).stage(tail).build();
        assertSame(tail, definition.stageForLevel(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> definition("overlap", Integer.MAX_VALUE)
            .stage(stage(1, Integer.MAX_VALUE).build()).stage(stage(Integer.MAX_VALUE, Integer.MAX_VALUE).build()).build());
        assertThrows(IllegalArgumentException.class, () -> definition("gap", 3).stage(stage(1, 1).build()).stage(stage(3, 3).build()).build());
        assertThrows(IllegalArgumentException.class, () -> definition.stageForLevel(0));
    }

    @Test void registryPublishesNothingAfterFailedValidationAndAcceptsRepairedReferences() {
        var registry = new ResearchRegistry();
        registry.register(definition("dependent", 1).stage(stage(1, 1).prerequisite(id("missing"), 1).build()).build());
        assertThrows(IllegalArgumentException.class, () -> registry.freeze(resources()));
        assertFalse(registry.isFrozen());
        assertThrows(IllegalStateException.class, registry::all);
        registry.register(definition("missing", 1).stage(stage(1, 1).build()).build());
        registry.freeze(resources());
        assertEquals(2, registry.all().size());
        assertEquals(id("dependent"), registry.require(id("dependent")).id());
        assertThrows(IllegalStateException.class, () -> registry.register(definition("late", 1).stage(stage(1, 1).build()).build()));
    }

    @Test void rejectsCyclesAndAsymmetryAcrossEveryCoveredStage() {
        var cyclic = new ResearchRegistry();
        cyclic.register(definition("a", 1).stage(stage(1, 1).prerequisite(id("b"), 1).build()).build());
        cyclic.register(definition("b", 1).stage(stage(1, 1).prerequisite(id("a"), 1).build()).build());
        assertThrows(IllegalArgumentException.class, () -> cyclic.freeze(resources()));

        var asymmetric = new ResearchRegistry();
        asymmetric.register(definition("a", 3).stage(stage(1, 3).mutex(id("b"), 1).build()).build());
        asymmetric.register(definition("b", 3).stage(stage(1, 1).mutex(id("a"), 1).build()).stage(stage(2, 3).build()).build());
        assertThrows(IllegalArgumentException.class, () -> asymmetric.freeze(resources()));

        var symmetric = new ResearchRegistry();
        symmetric.register(definition("a", Integer.MAX_VALUE).stage(stage(1, Integer.MAX_VALUE).mutex(id("b"), 1).build()).build());
        symmetric.register(definition("b", Integer.MAX_VALUE).stage(stage(1, 2).mutex(id("a"), 1).build())
            .stage(stage(3, Integer.MAX_VALUE).mutex(id("a"), 1).build()).build());
        assertDoesNotThrow(() -> symmetric.freeze(resources()));
    }

    @Test void rejectsUnregisteredOrConflictingComputingMetadata() {
        var unknown = new NetworkResourceType(id("unknown"), false, 0);
        var registry = new ResearchRegistry();
        registry.register(definition("unknown", 1).stage(stage(1, 1)
            .resourceFormula(ResearchResourceFormula.builder().constant(unknown, 1, 1).build()).build()).build());
        assertThrows(IllegalArgumentException.class, () -> registry.freeze(resources()));
        var conflict = new NetworkResourceType(NetworkResources.COMPUTE.id(), false, 20);
        var conflictingRegistry = new ResearchRegistry();
        conflictingRegistry.register(definition("conflict", 1).stage(stage(1, 1)
            .resourceFormula(ResearchResourceFormula.builder().constant(conflict, 1, 1).build()).build()).build());
        assertThrows(IllegalArgumentException.class, () -> conflictingRegistry.freeze(resources()));
    }

    @Test void retainsSparseRewardChoicesAndProtectsEveryPublicEntryFromMutation() {
        var reward = new RewardGroup(id("choice"), List.of(new RewardChild.Event(id("event"), (_, _) -> RewardEventAction.Result.APPLIED)));
        var stage = stage(1, Integer.MAX_VALUE).prerequisite(id("prerequisite"), 1).rewardGroup(Integer.MAX_VALUE, reward).build();
        assertEquals(1, stage.rewardGroupsByLevel().size());
        assertTrue(stage.rewardsForLevel(1).isEmpty());
        assertEquals(List.of(reward), stage.rewardsForLevel(Integer.MAX_VALUE));
        assertThrows(UnsupportedOperationException.class, () -> stage.rewardGroupsByLevel().int2ObjectEntrySet().iterator().next().setValue(ObjectLists.emptyList()));
        assertThrows(UnsupportedOperationException.class, () -> stage.prerequisites().object2IntEntrySet().iterator().next().setValue(2));
        assertThrows(UnsupportedOperationException.class, () -> stage.rewardsForLevel(Integer.MAX_VALUE).clear());
        assertThrows(IllegalArgumentException.class, () -> stage(1, 1).rewardGroup(1, reward).rewardGroup(1, reward).build());
    }

    @Test void conditionReceivesIndependentIdentityLevelTeamAndResourceSnapshots() {
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        levels.put(id("a"), 2);
        levels.defaultReturnValue(99);
        var amounts = new Object2LongOpenHashMap<NetworkResourceType>();
        amounts.put(NetworkResources.COMPUTE, 10);
        amounts.defaultReturnValue(99);
        var actor = UUID.randomUUID();
        var context = new ResearchStartContext(id("target"), 3, actor, "team", levels, amounts, 2);
        levels.clear();
        amounts.clear();
        var calls = new AtomicInteger();
        var definition = definition("target", 3).specialOnly().itemDownloadLevelCost(0).stage(stage(1, 3).build())
            .unlock(id("effect"), id("target"), 2).startCondition(input -> {
                calls.incrementAndGet();
                return Objects.equals(input.playerId(), actor) && Objects.equals(input.teamId(), "team") && input.targetLevel() == 3
                    && input.teamHasNetworkCenter() && input.teamSharedResearchLevels().getInt(id("a")) == 2
                    && input.availableResources().getLong(NetworkResources.COMPUTE) == 10;
            }).build();
        assertTrue(definition.startCondition().canStart(context));
        assertEquals(1, calls.get());
        assertEquals(0, context.teamSharedResearchLevels().getInt(id("absent")));
        assertEquals(0, context.availableResources().getLong(NetworkResources.QUBIT));
        assertThrows(UnsupportedOperationException.class, () -> context.teamSharedResearchLevels().object2IntEntrySet().iterator().next().setValue(3));
        assertThrows(UnsupportedOperationException.class, () -> context.availableResources().object2LongEntrySet().iterator().next().setValue(0));
        assertTrue(definition.specialOnly());
        assertEquals(0, definition.itemDownloadLevelCost());
        assertTrue(definition.unlocks(new ResearchUnlockQuery(id("effect"), id("target"), 2)));
        assertFalse(definition.unlocks(new ResearchUnlockQuery(id("effect"), id("target"), 1)));
    }

    @Test void manualStagesRejectNumericAndUnsupportedCompleteMaterialEffects() {
        assertThrows(IllegalArgumentException.class, () -> stage(1, 1).researchTier(0)
            .resourceFormula(ResearchResourceFormula.builder().constant(NetworkResources.COMPUTE, 1, 1).build()).build());
        var chance = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false, new ResourceChance(50, null), null);
        var requirement = RecipeRequirements.RESOURCES.requirement(id("chance"), chance);
        assertThrows(IllegalArgumentException.class, () -> stage(1, 1).researchTier(0).materialRequirement(requirement).build());
        var ordinary = stage(1, 1).materialRequirement(requirement).build();
        assertSame(requirement, ordinary.materialRequirements().getFirst());
    }

    private static ResearchDefinition.Builder definition(String name, int maxLevel) {
        return ResearchDefinition.builder(id(name)).maxLevel(maxLevel).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
    }
    private static ResearchLevelStage.Builder stage(int first, int last) {
        return ResearchLevelStage.builder(first, last).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build());
    }
    private static NetworkResourceRegistry resources() {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        return resources;
    }
    private static ResourceLocation id(String path) { return new ResourceLocation("prototype_eng_test", path); }
}
