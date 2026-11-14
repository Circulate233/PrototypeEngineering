package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResearchDefinitionTest {
    private static ResearchDefinition.Builder definition(String name, int maxLevel) {
        return ResearchDefinition.builder(id(name)).maxLevel(maxLevel).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
    }

    private static ResearchLevelStage.Builder stage(int first, int last) {
        return ResearchLevelStage.builder(first, last).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("prototype_eng_test", path);
    }

    @Test
    void registryPublishesNothingAfterFailedValidationAndAcceptsRepairedReferences() {
        var registry = new ResearchRegistry();
        registry.register(definition("dependent", 1).stage(stage(1, 1).prerequisite(id("missing"), 1).build()).build());
        assertThrows(IllegalArgumentException.class, registry::freeze);
        assertFalse(registry.isFrozen());
        assertThrows(IllegalStateException.class, registry::all);
        registry.register(definition("missing", 1).stage(stage(1, 1).build()).build());
        registry.freeze();
        assertEquals(2, registry.all().size());
        assertEquals(id("dependent"), registry.require(id("dependent")).id());
        assertThrows(IllegalStateException.class, () -> registry.register(definition("late", 1).stage(stage(1, 1).build()).build()));
    }

    @Test
    void rejectsCyclesAndAsymmetryAcrossEveryCoveredStage() {
        var cyclic = new ResearchRegistry();
        cyclic.register(definition("a", 1).stage(stage(1, 1).prerequisite(id("b"), 1).build()).build());
        cyclic.register(definition("b", 1).stage(stage(1, 1).prerequisite(id("a"), 1).build()).build());
        assertThrows(IllegalArgumentException.class, cyclic::freeze);

        var asymmetric = new ResearchRegistry();
        asymmetric.register(definition("a", 3).stage(stage(1, 3).mutex(id("b"), 1).build()).build());
        asymmetric.register(definition("b", 3).stage(stage(1, 1).mutex(id("a"), 1).build()).stage(stage(2, 3).build()).build());
        assertThrows(IllegalArgumentException.class, asymmetric::freeze);

        var symmetric = new ResearchRegistry();
        symmetric.register(definition("a", Integer.MAX_VALUE).stage(stage(1, Integer.MAX_VALUE).mutex(id("b"), 1).build()).build());
        symmetric.register(definition("b", Integer.MAX_VALUE).stage(stage(1, 2).mutex(id("a"), 1).build())
                                                             .stage(stage(3, Integer.MAX_VALUE).mutex(id("a"), 1).build()).build());
        assertDoesNotThrow(symmetric::freeze);
    }

}
