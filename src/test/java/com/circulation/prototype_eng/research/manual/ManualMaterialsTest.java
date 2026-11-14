package com.circulation.prototype_eng.research.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;


class ManualMaterialsTest {
    @Test
    void partialPaymentsDistributeAcrossSameKeyInputsAndRoundTripToRemainingTotals() {
        var type = new TestResources.KeyType("manual_material");
        var key = new TestResources.Key(type, "input");
        var materials = ManualMaterials.from(List.of(RecipeRequirements.RESOURCES.requirement(new ResourceLocation("test", "materials"),
            new ResourceRequirement(List.of(new GenericStack(key, 2), new GenericStack(key, 4)), List.of()))));
        var first = materials.plan(new Object2LongOpenHashMap<>(), ImmutableResourceAmounts.single(key, 3), false, 1);
        assertEquals(3, materials.remaining(first.expectedWork()).getLong(key));
        assertEquals(first.expectedWork(), materials.workForRemaining(materials.remaining(first.expectedWork())));
        var second = materials.plan(first.expectedWork(), ImmutableResourceAmounts.single(key, 3), true, 2);
        assertTrue(materials.complete(second.expectedWork()));
        assertEquals(3, materials.quantities(second.increments()).getLong(key));
    }
}
