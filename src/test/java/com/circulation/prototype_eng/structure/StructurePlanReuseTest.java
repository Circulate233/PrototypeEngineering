package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCheckPolicy;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructurePlanReuseTest {
    private static final BlockPos BODY = new BlockPos(1, 0, 0);
    private static final StructureQuery<String> VALUE = new StructureQuery<>(id("value"),
        (_, _) -> fail("No native World in plan tests"), UnaryOperator.identity(), String::equals);

    private static ResourceLocation id(String name) {
        return new ResourceLocation("plan_reuse_test", name);
    }

    private static MachineStructure template(StructurePredicate predicate) {
        return TemplateStructure.create(id("shape"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(new BlockPos2ObjectMap<>(Map.of(BODY, predicate))), List.of(), List.of());
    }

    private static TracedShape traced(Counts counts, boolean reusable) {
        return new TracedShape(template(StructurePredicates.test(VALUE, "body"::equals)), counts, reusable, () -> {
        }, () -> {
        });
    }

    private static StructureConfiguration configuration(MachineStructure shape) {
        return new StructureConfiguration(new StructureCatalog(List.of(new StructureDefinition(shape))), shape, BlockOrientation.NORTH_UP);
    }

    private static void drain(StructureOperation<StructurePlan> operation) {
        int slices = 0;
        while (!operation.advance(new RecipeWorkBudget(1))) assertTrue(++slices < 1000);
        assertNotNull(operation.result());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for independent operations");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void expiredBudgetsCannotStartAMissOrUseACompletedTransform() {
        var counts = new Counts();
        var configuration = configuration(traced(counts, true));
        var deadline = new PlannedDeadline(System.nanoTime() - 1_000_000, 1);
        var cold = StructurePlan.beginCreate(configuration, BlockPos.ORIGIN);
        assertFalse(cold.advance(new StructureCheckPolicy.Work(1000, deadline, Duration.ofSeconds(1), true)));
        assertEquals(0, counts.transforms.get());
        drain(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN));
        var warm = StructurePlan.beginCreate(configuration, BODY);
        assertFalse(warm.advance(new StructureCheckPolicy.Work(1000, deadline, Duration.ofSeconds(1), true)));
        assertEquals(1, counts.transforms.get());
        assertEquals(1, counts.declarations.get());
        drain(StructurePlan.beginCreate(configuration, BODY));
        assertEquals(1, counts.transforms.get());
        assertEquals(2, counts.declarations.get());
    }

    @Test
    void theDefaultSliceReusesItsShapeButReevaluatesEveryConditionAndGreedyLength() {
        var conditions = new AtomicInteger();
        var predicate = StructurePredicates.test(VALUE, value -> {
            conditions.incrementAndGet();
            return "body".equals(value);
        });
        var shape = SliceStructure.create(id("slice"), BlockOrientation.NORTH_UP, BODY, false,
            new StructurePattern(new BlockPos2ObjectMap<>(Map.of(BlockPos.ORIGIN, predicate))), 1, 3, BODY, List.of(), List.of());
        var configuration = configuration(shape);
        var first = Objects.requireNonNull(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN).run());
        var second = Objects.requireNonNull(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN).run());
        var shorter = first.evaluate(StructureSnapshot.capture(first.reads(), new Source(Map.of(BODY, "body", new BlockPos(2, 0, 0), "missing", new BlockPos(3, 0, 0), "missing"))));
        var longer = second.evaluate(StructureSnapshot.capture(second.reads(), new Source(Map.of(BODY, "body", new BlockPos(2, 0, 0), "body", new BlockPos(3, 0, 0), "missing"))));
        assertEquals(1, Objects.requireNonNull(shorter.match()).repetitions());
        assertEquals(2, Objects.requireNonNull(longer.match()).repetitions());
        assertSame(shorter.match().structure(), longer.match().structure());
        assertEquals(5, conditions.get());
    }

    @Test
    void aFailedTransformIsNeverRememberedAsACompletedEntry() {
        var counts = new Counts();
        var first = new AtomicBoolean(true);
        var shape = new TracedShape(template(StructurePredicates.test(VALUE, "body"::equals)), counts, true,
            () -> {
                if (first.getAndSet(false)) throw new IllegalArgumentException("first transform failed");
            }, () -> {
        });
        var configuration = configuration(shape);
        assertThrows(IllegalArgumentException.class, () -> StructurePlan.beginCreate(configuration, BlockPos.ORIGIN).run());
        drain(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN));
        drain(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN));
        assertEquals(2, counts.transforms.get());
    }

    @Test
    void legacyConstructorsAndUnknownChildrenOrValidatorsPreventParentReuse() {
        var legacyTransforms = new AtomicInteger();
        var legacy = new TemplateStructure(id("legacy"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(new BlockPos2ObjectMap<>(Map.of(BODY, StructurePredicates.test(VALUE, "body"::equals)))), List.of(), List.of()) {
            @Override
            public TemplateStructure transform(StructureTransform rotation) {
                legacyTransforms.incrementAndGet();
                return super.transform(rotation);
            }
        };
        var legacyConfiguration = configuration(legacy);
        drain(StructurePlan.beginCreate(legacyConfiguration, BlockPos.ORIGIN));
        drain(StructurePlan.beginCreate(legacyConfiguration, BlockPos.ORIGIN));
        assertEquals(2, legacyTransforms.get());
        var childCounts = new Counts();
        var parent = TemplateStructure.create(id("parent"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(BlockPos2ObjectMap.emptyMap()), List.of(), List.of(traced(childCounts, false)));
        var parentConfiguration = configuration(parent);
        drain(StructurePlan.beginCreate(parentConfiguration, BlockPos.ORIGIN));
        drain(StructurePlan.beginCreate(parentConfiguration, BlockPos.ORIGIN));
        assertEquals(2, childCounts.transforms.get());
        var validatorTransforms = new AtomicInteger();
        StructurePredicate validator = new StructurePredicate() {
            @Override
            public StructurePredicate transform(StructureTransform rotation) {
                validatorTransforms.incrementAndGet();
                return this;
            }

            @Override
            public void collectReads(StructureReadPlan.Builder reads, BlockPos position) {
                reads.read(position, VALUE);
            }

            @Override
            public boolean matches(StructureSnapshot snapshot, BlockPos position) {
                return "body".equals(snapshot.read(VALUE, position));
            }
        };
        var withValidator = configuration(TemplateStructure.create(id("validated"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(BlockPos2ObjectMap.emptyMap()), List.of(validator), List.of()));
        drain(StructurePlan.beginCreate(withValidator, BlockPos.ORIGIN));
        drain(StructurePlan.beginCreate(withValidator, BlockPos.ORIGIN));
        assertEquals(2, validatorTransforms.get());
    }

    @Test
    void aCompletedTransformIsSharedButConcurrentMatchesAndReadProofsStayIndependent() throws Exception {
        var counts = new Counts();
        var matching = new CountDownLatch(2);
        var shape = new TracedShape(template(StructurePredicates.test(VALUE, "body"::equals)), counts, true,
            () -> {
            }, () -> {
            matching.countDown();
            await(matching);
        });
        var configuration = configuration(shape);
        var first = Objects.requireNonNull(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN).run());
        var secondOrigin = new BlockPos(10, 0, 0);
        var second = Objects.requireNonNull(StructurePlan.beginCreate(configuration, secondOrigin).run());
        assertEquals(1, counts.transforms.get());
        assertEquals(2, counts.declarations.get());
        var firstSource = new Source(Map.of(BODY, "body"));
        var secondSource = new Source(Map.of(secondOrigin.add(BODY), "missing"));
        var firstSnapshot = StructureSnapshot.capture(first.reads(), firstSource);
        var secondSnapshot = StructureSnapshot.capture(second.reads(), secondSource);
        try (var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory())) {
            var firstResult = workers.submit(() -> first.evaluate(firstSnapshot));
            var secondResult = workers.submit(() -> second.evaluate(secondSnapshot));
            var accepted = firstResult.get(5, TimeUnit.SECONDS);
            var rejected = secondResult.get(5, TimeUnit.SECONDS);
            assertNotNull(accepted.match());
            assertNull(rejected.match());
            assertTrue(accepted.isCurrent(firstSource));
            assertTrue(rejected.isCurrent(secondSource));
            assertEquals(List.of(BODY), Objects.requireNonNull(accepted.match()).blocks());
            // Each proof re-captures only its own absolute position; the other source would fail.
            assertEquals(2, firstSource.reads.get());
            assertEquals(2, secondSource.reads.get());
        }
        assertEquals(2, counts.matches.get());
        var evaluatedShapes = List.copyOf(counts.evaluated);
        assertSame(evaluatedShapes.get(0), evaluatedShapes.get(1));
    }

    @Test
    void simultaneousMissesBuildIndependentCursorsAndPublishOneCompletedShape() throws Exception {
        var counts = new Counts();
        var transforming = new CountDownLatch(2);
        var shape = new TracedShape(template(StructurePredicates.test(VALUE, "body"::equals)), counts, true,
            () -> {
                transforming.countDown();
                await(transforming);
            }, () -> {
        });
        var configuration = configuration(shape);
        StructurePlan first;
        StructurePlan second;
        try (var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory())) {
            var one = workers.submit(() -> StructurePlan.beginCreate(configuration, BlockPos.ORIGIN).run());
            var two = workers.submit(() -> StructurePlan.beginCreate(configuration, new BlockPos(10, 0, 0)).run());
            first = Objects.requireNonNull(one.get(5, TimeUnit.SECONDS));
            second = Objects.requireNonNull(two.get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, counts.transforms.get());
        var one = first.evaluate(StructureSnapshot.capture(first.reads(), new Source(Map.of(BODY, "body"))));
        var two = second.evaluate(StructureSnapshot.capture(second.reads(), new Source(Map.of(new BlockPos(11, 0, 0), "body"))));
        assertSame(Objects.requireNonNull(one.match()).structure(), Objects.requireNonNull(two.match()).structure());
        Objects.requireNonNull(StructurePlan.beginCreate(configuration, new BlockPos(20, 0, 0)).run());
        assertEquals(2, counts.transforms.get(), "A later request adopts the completed value");
    }

    @Test
    void oneUnitPlanningDoesNotPublishAnUnfinishedTransformAndCatalogsDoNotAlias() {
        var counts = new Counts();
        var shape = traced(counts, true);
        var configuration = configuration(shape);
        var first = StructurePlan.beginCreate(configuration, BlockPos.ORIGIN);
        assertFalse(first.advance(new RecipeWorkBudget(1)));
        assertEquals(0, counts.transforms.get());
        var second = StructurePlan.beginCreate(configuration, new BlockPos(10, 0, 0));
        drain(second);
        assertEquals(1, counts.transforms.get());
        drain(first);
        assertEquals(2, counts.transforms.get(), "The already-started miss owns its own transform cursor");
        var current = new StructureConfiguration(configuration.catalog(), shape, BlockOrientation.SOUTH_UP);
        drain(StructurePlan.beginCreate(current, BlockPos.ORIGIN));
        drain(StructurePlan.beginCreate(new StructureConfiguration(configuration.catalog(), shape, BlockOrientation.NORTH_UP), BlockPos.ORIGIN));
        assertEquals(3, counts.transforms.get(), "Returning to a cached orientation reuses only pure geometry");
        drain(StructurePlan.beginCreate(configuration(shape), BlockPos.ORIGIN));
        assertEquals(4, counts.transforms.get(), "A new catalog with the same canonical object still has its own memo");
    }

    @Test
    void unknownShapeAndMutablePredicateDeclarationsKeepTheirVirtualCalls() {
        var counts = new Counts();
        var unknown = traced(counts, false);
        var configuration = configuration(unknown);
        drain(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN));
        drain(StructurePlan.beginCreate(configuration, BlockPos.ORIGIN));
        assertEquals(2, counts.transforms.get());
        var chosen = new AtomicInteger();
        var declarations = new AtomicInteger();
        StructurePredicate mutable = new StructurePredicate() {
            @Override
            public void collectReads(StructureReadPlan.Builder reads, BlockPos position) {
                declarations.incrementAndGet();
                reads.read(position.add(chosen.get(), 0, 0), VALUE);
            }

            @Override
            public boolean matches(StructureSnapshot snapshot, BlockPos position) {
                return "body".equals(snapshot.read(VALUE, position.add(chosen.get(), 0, 0)));
            }
        };
        var parent = configuration(template(StructurePredicates.all(List.of(StructurePredicates.at(BlockPos.ORIGIN, mutable)))));
        var initial = Objects.requireNonNull(StructurePlan.beginCreate(parent, BlockPos.ORIGIN).run());
        assertNotNull(initial.match(StructureSnapshot.capture(initial.reads(), new Source(Map.of(BODY, "body")))));
        chosen.set(3);
        var changed = Objects.requireNonNull(StructurePlan.beginCreate(parent, BlockPos.ORIGIN).run());
        assertNotNull(changed.match(StructureSnapshot.capture(changed.reads(), new Source(Map.of(new BlockPos(4, 0, 0), "body")))));
        assertEquals(2, declarations.get());
    }

    private static final class Counts {
        private final AtomicInteger transforms = new AtomicInteger();
        private final AtomicInteger declarations = new AtomicInteger();
        private final AtomicInteger matches = new AtomicInteger();
        private final ConcurrentLinkedQueue<MachineStructure> evaluated = new ConcurrentLinkedQueue<>();
    }

    private record TracedShape(MachineStructure delegate, Counts counts, boolean planReusable,
                               Runnable transforming, Runnable matching) implements MachineStructure {
        @Override
        public ResourceLocation id() {
            return delegate.id();
        }

        @Override
        public BlockOrientation orientation() {
            return delegate.orientation();
        }

        @Override
        public boolean hideWorldBlocks() {
            return false;
        }

        @Override
        public MachineStructure transform(StructureTransform rotation) {
            counts.transforms.incrementAndGet();
            transforming.run();
            return new TracedShape(delegate.transform(rotation), counts, planReusable, transforming, matching);
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
            counts.declarations.incrementAndGet();
            delegate.collectReads(plan, anchor, controller);
        }

        @Override
        @Nullable
        public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
            counts.matches.incrementAndGet();
            counts.evaluated.add(this);
            matching.run();
            var result = delegate.match(snapshot, anchor, controller);
            return result == null ? null : new StructureMatch(this, result.origin(), result.repetitions(), result.blocks(), result.children());
        }
    }

    private static final class Source implements StructureReadSource {
        private final Thread owner = Thread.currentThread();
        private final Map<BlockPos, String> values;
        private final AtomicInteger reads = new AtomicInteger();

        private Source(Map<BlockPos, String> values) {
            this.values = values;
        }

        @Override
        public void requireServerThread() {
            assertSame(owner, Thread.currentThread());
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            return true;
        }

        @Override
        @SuppressWarnings("unchecked") // This fixture owns one String query descriptor.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertSame(VALUE, query);
            assertTrue(values.containsKey(position), "Another request's read position was reused");
            reads.incrementAndGet();
            return (T) values.get(position);
        }
    }
}
