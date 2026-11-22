package com.circulation.prototype_eng.api.recipe.importing;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.RecipeInventoryMatch;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.localization.RecipePoolTitle;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipePoolTypeTest {
    private static final Key INPUT = new Key(new KeyType("external_pool_type"), "input");

    @Test
    void registeredFactoryCreatesAnIndependentStrategyLazilyAndFreezesWithoutReimporting() {
        var reads = new AtomicInteger();
        var creations = new AtomicInteger();
        var first = recipe("first");
        var second = recipe("second");
        var type = RecipePoolType.register(id("reverse"), (poolId, title, recipes) -> {
            creations.incrementAndGet();
            return new ReversePool(poolId, title, recipes, List.of());
        });
        var source = new ImportedRecipePool(id("source"), "pool.external.title", () -> {
            reads.incrementAndGet();
            return List.of(first, second);
        }).poolType(type);
        assertSame(type, RecipePoolType.require(type.id()));
        assertSame(type, RecipePoolType.require(type.id().toString()));
        assertEquals(0, reads.get());
        assertEquals(0, creations.get());
        assertInstanceOf(ReversePool.class, source.implementation());
        var inventory = new AEKey2LongMap.OpenHashMap();
        inventory.put(INPUT, 2);
        assertSame(second, source.match(inventory));
        var replacement = RecipeBuilder.from(first).durationTicks(7).build();
        var machines = List.of(MachineType.builder(id("machine"), new RecipePoolTitle("pool.external.machine"), id("structure")).build());
        var frozen = source.freeze(List.of(replacement), machines);
        assertInstanceOf(ReversePool.class, frozen.implementation());
        assertSame(type, frozen.poolType());
        assertSame(replacement, frozen.match(inventory));
        assertSame(machines, frozen.machines());
        assertThrows(IllegalStateException.class, () -> frozen.poolType(RecipePoolType.GENERAL));
        assertEquals(1, reads.get());
        assertEquals(1, creations.get());
    }

    @Test
    void duplicateAndUnknownTypesFailWithoutReplacingRegistrationsOrInvokingFactories() {
        var calls = new AtomicInteger();
        var type = RecipePoolType.register(id("duplicate"), (poolId, title, recipes) -> {
            calls.incrementAndGet();
            return new DefaultRecipePool(poolId, title, recipes);
        });
        assertThrows(IllegalArgumentException.class,
            () -> RecipePoolType.register(type.id(), DefaultRecipePool::new));
        assertSame(type, RecipePoolType.require(type.id()));
        assertThrows(IllegalArgumentException.class, () -> RecipePoolType.require(id("missing")));
        assertThrows(IllegalArgumentException.class, () -> RecipePoolType.require(" "));
        assertSame(RecipePoolType.GENERAL, RecipePoolType.require("general"));
        assertSame(RecipePoolType.GENERAL, RecipePoolType.require("prototype_eng:general"));
        assertSame(RecipePoolType.SINGLE_INPUT, RecipePoolType.require("single_input"));
        assertEquals(0, calls.get());
    }

    @Test
    void factoryFailuresAndNullResultsRemainFailedWithoutRepeatingTheSource() {
        var failure = new IllegalArgumentException("External factory failure");
        var calls = new AtomicInteger();
        var type = RecipePoolType.register(id("failed"), (_, _, _) -> {
            calls.incrementAndGet();
            throw failure;
        });
        var source = new ImportedRecipePool(id("failed_source"), "pool.title", List::of).poolType(type);
        assertSame(failure, assertThrows(IllegalArgumentException.class, source::recipes));
        assertSame(failure, assertThrows(IllegalStateException.class, source::recipes).getCause());
        assertThrows(IllegalStateException.class, () -> source.poolType(RecipePoolType.GENERAL));
        assertEquals(1, calls.get());
        var nullCalls = new AtomicInteger();
        var nullType = RecipePoolType.register(id("null_result"), (_, _, _) -> {
            nullCalls.incrementAndGet();
            return null;
        });
        var nullSource = new ImportedRecipePool(id("null_source"), "pool.title", List::of).poolType(nullType);
        var nullFailure = assertThrows(NullPointerException.class, nullSource::recipes);
        assertSame(nullFailure, assertThrows(IllegalStateException.class, nullSource::recipes).getCause());
        assertEquals(1, nullCalls.get());
    }

    @Test
    void factoriesMustRetainPoolIdentityTitleAndExactRecipeMembership() {
        var first = recipe("identity_first");
        var second = recipe("identity_second");
        var recipes = List.of(first, second);
        List<RecipePoolType.Factory> invalid = List.of(
            (_, title, values) -> new DefaultRecipePool(id("wrong_id"), title, values),
            (poolId, _, values) -> new DefaultRecipePool(poolId, "wrong.title", values),
            (poolId, title, _) -> new DefaultRecipePool(poolId, title, List.of(first)),
            (poolId, title, _) -> new DefaultRecipePool(poolId, title, List.of(second, first)),
            (poolId, title, _) -> new DefaultRecipePool(poolId, title, List.of(RecipeBuilder.from(first).build(), second)));
        for (int index = 0; index < invalid.size(); index++) {
            var type = RecipePoolType.register(id("invalid_" + index), invalid.get(index));
            var source = new ImportedRecipePool(id("invalid_source_" + index), "pool.title", () -> recipes).poolType(type);
            var failure = assertThrows(IllegalArgumentException.class, source::recipes);
            assertSame(failure, assertThrows(IllegalStateException.class, source::recipes).getCause());
        }
    }

    private static MachineRecipe recipe(String path) {
        return new RecipeBuilder(id(path), 1).addInput(id("materials"), new GenericStack(INPUT, 1)).build();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("pool_type_test", path);
    }

    private record ReversePool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes,
                               List<MachineType> machines) implements RecipePool {
        @Override
        public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
            return new DefaultRecipePool(id, translationKey, recipes).match(inventory, parallelism).reversed();
        }

        @Override
        public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
            return new ReversePool(id, translationKey, recipes, machines);
        }
    }
}
