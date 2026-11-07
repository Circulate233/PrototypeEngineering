package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.network.recipe.NetworkWorkRequirements;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkWorkRequirementsTest {
    @Test void combinesHighestResearchLevelsAndEveryNumericDimensionBeforeExactScaling() {
        var defaults = NetworkWorkRequirements.builder().research(id("shared"), 3).research(id("default"), 2)
            .resource(NetworkResources.COMPUTE, 5).resource(NetworkResources.QUBIT, 7).build();
        var custom = new NetworkResourceType(id("custom"), false, 8);
        var recipe = NetworkWorkRequirements.builder().research(id("shared"), 1).research(id("recipe"), 4)
            .resource(NetworkResources.COMPUTE, 6).resource(custom, 9).build();
        var combined = NetworkWorkRequirements.combined(defaults, recipe);
        assertEquals(3, combined.researchRequirements().getInt(id("shared")));
        assertEquals(2, combined.researchRequirements().getInt(id("default")));
        assertEquals(4, combined.researchRequirements().getInt(id("recipe")));
        var scaled = combined.scaledResources(3);
        assertEquals(3, scaled.size());
        assertEquals(33, scaled.getLong(NetworkResources.COMPUTE));
        assertEquals(21, scaled.getLong(NetworkResources.QUBIT));
        assertEquals(27, scaled.getLong(custom));
        assertEquals(5, defaults.resources().getLong(NetworkResources.COMPUTE));
        assertEquals(6, recipe.resources().getLong(NetworkResources.COMPUTE));
    }

    @Test void repeatedDeclarationsTakeMaxLevelsAndSumEqualMetadataTypes() {
        var equalCompute = new NetworkResourceType(NetworkResources.COMPUTE.id(), false, 0);
        var requirements = NetworkWorkRequirements.builder().research(id("research"), 1)
            .research(id("research"), 4).research(id("research"), 2)
            .resource(NetworkResources.COMPUTE, 3).resource(equalCompute, 5).build();
        assertEquals(4, requirements.researchRequirements().getInt(id("research")));
        assertEquals(8, requirements.resources().getLong(NetworkResources.COMPUTE));
        assertEquals(1, requirements.resources().size());
    }

    @Test void finishedContextsAndBothMapsRejectAllMutationRoutes() {
        var builder = NetworkWorkRequirements.builder().research(id("research"), 2).resource(NetworkResources.COMPUTE, 5);
        var requirements = builder.build();
        assertThrows(IllegalStateException.class, () -> builder.research(id("new"), 1));
        assertThrows(IllegalStateException.class, () -> builder.resource(NetworkResources.QUBIT, 1));
        assertThrows(IllegalStateException.class, () -> builder.addAll(NetworkWorkRequirements.EMPTY));
        assertThrows(IllegalStateException.class, builder::build);
        var research = requirements.researchRequirements();
        assertThrows(UnsupportedOperationException.class, () -> research.put(id("research"), 4));
        assertThrows(UnsupportedOperationException.class, () -> research.defaultReturnValue(99));
        assertThrows(UnsupportedOperationException.class, () -> research.object2IntEntrySet().iterator().next().setValue(9));
        var researchIterator = research.object2IntEntrySet().iterator();
        researchIterator.next();
        assertThrows(UnsupportedOperationException.class, researchIterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> research.values().removeIf(_ -> true));
        var resources = requirements.resources();
        assertThrows(UnsupportedOperationException.class, () -> resources.object2LongEntrySet().iterator().next().setValue(9));
        assertThrows(UnsupportedOperationException.class, resources::clear);
        assertThrows(UnsupportedOperationException.class, () -> requirements.scaledResources(3)
            .object2LongEntrySet().iterator().next().setValue(1));
    }

    @Test void numericOverflowDoesNotChangeSingleOrWholeVectorAddition() {
        var builder = NetworkWorkRequirements.builder().research(id("existing"), 1).resource(NetworkResources.COMPUTE, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> builder.resource(NetworkResources.COMPUTE, 1));
        var addition = NetworkWorkRequirements.builder().research(id("existing"), 4).research(id("new"), 2)
            .resource(NetworkResources.COMPUTE, 1).resource(NetworkResources.QUBIT, 7).build();
        assertThrows(ArithmeticException.class, () -> builder.addAll(addition));
        var result = builder.build();
        assertEquals(Long.MAX_VALUE, result.resources().getLong(NetworkResources.COMPUTE));
        assertFalse(result.resources().containsKey(NetworkResources.QUBIT));
        assertEquals(1, result.researchRequirements().getInt(id("existing")));
        assertFalse(result.researchRequirements().containsKey(id("new")));
        assertThrows(ArithmeticException.class, () -> NetworkWorkRequirements.combined(result, addition));
    }

    @Test void scalingRejectsOverflowAndNonpositiveParallelismIncludingEmptyRequirements() {
        var requirements = NetworkWorkRequirements.builder().resource(NetworkResources.QUBIT, 1)
            .resource(NetworkResources.COMPUTE, Long.MAX_VALUE / 2 + 1).build();
        assertThrows(ArithmeticException.class, () -> requirements.scaledResources(2));
        assertEquals(Long.MAX_VALUE / 2 + 1, requirements.scaledResources(1).getLong(NetworkResources.COMPUTE));
        assertEquals(Long.MAX_VALUE, NetworkWorkRequirements.builder().resource(NetworkResources.COMPUTE, Long.MAX_VALUE)
            .build().scaledResources(1).getLong(NetworkResources.COMPUTE));
        for (int parallelism : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> NetworkWorkRequirements.EMPTY.scaledResources(parallelism));
            assertThrows(IllegalArgumentException.class, () -> requirements.scaledResources(parallelism));
        }
    }

    @Test void rejectsLogicalResourcesInvalidDemandsAndSameIdMetadataDriftWithoutMutation() {
        var builder = NetworkWorkRequirements.builder().resource(NetworkResources.COMPUTE, 5);
        for (var type : new NetworkResourceType[]{NetworkResources.THREAD, new NetworkResourceType(id("custom_logical"), true, 0),
            new NetworkResourceType(NetworkResources.COMPUTE.id(), false, 7)}) {
            assertThrows(IllegalArgumentException.class, () -> builder.resource(type, 1));
        }
        for (long amount : new long[]{0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> builder.resource(NetworkResources.QUBIT, amount));
        }
        assertThrows(IllegalArgumentException.class, () -> builder.research(id("research"), 0));
        assertThrows(IllegalArgumentException.class, () -> builder.research(id("research"), -1));
        var conflict = NetworkWorkRequirements.builder().research(id("new"), 2)
            .resource(new NetworkResourceType(NetworkResources.COMPUTE.id(), false, 7), 3).build();
        assertThrows(IllegalArgumentException.class, () -> builder.addAll(conflict));
        var result = builder.build();
        assertEquals(5, result.resources().getLong(NetworkResources.COMPUTE));
        assertEquals(1, result.resources().size());
        assertTrue(result.researchRequirements().isEmpty());
    }

    @Test void validatesAuthoritativeResearchLevelsAndAllResourceMetadataWithoutApplyingSupplyCapsToDemand() {
        var resources = new NetworkResourceRegistry();
        var limited = resources.register(new NetworkResourceType(id("limited"), false, 2));
        resources.freeze();
        var research = new ResearchRegistry();
        research.register(ResearchDefinition.builder(id("research")).maxLevel(3)
            .deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(ResearchLevelStage.builder(1, 3)
                .researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        research.freeze(resources);
        var valid = NetworkWorkRequirements.builder().research(id("research"), 3)
            .resource(new NetworkResourceType(limited.id(), false, 2), 100).build();
        assertDoesNotThrow(() -> valid.validate(research, resources));
        assertThrows(IllegalArgumentException.class, () -> NetworkWorkRequirements.builder().research(id("research"), 4)
            .build().validate(research, resources));
        assertThrows(IllegalArgumentException.class, () -> NetworkWorkRequirements.builder().research(id("unknown"), 1)
            .build().validate(research, resources));
        assertThrows(IllegalArgumentException.class, () -> NetworkWorkRequirements.builder().resource(new NetworkResourceType(id("unknown"), false, 0), 1)
            .build().validate(research, resources));
        assertThrows(IllegalArgumentException.class, () -> NetworkWorkRequirements.builder().resource(new NetworkResourceType(limited.id(), false, 0), 1)
            .build().validate(research, resources));
        assertThrows(IllegalArgumentException.class, () -> NetworkWorkRequirements.builder()
            .resource(new NetworkResourceType(NetworkResources.THREAD.id(), false, 1), 1).build().validate(research, resources));
    }

    @Test void validationRequiresBothRegistriesFrozenEvenForEmptyRequirements() {
        var resources = new NetworkResourceRegistry();
        var research = new ResearchRegistry();
        assertThrows(IllegalStateException.class, () -> NetworkWorkRequirements.EMPTY.validate(research, resources));
        resources.freeze();
        assertThrows(IllegalStateException.class, () -> NetworkWorkRequirements.EMPTY.validate(research, resources));
        research.freeze(resources);
        assertDoesNotThrow(() -> NetworkWorkRequirements.EMPTY.validate(research, resources));
        assertThrows(IllegalStateException.class, () -> NetworkWorkRequirements.EMPTY.validate(research, new NetworkResourceRegistry()));
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("work_requirements_test", path); }
}
