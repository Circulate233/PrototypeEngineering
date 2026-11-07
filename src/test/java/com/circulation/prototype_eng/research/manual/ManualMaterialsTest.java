package com.circulation.prototype_eng.research.manual;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManualMaterialsTest {
    private static final TestResources.KeyType TYPE = new TestResources.KeyType("manual_material");
    private static final TestResources.Key SHARED = new TestResources.Key(TYPE, "shared");
    private static final TestResources.Key OTHER = new TestResources.Key(TYPE, "other");

    @Test void advancesSameKeyInputsSeparatelyUntilTheirDifferentTargetsComplete() {
        var materials = ManualMaterials.from(List.of(requirement("input", new GenericStack(SHARED, 2), new GenericStack(SHARED, 4))));
        var firstId = material("input", 0);
        var secondId = material("input", 1);
        var first = materials.plan(new Object2LongOpenHashMap<>());
        assertEquals(0, first.sequence());
        assertEquals(2, first.quantities().getLong(SHARED));
        assertEquals(1, first.expectedWork().getLong(firstId));
        assertEquals(1, first.expectedWork().getLong(secondId));
        assertFalse(first.completesResearch());

        var second = materials.plan(first.expectedWork());
        assertEquals(2, second.sequence());
        assertEquals(2, second.quantities().getLong(SHARED));
        assertFalse(second.completesResearch());
        var third = materials.plan(second.expectedWork());
        assertEquals(4, third.sequence());
        assertEquals(1, third.quantities().getLong(SHARED));
        assertFalse(third.increments().containsKey(firstId));
        assertEquals(1, third.increments().getLong(secondId));
        assertEquals(2, third.expectedWork().getLong(firstId));
        assertEquals(3, third.expectedWork().getLong(secondId));
        assertFalse(third.completesResearch());

        var fourth = materials.plan(third.expectedWork());
        assertEquals(5, fourth.sequence());
        assertEquals(1, fourth.quantities().getLong(SHARED));
        assertTrue(fourth.completesResearch());
        assertTrue(materials.complete(fourth.expectedWork()));
        var alreadyComplete = materials.plan(fourth.expectedWork());
        assertEquals(6, alreadyComplete.sequence());
        assertTrue(alreadyComplete.increments().isEmpty());
        assertTrue(alreadyComplete.quantities().isEmpty());
        assertTrue(alreadyComplete.completesResearch());
    }

    @Test void resumesPartialWorkAcrossRequirementsWithoutChargingCompletedItems() {
        var materials = ManualMaterials.from(List.of(requirement("a", new GenericStack(SHARED, 3)),
            requirement("b", new GenericStack(SHARED, 2), new GenericStack(OTHER, 1))));
        var work = new Object2LongOpenHashMap<ManualMaterialKey>();
        work.defaultReturnValue(99);
        work.put(material("a", 0), 3);
        work.put(material("b", 0), 1);
        assertFalse(materials.complete(work));
        var plan = materials.plan(work);
        assertEquals(4, plan.sequence());
        assertEquals(2, plan.increments().size());
        assertFalse(plan.increments().containsKey(material("a", 0)));
        assertEquals(1, plan.quantities().getLong(SHARED));
        assertEquals(1, plan.quantities().getLong(OTHER));
        assertTrue(plan.completesResearch());
        assertTrue(materials.complete(plan.expectedWork()));
    }

    @Test void rejectsUnknownRequirementAndInputPositionsEvenWhenTheirWorkIsZero() {
        var materials = ManualMaterials.from(List.of(requirement("input", new GenericStack(SHARED, 2))));
        for (var unknown : List.of(material("missing", 0), material("input", 1))) {
            var work = new Object2LongOpenHashMap<ManualMaterialKey>();
            work.put(unknown, 0);
            assertThrows(IllegalArgumentException.class, () -> materials.validateWork(work));
            assertThrows(IllegalArgumentException.class, () -> materials.plan(work));
            assertThrows(IllegalArgumentException.class, () -> materials.complete(work));
        }
    }

    @Test void rejectsNegativeAndOverTargetRestoredWork() {
        var materials = ManualMaterials.from(List.of(requirement("input", new GenericStack(SHARED, 2))));
        for (long invalid : new long[] {-1, 3}) {
            var work = new Object2LongOpenHashMap<ManualMaterialKey>();
            work.put(material("input", 0), invalid);
            assertThrows(IllegalArgumentException.class, () -> materials.validateWork(work));
            assertThrows(IllegalArgumentException.class, () -> materials.plan(work));
            assertThrows(IllegalArgumentException.class, () -> materials.complete(work));
        }
    }

    @Test void rejectsCurrentAndResultingSequenceOverflowBeforePublishingProgress() {
        var materials = ManualMaterials.from(List.of(requirement("input", new GenericStack(SHARED, Long.MAX_VALUE), new GenericStack(OTHER, 1))));
        var work = new Object2LongOpenHashMap<ManualMaterialKey>();
        work.put(material("input", 0), Long.MAX_VALUE);
        assertDoesNotThrow(() -> materials.validateWork(work));
        assertThrows(ArithmeticException.class, () -> materials.plan(work));
        assertFalse(work.containsKey(material("input", 1)));
        work.put(material("input", 1), 1);
        assertThrows(ArithmeticException.class, () -> materials.validateWork(work));
        assertThrows(ArithmeticException.class, () -> materials.plan(work));
        assertThrows(ArithmeticException.class, () -> materials.complete(work));
    }

    @Test void acceptsAnExactlyRepresentableFinalSequence() {
        var materials = ManualMaterials.from(List.of(requirement("input", new GenericStack(SHARED, Long.MAX_VALUE - 1), new GenericStack(OTHER, 1))));
        var work = new Object2LongOpenHashMap<ManualMaterialKey>();
        work.put(material("input", 0), Long.MAX_VALUE - 1);
        var plan = materials.plan(work);
        assertEquals(Long.MAX_VALUE - 1, plan.sequence());
        assertTrue(plan.completesResearch());
        assertTrue(materials.complete(plan.expectedWork()));
        assertEquals(Long.MAX_VALUE, materials.plan(plan.expectedWork()).sequence());
    }

    @Test void capturesDefinitionsAndWorkAndRejectsMutationThroughEntries() {
        var definitions = new ObjectArrayList<RecipeRequirement<?>>();
        definitions.add(requirement("input", new GenericStack(SHARED, 2)));
        var materials = ManualMaterials.from(definitions);
        definitions.clear();
        var work = new Object2LongOpenHashMap<ManualMaterialKey>();
        work.put(material("input", 0), 1);
        var plan = materials.plan(work);
        work.clear();
        assertTrue(materials.complete(plan.expectedWork()));
        Map<ManualMaterialKey, Long> boxedWork = plan.expectedWork();
        assertThrows(UnsupportedOperationException.class, () -> plan.increments().object2LongEntrySet().iterator().next().setValue(9));
        assertThrows(UnsupportedOperationException.class, () -> boxedWork.entrySet().iterator().next().setValue(9L));
        assertThrows(UnsupportedOperationException.class, () -> plan.quantities().object2LongEntrySet().iterator().next().setValue(9));
        assertThrows(UnsupportedOperationException.class, () -> plan.quantities().addTo(SHARED, 1));
        assertEquals(1, plan.quantities().getLong(SHARED));
        assertEquals(2, plan.expectedWork().getLong(material("input", 0)));
    }

    @Test void planConstructorCopiesEveryProvidedMutableMap() {
        var work = new Object2LongOpenHashMap<ManualMaterialKey>();
        work.put(material("input", 0), 1);
        var quantities = new AEKey2LongMap.OpenHashMap();
        quantities.addTo(SHARED, 1);
        var plan = new ManualMaterials.Plan(work, work, quantities, 0, true);
        work.clear();
        quantities.clear();
        assertEquals(1, plan.increments().getLong(material("input", 0)));
        assertEquals(1, plan.expectedWork().getLong(material("input", 0)));
        assertEquals(1, plan.quantities().getLong(SHARED));
    }

    @Test void rejectsEmptyDuplicateAndNonResourceMaterials() {
        assertThrows(IllegalArgumentException.class, () -> ManualMaterials.from(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ManualMaterials.from(List.of(requirement("empty"))));
        var input = requirement("input", new GenericStack(SHARED, 1));
        assertThrows(IllegalArgumentException.class, () -> ManualMaterials.from(List.of(input, input)));
        assertThrows(IllegalArgumentException.class, () -> ManualMaterials.from(List.of(
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(1, 0, 0, 0, false)))));
    }

    @Test void rejectsResourceEffectsBeyondStaticInputs() {
        var stacks = List.of(new GenericStack(SHARED, 1));
        var unsupported = List.of(
            new ResourceRequirement(stacks, stacks, List.of(), List.of(), false),
            new ResourceRequirement(stacks, List.of(), stacks, List.of(), false),
            new ResourceRequirement(stacks, stacks),
            new ResourceRequirement(stacks, List.of(), List.of(), List.of(), true),
            new ResourceRequirement(stacks, List.of(), List.of(), List.of(), false, new ResourceChance(50, null), null),
            new ResourceRequirement(stacks, List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, null,
                List.of(new FuzzyInputGroup(List.of(SHARED), 1)), List.of()));
        for (var payload : unsupported) {
            assertThrows(IllegalArgumentException.class, () -> ManualMaterials.from(List.of(RecipeRequirements.RESOURCES.requirement(id("input"), payload))));
        }
    }

    private static RecipeRequirement<?> requirement(String name, GenericStack... inputs) {
        return RecipeRequirements.RESOURCES.requirement(id(name), new ResourceRequirement(List.of(inputs), List.of()));
    }

    private static ManualMaterialKey material(String requirement, int inputIndex) { return new ManualMaterialKey(id(requirement), inputIndex); }
    private static ResourceLocation id(String name) { return new ResourceLocation("prototype_eng_test", name); }
}
