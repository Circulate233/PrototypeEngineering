package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureMatchingTest {
    private static final StructureQuery<String> MARKER = new StructureQuery<>(id("marker"), (_, _) -> {
        throw new AssertionError("Logical matching tests must not read a Minecraft world");
    }, UnaryOperator.identity(), String::equals);
    private static final StructurePredicate YES = StructurePredicates.all(List.of());
    private static final StructurePredicate NO = StructurePredicates.any(List.of());

    private static StructurePredicate marker(String value) {
        return StructurePredicates.test(MARKER, value::equals);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("structure_matching_test", path);
    }

    private static TemplateStructure template(String name, BlockPos offset, Map<BlockPos, StructurePredicate> blocks,
                                              List<StructurePredicate> validators, List<MachineStructure> children) {
        return new TemplateStructure(id(name), BlockOrientation.NORTH_UP, offset, false, new StructurePattern(blocks), validators, children);
    }

    private static SliceStructure slice(String name, BlockPos offset, int min, int max, BlockPos step,
                                        StructurePredicate predicate, List<MachineStructure> children) {
        return new SliceStructure(id(name), BlockOrientation.NORTH_UP, offset, false,
            new StructurePattern(Map.of(BlockPos.ORIGIN, predicate)), min, max, step, List.of(), children);
    }

    @Test
    void namedDefaultParentRetainsLegacyShapeAndPredicateVirtualCallbacks() {
        var calls = new ObjectArrayList<String>();
        var first = new BlockPos(1, 0, 0);
        var second = new BlockPos(2, 0, 0);
        var legacyTemplate = new TemplateStructure(id("legacy_template"), BlockOrientation.NORTH_UP, first, false,
            new StructurePattern(Map.of(BlockPos.ORIGIN, marker("template"))), List.of(), List.of()) {
            @Override
            public TemplateStructure transform(StructureTransform rotation) {
                calls.add("template transform");
                return this;
            }

            @Override
            public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
                calls.add("template collect");
                super.collectReads(plan, anchor, controller);
            }

            @Override
            public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
                calls.add("template match");
                return super.match(snapshot, anchor, controller);
            }
        };
        var legacySlice = new SliceStructure(id("legacy_slice"), BlockOrientation.NORTH_UP, second, false,
            new StructurePattern(Map.of(BlockPos.ORIGIN, marker("slice"))), 1, 1, first, List.of(), List.of()) {
            @Override
            public SliceStructure transform(StructureTransform rotation) {
                calls.add("slice transform");
                return this;
            }

            @Override
            public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
                calls.add("slice collect");
                super.collectReads(plan, anchor, controller);
            }

            @Override
            public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
                calls.add("slice match");
                return super.match(snapshot, anchor, controller);
            }
        };
        var legacyPredicate = new StructurePredicate() {
            @Override
            public StructurePredicate transform(StructureTransform rotation) {
                calls.add("predicate transform");
                return this;
            }

            @Override
            public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
                calls.add("predicate collect");
                plan.read(position, MARKER);
            }

            @Override
            public boolean matches(StructureSnapshot snapshot, BlockPos position) {
                calls.add("predicate match");
                return "validator".equals(snapshot.read(MARKER, position));
            }
        };
        var root = TemplateStructure.create(id("default_parent"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(Map.of()), List.of(legacyPredicate), List.of(legacyTemplate, legacySlice));
        var planning = StructurePlan.beginCreate(root, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        int slices = 0;
        while (!planning.advance(new RecipeWorkBudget(1))) assertTrue(++slices < 200);
        var source = new Source();
        source.values.put(first, "template");
        source.values.put(second, "slice");
        source.values.put(BlockPos.ORIGIN, "validator");
        var plan = Objects.requireNonNull(planning.result());
        var evaluation = plan.beginEvaluate(StructureSnapshot.capture(plan.reads(), source));
        slices = 0;
        while (!evaluation.advance(new RecipeWorkBudget(1))) assertTrue(++slices < 200);
        var match = Objects.requireNonNull(Objects.requireNonNull(evaluation.result()).match());
        assertSame(legacyTemplate, match.children().get(0).structure());
        assertSame(legacySlice, match.children().get(1).structure());
        assertEquals(List.of(first, second), match.allBlocks());
        assertTrue(calls.containsAll(List.of("template transform", "template collect", "template match",
            "slice transform", "slice collect", "slice match", "predicate transform", "predicate collect", "predicate match")));
    }

    @Test
    void nestedStructuresExcludeOnlyTheActualControllerAndDoNotPublishPartialSuccess() {
        var required = new BlockPos(1, 0, 0);
        var child = template("child", new BlockPos(0, 1, 0),
            Map.of(new BlockPos(0, -1, 0), marker("child"), new BlockPos(-1, -1, 0), NO), List.of(), List.of());
        var root = template("root", new BlockPos(1, 0, 0),
            Map.of(BlockPos.ORIGIN, YES, new BlockPos(-1, 0, 0), NO), List.of(), List.of(child));
        var plan = new StructurePlan(root, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        var source = new Source();
        source.values.put(required, "wrong");
        assertNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
        source.values.put(required, "child");
        var successful = plan.match(StructureSnapshot.capture(plan.reads(), source));
        assertNotNull(successful);
        assertEquals(List.of(required), successful.allBlocks());
        assertEquals(1, successful.children().size());
        source.values.clear();
        assertNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
        assertEquals(List.of(required), successful.allBlocks());
    }

    @Test
    void rotationMovesParentChildAndValidatorOffsetsTogetherButDoesNotCollectValidatorOnlyBlocks() {
        var child = template("child", new BlockPos(0, -1, 1), Map.of(new BlockPos(1, 0, 0), marker("child")), List.of(), List.of());
        var validator = StructurePredicates.at(new BlockPos(-1, 0, 3), marker("validator"));
        var root = template("root", new BlockPos(1, 0, -2), Map.of(new BlockPos(2, 1, 0), marker("root")), List.of(validator), List.of(child));
        var plan = new StructurePlan(root, new BlockPos(10, 20, 30), BlockOrientation.EAST_UP);
        var source = new Source();
        source.values.put(new BlockPos(12, 21, 33), "root");
        source.values.put(new BlockPos(9, 20, 30), "validator");
        source.values.put(new BlockPos(11, 19, 32), "child");
        var match = plan.match(StructureSnapshot.capture(plan.reads(), source));
        assertNotNull(match);
        assertEquals(new BlockPos(12, 20, 31), match.origin());
        assertEquals(BlockOrientation.EAST_UP, match.structure().orientation());
        assertEquals(List.of(new BlockPos(12, 21, 33), new BlockPos(11, 19, 32)), match.allBlocks());
        source.unloaded.add(StructureBounds.at(new BlockPos(9, 20, 30)));
        assertNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
        source.unloaded.clear();
        source.values.remove(new BlockPos(9, 20, 30));
        assertNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
    }

    @Test
    void greedySlicesIgnoreUnvisitedFarAreasButRequireTheNextActuallyAttemptedSliceToBeLoaded() {
        var child = template("end", new BlockPos(0, 0, 1), Map.of(BlockPos.ORIGIN, marker("end")), List.of(), List.of());
        var slices = slice("slices", new BlockPos(0, 1, 0), 1, 4, new BlockPos(1, 0, 0), marker("body"), List.of(child));
        var plan = new StructurePlan(slices, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        var source = new Source();
        source.values.put(new BlockPos(0, 1, 0), "body");
        source.values.put(new BlockPos(1, 1, 0), "body");
        source.values.put(new BlockPos(1, 1, 1), "end");
        source.unloaded.add(StructureBounds.at(new BlockPos(3, 1, 0)));
        var match = plan.match(StructureSnapshot.capture(plan.reads(), source));
        assertNotNull(match);
        assertEquals(2, match.repetitions());
        assertEquals(new BlockPos(1, 1, 1), match.children().getFirst().origin());
        assertEquals(List.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), new BlockPos(1, 1, 1)), match.allBlocks());
        source.unloaded.add(StructureBounds.at(new BlockPos(2, 1, 0)));
        assertNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
        source.unloaded.remove(StructureBounds.at(new BlockPos(2, 1, 0)));
        source.values.remove(new BlockPos(1, 1, 1));
        assertNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
    }

    @Test
    void zeroSlicesUseTheSamePrecedingSliceAnchorForPlanningMatchingAndCollectedChildren() {
        var child = template("child", new BlockPos(0, 1, 0), Map.of(BlockPos.ORIGIN, marker("child")), List.of(), List.of());
        for (int max : List.of(0, 3)) {
            var slices = slice("optional", new BlockPos(4, 0, 0), 0, max, new BlockPos(2, 0, 0), marker("body"), List.of(child));
            var plan = new StructurePlan(slices, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
            var source = new Source();
            source.values.put(new BlockPos(2, 1, 0), "child");
            var snapshot = StructureSnapshot.capture(plan.reads(), source);
            var match = plan.match(snapshot);
            assertNotNull(match);
            assertEquals(0, match.repetitions());
            assertTrue(match.blocks().isEmpty());
            assertEquals(List.of(new BlockPos(2, 1, 0)), match.allBlocks());
            source.unloaded.add(StructureBounds.at(new BlockPos(4, 0, 0)));
            var unavailable = plan.match(StructureSnapshot.capture(plan.reads(), source));
            if (max == 0) {
                assertNotNull(unavailable);
            } else {
                assertNull(unavailable);
            }
        }
    }

    @Test
    void childrenDoNotBacktrackSliceCountAndUnusedPotentialCaptureFailuresDoNotChangeTheResult() {
        var child = template("end", new BlockPos(0, 0, 1), Map.of(BlockPos.ORIGIN, marker("end")), List.of(), List.of());
        var slices = slice("greedy", new BlockPos(0, 1, 0), 1, 3, new BlockPos(1, 0, 0), marker("body"), List.of(child));
        var plan = new StructurePlan(slices, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        var source = new Source();
        for (int x = 0; x < 3; x++) {
            source.values.put(new BlockPos(x, 1, 0), "body");
        }
        source.values.put(new BlockPos(1, 1, 1), "end");
        assertNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
        source.values.put(new BlockPos(2, 1, 1), "end");
        var unusedFailure = new IllegalStateException("unused potential child");
        source.failures.put(new BlockPos(0, 1, 1), unusedFailure);
        var snapshot = StructureSnapshot.capture(plan.reads(), source);
        assertEquals(1, snapshot.failures().size());
        var match = plan.match(snapshot);
        assertNotNull(match);
        assertEquals(3, match.repetitions());
        var usedFailure = new IllegalArgumentException("actual selected child");
        source.failures.put(new BlockPos(2, 1, 1), usedFailure);
        var broken = StructureSnapshot.capture(plan.reads(), source);
        assertSame(usedFailure, assertThrows(IllegalStateException.class, () -> plan.match(broken)).getCause());
    }

    @Test
    void reusedSliceDefinitionsKeepIndependentOccurrencesInsteadOfFlatteningCountsById() {
        var shared = slice("shared", new BlockPos(0, 1, 0), 1, 3, new BlockPos(1, 0, 0), marker("body"), List.of());
        var left = template("left", new BlockPos(10, 0, 0), Map.of(), List.of(), List.of(shared));
        var right = template("right", new BlockPos(20, 0, 0), Map.of(), List.of(), List.of(shared));
        var root = template("root", BlockPos.ORIGIN, Map.of(), List.of(), List.of(left, right));
        var plan = new StructurePlan(root, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        var source = new Source();
        source.values.put(new BlockPos(10, 1, 0), "body");
        source.values.put(new BlockPos(20, 1, 0), "body");
        source.values.put(new BlockPos(21, 1, 0), "body");
        var match = plan.match(StructureSnapshot.capture(plan.reads(), source));
        assertNotNull(match);
        assertEquals(1, match.children().get(0).children().getFirst().repetitions());
        assertEquals(2, match.children().get(1).children().getFirst().repetitions());
        assertEquals(Set.of(new BlockPos(10, 1, 0), new BlockPos(20, 1, 0), new BlockPos(21, 1, 0)), Set.copyOf(match.allBlocks()));
    }

    private static final class Source implements StructureReadSource {
        final Map<BlockPos, String> values = new Object2ObjectOpenHashMap<>();
        final Map<BlockPos, RuntimeException> failures = new Object2ObjectOpenHashMap<>();
        final Set<StructureBounds> unloaded = new ObjectOpenHashSet<>();
        final Thread serverThread = Thread.currentThread();

        @Override
        public void requireServerThread() {
            assertSame(serverThread, Thread.currentThread());
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            return !unloaded.contains(bounds);
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked") // This fixture accepts only its String marker descriptor.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            assertSame(MARKER, query);
            var failure = failures.get(position);
            if (failure != null) {
                throw failure;
            }
            return (T) values.get(position);
        }
    }
}
