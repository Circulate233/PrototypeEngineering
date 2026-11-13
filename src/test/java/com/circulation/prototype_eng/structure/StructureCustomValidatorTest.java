package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicateResult;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureCustomValidatorTest {
    private static final BlockPos CONTROLLER = new BlockPos(10, 64, 10);
    private static final BlockPos BASE = new BlockPos(10, 64, 16);
    private static final BlockPos NEIGHBOR = new BlockPos(10, 64, 17);

    private static void finishOneUnit(StructureOperation<?> operation) {
        int slices = 0;
        while (!operation.advance(new RecipeWorkBudget(1))) assertTrue(++slices < 1000);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("custom_test", path);
    }

    @Test
    void transformedNestedPatternsAndValidatorsShareDetachedFactsAndIndependentWorkerProofs() throws Exception {
        var owner = Thread.currentThread();
        var calls = new AtomicInteger();
        var sampler = new StructureQuery<>(id("metadata"), (_, _) -> fail("No live World in fixture"),
            NBTTagCompound::copy, Objects::equals);
        var custom = StructurePredicates.custom(List.of(StructureQueries.BIOME_NAME, sampler),
            List.of(BlockPos.ORIGIN, new BlockPos(1, 0, 0)), List.of(BlockPos.ORIGIN), facts -> {
                assertNotSame(owner, Thread.currentThread());
                calls.incrementAndGet();
                assertEquals(BASE, facts.position());
                assertEquals(new BlockPos(0, 0, 6), facts.relativePosition());
                assertEquals(NEIGHBOR, facts.at(new BlockPos(1, 0, 0)).position());
                var data = Objects.requireNonNull(facts.read(sampler));
                assertEquals(7, data.getInteger("amount"));
                return "Birch Forest".equals(facts.read(StructureQueries.BIOME_NAME))
                    && "Birch Forest".equals(facts.atController(BlockPos.ORIGIN).read(StructureQueries.BIOME_NAME));
            });
        var displayed = StructurePredicates.withDisplay(StructurePredicates.any(List.of(custom)),
            new StructureDisplay("explicit", List.of(new StructureDisplay.BlockOption(id("marker"), 0, Map.of())), null, false));
        var wrapped = StructurePredicates.all(List.of(StructurePredicates.at(new BlockPos(1, 0, 0), displayed)));
        var cell = new StructurePattern(new BlockPos2ObjectMap<>(Map.of(new BlockPos(1, 0, 0), displayed)));
        var slice = SliceStructure.create(id("slice"), BlockOrientation.NORTH_UP, new BlockPos(3, 0, 0), false,
            cell, 1, 1, new BlockPos(0, 1, 0), List.of(wrapped), List.of());
        var root = TemplateStructure.create(id("root"), BlockOrientation.NORTH_UP, new BlockPos(2, 0, 0), false,
            new StructurePattern(BlockPos2ObjectMap.emptyMap()), List.of(), List.of(slice));
        var planning = StructurePlan.beginCreate(root, CONTROLLER, BlockOrientation.EAST_UP);
        finishOneUnit(planning);
        var plan = Objects.requireNonNull(planning.result());
        var source = new Source(owner);
        var tag = new NBTTagCompound();
        tag.setInteger("amount", 7);
        for (var position : List.of(BASE, NEIGHBOR, CONTROLLER)) {
            source.put(sampler, position, tag);
            source.put(StructureQueries.BIOME_NAME, position, "Birch Forest");
        }
        var first = StructureSnapshot.capture(plan.reads(), source);
        var second = StructureSnapshot.capture(plan.reads(), source);
        tag.setInteger("amount", 88);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> plan.evaluate(first));
            var b = workers.submit(() -> plan.evaluate(second));
            var evaluationA = a.get(5, TimeUnit.SECONDS);
            var evaluationB = b.get(5, TimeUnit.SECONDS);
            assertEquals(StructurePredicateResult.MATCHED, evaluationA.status());
            assertEquals(StructurePredicateResult.MATCHED, evaluationB.status());
            assertNotSame(evaluationA.match(), evaluationB.match());
            assertFalse(evaluationA.isCurrent(source), "Sampler data changed after capture");
        }
        assertEquals(4, calls.get(), "The pattern and base validator run once per independent attempt");
    }

    @Test
    void threeArgumentDriversKeepLegacyTwoArgumentBudgetHooksThroughWrappers() {
        var declarations = new AtomicInteger();
        var matches = new AtomicInteger();
        var legacy = new StructurePredicate() {
            @Override
            public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
                fail("Budgeted driver must retain the old begin hook");
            }

            @Override
            public boolean matches(StructureSnapshot snapshot, BlockPos position) {
                return fail("Budgeted driver must retain the old begin hook");
            }

            @Override
            public StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position) {
                return StructureOperation.call(() -> {
                    declarations.incrementAndGet();
                    plan.read(position, StructureQueries.DIMENSION);
                    return null;
                });
            }

            @Override
            public StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position) {
                return StructureOperation.call(() -> {
                    matches.incrementAndGet();
                    return Objects.requireNonNull(snapshot.read(StructureQueries.DIMENSION, position)) == 12
                        ? StructurePredicateResult.MATCHED : StructurePredicateResult.MISMATCH;
                });
            }
        };
        var wrapped = StructurePredicates.all(List.of(StructurePredicates.at(new BlockPos(1, 0, 0), legacy)));
        var child = SliceStructure.create(id("legacy_slice"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(BlockPos2ObjectMap.emptyMap()), 1, 1, new BlockPos(0, 1, 0), List.of(wrapped), List.of());
        var root = TemplateStructure.create(id("legacy_root"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(new BlockPos2ObjectMap<>(Map.of(new BlockPos(1, 0, 0), legacy))), List.of(wrapped), List.of(child));
        var planning = StructurePlan.beginCreate(root, CONTROLLER, BlockOrientation.EAST_UP);
        finishOneUnit(planning);
        var plan = Objects.requireNonNull(planning.result());
        var source = new Source(Thread.currentThread());
        source.put(StructureQueries.DIMENSION, CONTROLLER.add(0, 0, 1), 12);
        var matching = plan.beginEvaluate(StructureSnapshot.capture(plan.reads(), source));
        finishOneUnit(matching);
        assertEquals(StructurePredicateResult.MATCHED, Objects.requireNonNull(matching.result()).status());
        assertEquals(3, declarations.get());
        assertEquals(3, matches.get());
    }

    @Test
    void optionalUnavailableReadsAndSamplerFailuresRemainDistinctFromCallbackFailures() {
        var source = new Source(Thread.currentThread());
        var neighbor = new BlockPos(1, 0, 0);
        var custom = StructurePredicates.custom(List.of(StructureQueries.BIOME_NAME), List.of(BlockPos.ORIGIN, neighbor),
            List.of(), facts -> facts.at(neighbor).available() && "Forest".equals(facts.at(neighbor).read(StructureQueries.BIOME_NAME)));
        var builder = new StructureReadPlan.Builder();
        custom.collectReads(builder, BlockPos.ORIGIN, CONTROLLER);
        source.unavailable.add(neighbor);
        var snapshot = StructureSnapshot.capture(builder.build(), source);
        assertEquals(StructurePredicateResult.UNAVAILABLE, custom.beginMatch(snapshot, BlockPos.ORIGIN, CONTROLLER).run());
        source.unavailable.clear();
        var failure = new IllegalStateException("sampler failed");
        source.put(StructureQueries.BIOME_NAME, neighbor, failure);
        snapshot = StructureSnapshot.capture(builder.build(), source);
        var captured = snapshot;
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> custom.beginMatch(captured, BlockPos.ORIGIN, CONTROLLER).run()).getCause());
        var callbackFailure = new IllegalArgumentException("validator failed");
        var throwing = StructurePredicates.custom(List.of(), List.of(), List.of(), _ -> {
            throw callbackFailure;
        });
        builder = new StructureReadPlan.Builder();
        throwing.collectReads(builder, BlockPos.ORIGIN, CONTROLLER);
        var onlyArea = StructureSnapshot.capture(builder.build(), source);
        assertSame(callbackFailure, assertThrows(IllegalArgumentException.class,
            () -> throwing.beginMatch(onlyArea, BlockPos.ORIGIN, CONTROLLER).run()));
        assertNull(throwing.display(), "Preview cannot execute or infer a custom validator");
    }

    private static final class Source implements StructureReadSource {
        private final Thread owner;
        private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Object> values = new Object2ObjectOpenHashMap<>();
        private final ObjectOpenHashSet<BlockPos> unavailable = new ObjectOpenHashSet<>();

        private Source(Thread owner) {
            this.owner = owner;
        }

        private <T> void put(StructureQuery<T> query, BlockPos position, Object value) {
            values.put(new StructureReadPlan.Read<>(position, query), value);
        }

        @Override
        public void requireServerThread() {
            assertSame(owner, Thread.currentThread());
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            return !unavailable.contains(bounds.min());
        }

        @Override
        @SuppressWarnings("unchecked") // Fixture storage is keyed by the typed query descriptor.
        public <T> @Nullable T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            var value = values.get(new StructureReadPlan.Read<>(position, query));
            if (value instanceof RuntimeException failure) throw failure;
            return (T) value;
        }
    }
}
