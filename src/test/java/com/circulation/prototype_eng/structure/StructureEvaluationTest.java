package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureEvaluation;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureEvaluationTest {
    private static StructureEvaluation evaluate(StructurePlan plan, FakeSource source) {
        return plan.evaluate(StructureSnapshot.capture(plan.reads(), source));
    }

    private static StructurePlan plan(String name, StructurePredicate predicate) {
        var structure = new TemplateStructure(id(name), BlockOrientation.NORTH_UP,
            new BlockPos(1, 0, 0), false,
            new StructurePattern(Map.of(BlockPos.ORIGIN, predicate)), List.of(), List.of());
        return new StructurePlan(structure, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
    }

    private static StructurePredicate marker(StructureQuery<String> query, String expected) {
        return StructurePredicates.test(query, expected::equals);
    }

    private static StructureQuery<String> stringQuery(String name) {
        return new StructureQuery<>(id(name), (_, _) -> fail("No World reader may run in this test"),
            UnaryOperator.identity(), Objects::equals);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("structure_evaluation_test", name);
    }

    @Test
    void unusedOrBranchFailuresAndUnloadedAreasDoNotEnterTheActualProof() {
        StructureQuery<String> query = stringQuery("optional");
        BlockPos position = new BlockPos(1, 0, 0);
        BlockPos distant = new BlockPos(65, 0, 0);
        StructurePredicate predicate = StructurePredicates.any(List.of(
            marker(query, "head"),
            StructurePredicates.at(new BlockPos(64, 0, 0), marker(query, "fallback"))));
        var plan = plan("optional", predicate);
        var source = new FakeSource();
        source.put(query, position, "head");
        var initialFailure = new IllegalArgumentException("unused distant provider");
        source.supply(query, distant, () -> {
            throw initialFailure;
        });
        var snapshot = StructureSnapshot.capture(plan.reads(), source);
        StructureEvaluation evaluation = plan.evaluate(snapshot);
        assertNotNull(evaluation.match());
        assertEquals(1, evaluation.failures().size());
        assertSame(initialFailure, assertThrows(IllegalStateException.class,
            () -> snapshot.read(query, distant)).getCause());

        source.unloaded.add(StructureBounds.at(distant));
        assertTrue(evaluation.isCurrent(source));
        assertSame(evaluation, evaluation.refresh(source));
        assertEquals(1, source.readCount(query, distant));
        source.unloaded.clear();
        var changedFailure = new IllegalStateException("the unused provider changed");
        source.supply(query, distant, () -> {
            throw changedFailure;
        });
        assertTrue(evaluation.isCurrent(source));
        assertSame(evaluation, evaluation.refresh(source));
        assertEquals(1, source.readCount(query, distant));
    }

    @Test
    void theFirstFailedGreedySliceIsRecheckedAndANewMatchIncludesTheAddedSlices() {
        StructureQuery<String> query = stringQuery("slices");
        var structure = new SliceStructure(id("slices"), BlockOrientation.NORTH_UP,
            new BlockPos(1, 0, 0), false,
            new StructurePattern(Map.of(BlockPos.ORIGIN, marker(query, "body"))),
            1, 3, new BlockPos(1, 0, 0), List.of(), List.of());
        var plan = new StructurePlan(structure, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        BlockPos first = new BlockPos(1, 0, 0);
        BlockPos second = new BlockPos(2, 0, 0);
        BlockPos third = new BlockPos(3, 0, 0);
        var source = new FakeSource();
        source.put(query, first, "body");
        source.put(query, second, "stop");
        source.put(query, third, "stop");
        var old = evaluate(plan, source);
        var oldMatch = old.match();
        assertNotNull(oldMatch);
        assertEquals(1, oldMatch.repetitions());
        assertEquals(List.of(first), oldMatch.allBlocks());

        source.put(query, second, "body");
        assertFalse(old.isCurrent(source));
        var refreshed = old.refresh(source);
        var refreshedMatch = refreshed.match();
        assertNotNull(refreshedMatch);
        assertNotSame(old, refreshed);
        assertEquals(2, refreshedMatch.repetitions());
        assertEquals(List.of(first, second), refreshedMatch.allBlocks());
        assertEquals(1, oldMatch.repetitions());
        assertTrue(refreshed.isCurrent(source));

        source.put(query, third, "body");
        assertFalse(refreshed.isCurrent(source));
        var maximumMatch = refreshed.refresh(source).match();
        assertNotNull(maximumMatch);
        assertEquals(3, maximumMatch.repetitions());
        assertEquals(List.of(first, second, third), maximumMatch.allBlocks());
    }

    @Test
    void changedWorldTimeRefreshesTheProofWhileTheTimeConditionCanRemainTrue() {
        BlockPos position = new BlockPos(1, 0, 0);
        StructurePredicate time = StructurePredicates.test(StructureQueries.WORLD_TIME,
            value -> value != null && value >= 100 && value < 200);
        var plan = plan("time", time);
        var source = new FakeSource();
        source.put(StructureQueries.WORLD_TIME, position, 100L);
        var old = evaluate(plan, source);
        assertNotNull(old.match());

        source.put(StructureQueries.WORLD_TIME, position, 101L);
        assertFalse(old.isCurrent(source));
        var current = old.refresh(source);
        assertNotSame(old, current);
        assertNotNull(current.match());
        assertTrue(current.isCurrent(source));

        source.put(StructureQueries.WORLD_TIME, position, 200L);
        var expired = current.refresh(source);
        assertNull(expired.match());
        assertTrue(expired.isCurrent(source));
    }

    @Test
    void mutableFactsUseTheirExplicitEquivalenceAndBothCaptureAndDeliveryStayDetached() {
        StructureQuery<MutableFact> query = new StructureQuery<>(id("mutable_fact"),
            (_, _) -> fail("A logical fake must never invoke a World reader"),
            fact -> new MutableFact(fact.amount, fact.slots.clone()),
            (first, second) -> first.amount == second.amount && Arrays.equals(first.slots, second.slots));
        StructurePredicate predicate = StructurePredicates.test(query,
            fact -> fact != null && fact.amount == 7 && fact.slots[0] == 2);
        var plan = plan("mutable", predicate);
        BlockPos position = new BlockPos(1, 0, 0);
        var original = new MutableFact(7, new int[]{2, 5});
        var source = new FakeSource();
        source.put(query, position, original);
        var snapshot = StructureSnapshot.capture(plan.reads(), source);
        var evaluation = plan.evaluate(snapshot);
        assertNotNull(evaluation.match());
        original.amount = 90;
        original.slots[0] = 99;

        var replacement = new MutableFact(7, new int[]{2, 5});
        source.put(query, position, replacement);
        assertTrue(evaluation.isCurrent(source));
        assertSame(evaluation, evaluation.refresh(source));
        MutableFact delivered = snapshot.read(query, position);
        assertNotNull(delivered);
        assertNotSame(replacement, delivered);
        delivered.amount = 123;
        delivered.slots[0] = 456;
        assertTrue(evaluation.isCurrent(source));

        replacement.slots[0] = 8;
        assertFalse(evaluation.isCurrent(source));
        assertNull(evaluation.refresh(source).match());
    }

    @Test
    void changedBranchSelectionCanStopVisitingAnOldFailureButVisitedFailuresStillPropagate() {
        StructureQuery<String> firstQuery = stringQuery("first_branch");
        StructureQuery<String> fallbackQuery = stringQuery("fallback_branch");
        BlockPos position = new BlockPos(1, 0, 0);
        BlockPos fallbackPosition = new BlockPos(33, 0, 0);
        StructurePredicate predicate = StructurePredicates.any(List.of(marker(firstQuery, "yes"),
            StructurePredicates.at(new BlockPos(32, 0, 0), marker(fallbackQuery, "yes"))));
        var plan = plan("changed_branch", predicate);
        var source = new FakeSource();
        source.put(firstQuery, position, "no");
        source.put(fallbackQuery, fallbackPosition, "yes");
        var old = evaluate(plan, source);
        assertNotNull(old.match());

        var failure = new IllegalArgumentException("the previously used fallback failed");
        source.put(firstQuery, position, "yes");
        source.supply(fallbackQuery, fallbackPosition, () -> {
            throw failure;
        });
        assertFalse(old.isCurrent(source));
        var refreshed = old.refresh(source);
        assertNotNull(refreshed.match());
        assertEquals(1, refreshed.failures().size());
        assertTrue(refreshed.isCurrent(source));

        source.put(firstQuery, position, "no");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> refreshed.refresh(source)).getCause());

        source.put(firstQuery, position, "yes");
        source.areaFailures.put(StructureBounds.at(fallbackPosition), failure);
        var withoutFailedArea = old.refresh(source);
        assertNotNull(withoutFailedArea.match());
        assertTrue(withoutFailedArea.isCurrent(source));
        source.put(firstQuery, position, "no");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> withoutFailedArea.refresh(source)).getCause());
    }

    private static final class MutableFact {
        private final int[] slots;
        private int amount;

        private MutableFact(int amount, int[] slots) {
            this.amount = amount;
            this.slots = slots;
        }
    }

    private static final class FakeSource implements StructureReadSource {
        private final Thread serverThread = Thread.currentThread();
        private final ObjectOpenHashSet<StructureBounds> unloaded = new ObjectOpenHashSet<>();
        private final Object2ObjectOpenHashMap<StructureBounds, RuntimeException> areaFailures = new Object2ObjectOpenHashMap<>();
        private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Supplier<?>> values = new Object2ObjectOpenHashMap<>();
        private final Object2IntOpenHashMap<StructureReadPlan.Read<?>> readCounts = new Object2IntOpenHashMap<>();

        private <T> void put(StructureQuery<T> query, BlockPos position, T value) {
            supply(query, position, () -> value);
        }

        private <T> void supply(StructureQuery<T> query, BlockPos position, Supplier<T> value) {
            values.put(new StructureReadPlan.Read<>(position, query), value);
        }

        private <T> int readCount(StructureQuery<T> query, BlockPos position) {
            return readCounts.getInt(new StructureReadPlan.Read<>(position, query));
        }

        @Override
        public void requireServerThread() {
            assertSame(serverThread, Thread.currentThread(), "Only the server may recapture facts");
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            var failure = areaFailures.get(bounds);
            if (failure != null) {
                throw failure;
            }
            return !unloaded.contains(bounds);
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked") // supply binds a descriptor's T to its stored supplier.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertTrue(isAreaLoaded(StructureBounds.at(position)), "Do not capture unloaded positions");
            var request = new StructureReadPlan.Read<>(position, query);
            readCounts.addTo(request, 1);
            var value = values.get(request);
            return value == null ? null : (T) value.get();
        }
    }
}
