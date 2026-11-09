package com.circulation.prototype_eng.structure;

import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.structure.StructureCheckPolicy;
import com.circulation.prototype_eng.api.structure.StructureReadWindow;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class StructureSnapshotTest {
    @Test
    void explicitFiniteBlockReadsRunInTheOriginalJoinedWorkerAndTheirPortsCloseBeforeReturn() {
        var owner = Thread.currentThread();
        var position = new BlockPos(17, 0, 1);
        var whole = new StructureBounds(position, position.add(64, 0, 0));
        var windows = new ObjectArrayList<StructureReadWindow<?>>();
        var areas = new ObjectArrayList<StructureBounds>();
        var blocks = new AtomicInteger();
        var descriptor = new StructureQuery<>(new ResourceLocation("test", "finite_block_port"),
            (_, _) -> fail("Explicitly safe block reads must not use the World callback"), UnaryOperator.identity(),
            String::equals, (states, requested) -> { states.getBlockState(requested); return "body"; });
        var source = new StructureReadSource() {
            @Override public void requireServerThread() { assertSame(owner, Thread.currentThread()); }
            @Override public boolean isAreaLoaded(StructureBounds bounds) { requireServerThread(); areas.add(bounds); return true; }
            @Override public <T> T read(StructureQuery<T> query, BlockPos requested) { return fail("An explicit finite port owns this read"); }
            @Override public <T> StructureReadWindow<T> beginWorkerRead(StructureQuery<T> query, BlockPos requested) {
                requireServerThread(); assertEquals(position, requested);
                var window = query.beginWorkerCapture(bound -> {
                    assertNotSame(owner, Thread.currentThread()); assertEquals(position, bound);
                    blocks.incrementAndGet(); return null;
                }, requested);
                windows.add(window); return window;
            }
        };
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            var deadline = PlannedDeadline.after(Duration.ofSeconds(5));
            var work = new StructureCheckPolicy.Work(20, deadline, Duration.ofSeconds(5), false);
            var capture = StructureSnapshot.beginCapture(new StructureReadPlan.Builder().area(whole).read(position, descriptor).build(), source,
                action -> scheduler.runStructureWorker(() -> { action.run(); return true; }, deadline)
                    instanceof PlannedTaskExecutor.Success<Boolean>);
            scheduler.runAtBoundary(() -> assertTrue(capture.advance(work)));
            assertEquals("body", Objects.requireNonNull(capture.result()).read(descriptor, position));
            assertEquals(1, blocks.get());
            assertTrue(areas.contains(whole), "The actual whole-area virtual check remains visible");
            assertEquals(1, windows.size());
            assertThrows(IllegalStateException.class, windows.getFirst()::close);
            assertTrue(work.spent() > 3, "Capture and mandatory port closure both contribute actual cost");
        }
    }
    @Test
    void retainedCaptureRechecksLoadingBeforeTheActualLegacyReadOnANewSlice() {
        var descriptor = query("slice_loading", UnaryOperator.<String>identity());
        var position = new BlockPos(1, 0, 0);
        var plan = new StructureReadPlan.Builder().read(position, descriptor).build();
        var source = new FakeSource();
        source.put(descriptor, position, "must not be read after unload");
        var capture = StructureSnapshot.beginCapture(plan, source, null);
        assertFalse(capture.advance(new RecipeWorkBudget(2)));
        source.area(StructureBounds.at(position), () -> false);
        assertTrue(capture.advance(new RecipeWorkBudget(20)));
        var snapshot = Objects.requireNonNull(capture.result());
        assertFalse(snapshot.isAreaLoaded(StructureBounds.at(position)));
        assertEquals(0, source.readCount(descriptor, position));
    }

    @Test
    void stoppedBorrowTerminatesTheCaptureWithoutPublishingMissingFacts() {
        var descriptor = query("stopped_borrow", UnaryOperator.<String>identity());
        var position = new BlockPos(1, 0, 0);
        var source = new FakeSource();
        source.put(descriptor, position, "captured on the real owner");
        var capture = StructureSnapshot.beginCapture(new StructureReadPlan.Builder().read(position, descriptor).build(), source, _ -> false);
        assertTrue(capture.advance(new RecipeWorkBudget(20)));
        assertTrue(capture.complete());
        assertNull(capture.result());
        assertEquals(1, source.readCount(descriptor, position));
    }
    @Test
    void unavailableOptionalBranchesDoNotPreventRequiredReadsOrReadUnloadedPositions() {
        StructureQuery<String> query = query("block", UnaryOperator.identity());
        BlockPos required = new BlockPos(1, 2, 3);
        BlockPos absent = new BlockPos(2, 2, 3);
        BlockPos optional = new BlockPos(97, 2, 3);
        StructureBounds distant = new StructureBounds(optional, optional.add(15, 0, 0));
        var plan = new StructureReadPlan.Builder().area(distant)
                .read(required, query).read(absent, query).read(optional, query).build();
        var source = new FakeSource();
        source.put(query, required, "casing");
        source.put(query, optional, "must not be read");
        source.area(distant, () -> false);
        source.area(StructureBounds.at(optional), () -> false);

        var snapshot = StructureSnapshot.capture(plan, source);

        assertEquals("casing", snapshot.read(query, required));
        assertNull(snapshot.read(query, absent));
        assertFalse(snapshot.isAreaLoaded(distant));
        assertThrows(IllegalStateException.class, () -> snapshot.read(query, optional));
        assertEquals(1, source.readCount(query, required));
        assertEquals(1, source.readCount(query, absent));
        assertEquals(0, source.readCount(query, optional));
        assertTrue(snapshot.failures().isEmpty());
    }

    @Test
    void sparsePatternBoundsAreIndependentFromTheirLoadedEndpointCells() {
        StructureQuery<String> query = query("sparse", UnaryOperator.identity());
        BlockPos first = new BlockPos(1, 0, 0);
        BlockPos last = new BlockPos(33, 0, 0);
        StructureBounds patternBounds = new StructureBounds(first, last);
        var plan = new StructureReadPlan.Builder().area(patternBounds)
                .read(first, query).read(last, query).build();
        var source = new FakeSource();
        source.area(patternBounds, () -> false);
        source.put(query, first, "head");
        source.put(query, last, "tail");

        var snapshot = StructureSnapshot.capture(plan, source);

        assertFalse(snapshot.isAreaLoaded(patternBounds));
        assertTrue(snapshot.isAreaLoaded(StructureBounds.at(first)));
        assertTrue(snapshot.isAreaLoaded(StructureBounds.at(last)));
        assertEquals("head", snapshot.read(query, first));
        assertEquals("tail", snapshot.read(query, last));
    }

    @Test
    void nbtIsDeeplyDetachedFromBothSourceMutationsAndDeliveredValueMutations() {
        StructureQuery<NBTTagCompound> query = query("nbt", NBTTagCompound::copy);
        BlockPos position = new BlockPos(4, 5, 6);
        var nested = new NBTTagCompound();
        nested.setInteger("amount", 17);
        nested.setIntArray("slots", new int[] {2, 5, 8});
        var original = new NBTTagCompound();
        original.setTag("storage", nested);
        var source = new FakeSource();
        source.put(query, position, original);
        var snapshot = StructureSnapshot.capture(new StructureReadPlan.Builder().read(position, query).build(), source);

        nested.setInteger("amount", 99);
        nested.getIntArray("slots")[0] = 77;
        NBTTagCompound delivered = snapshot.read(query, position);
        assertNotNull(delivered);
        assertEquals(17, delivered.getCompoundTag("storage").getInteger("amount"));
        assertEquals(2, delivered.getCompoundTag("storage").getIntArray("slots")[0]);
        delivered.getCompoundTag("storage").setInteger("amount", 123);
        delivered.getCompoundTag("storage").getIntArray("slots")[1] = 456;
        delivered.removeTag("storage");

        NBTTagCompound deliveredAgain = snapshot.read(query, position);
        assertNotNull(deliveredAgain);
        assertEquals(17, deliveredAgain.getCompoundTag("storage").getInteger("amount"));
        assertEquals(5, deliveredAgain.getCompoundTag("storage").getIntArray("slots")[1]);
        assertEquals(99, nested.getInteger("amount"));
        assertEquals(1, source.readCount(query, position));
    }

    @Test
    void aliasesReadOnceAndBuiltPlansDetachBuilderChangesAndMutablePositions() {
        StructureQuery<String> query = query("aliases", UnaryOperator.identity());
        var mutable = new BlockPos.MutableBlockPos(3, 4, 5);
        BlockPos original = mutable.toImmutable();
        var min = new BlockPos.MutableBlockPos(0, 0, 0);
        var max = new BlockPos.MutableBlockPos(8, 8, 8);
        StructureBounds originalArea = new StructureBounds(min, max);
        var builder = new StructureReadPlan.Builder().area(originalArea)
                .read(mutable, query).read(new BlockPos(3, 4, 5), query);
        var oldPlan = builder.build();
        mutable.setPos(20, 21, 22);
        min.setPos(40, 40, 40);
        max.setPos(50, 50, 50);
        BlockPos later = mutable.toImmutable();
        StructureBounds laterArea = new StructureBounds(min, max);
        builder.area(laterArea).read(mutable, query);
        var source = new FakeSource();
        source.put(query, original, "old");
        source.put(query, later, "new");

        var oldSnapshot = StructureSnapshot.capture(oldPlan, source);

        assertEquals("old", oldSnapshot.read(query, original));
        assertTrue(oldSnapshot.isAreaLoaded(new StructureBounds(BlockPos.ORIGIN, new BlockPos(8, 8, 8))));
        assertThrows(IllegalArgumentException.class, () -> oldSnapshot.isAreaLoaded(laterArea));
        assertThrows(IllegalArgumentException.class, () -> oldSnapshot.read(query, later));
        assertEquals(1, source.readCount(query, original));
        assertEquals(0, source.readCount(query, later));
        var newSnapshot = StructureSnapshot.capture(builder.build(), source);
        assertEquals("new", newSnapshot.read(query, later));
        assertEquals("old", newSnapshot.read(query, original));
    }

    @Test
    void conflictingQueryIdsAreRejectedWithoutPollutingAnExistingPlanOrItsBuilder() {
        StructureQuery<String> originalQuery = query("shared_id", UnaryOperator.identity());
        StructureQuery<Integer> conflictingQuery = query("shared_id", UnaryOperator.identity());
        BlockPos original = new BlockPos(1, 2, 3);
        BlockPos rejected = new BlockPos(64, 2, 3);
        var builder = new StructureReadPlan.Builder().read(original, originalQuery);
        var oldPlan = builder.build();
        assertThrows(IllegalArgumentException.class, () -> builder.read(rejected, conflictingQuery));
        builder.read(original, originalQuery);
        var source = new FakeSource();
        source.put(originalQuery, original, "valid");
        source.put(conflictingQuery, rejected, 42);

        var oldSnapshot = StructureSnapshot.capture(oldPlan, source);
        var rebuiltSnapshot = StructureSnapshot.capture(builder.build(), source);

        assertEquals("valid", oldSnapshot.read(originalQuery, original));
        assertEquals("valid", rebuiltSnapshot.read(originalQuery, original));
        assertThrows(IllegalArgumentException.class, () -> rebuiltSnapshot.read(conflictingQuery, original));
        assertThrows(IllegalArgumentException.class, () -> rebuiltSnapshot.isAreaLoaded(StructureBounds.at(rejected)));
        assertEquals(2, source.readCount(originalQuery, original));
        assertEquals(0, source.readCount(conflictingQuery, rejected));
    }

    @Test
    void runtimeAreaAndReadFailuresRemainDeferredFactsInsteadOfFalseOrNull() {
        StructureQuery<String> query = query("read_failure", UnaryOperator.identity());
        BlockPos good = new BlockPos(1, 0, 0);
        BlockPos failedRead = new BlockPos(2, 0, 0);
        BlockPos failedArea = new BlockPos(3, 0, 0);
        var readFailure = new IllegalArgumentException("provider failed");
        var areaFailure = new IllegalArgumentException("chunk lookup failed");
        var source = new FakeSource();
        source.put(query, good, "available");
        source.supply(query, failedRead, () -> { throw readFailure; });
        source.area(StructureBounds.at(failedArea), () -> { throw areaFailure; });
        var plan = new StructureReadPlan.Builder().read(good, query)
                .read(failedRead, query).read(failedArea, query).build();

        var snapshot = StructureSnapshot.capture(plan, source);

        assertEquals("available", snapshot.read(query, good));
        assertEquals(2, snapshot.failures().size());
        assertSame(readFailure, assertThrows(IllegalStateException.class,
                () -> snapshot.read(query, failedRead)).getCause());
        assertSame(areaFailure, assertThrows(IllegalStateException.class,
                () -> snapshot.isAreaLoaded(StructureBounds.at(failedArea))).getCause());
        assertSame(areaFailure, assertThrows(IllegalStateException.class,
                () -> snapshot.read(query, failedArea)).getCause());
        assertEquals(0, source.readCount(query, failedArea));
    }

    @Test
    void copyFailuresAreDeferredButJvmErrorsAbortCaptureImmediately() {
        var copyFailure = new IllegalArgumentException("cannot detach value");
        StructureQuery<String> badCopy = query("bad_copy", _ -> { throw copyFailure; });
        BlockPos position = new BlockPos(7, 8, 9);
        var source = new FakeSource();
        source.put(badCopy, position, "mutable value");
        var snapshot = StructureSnapshot.capture(new StructureReadPlan.Builder().read(position, badCopy).build(), source);
        assertEquals(1, snapshot.failures().size());
        assertSame(copyFailure, assertThrows(IllegalStateException.class,
                () -> snapshot.read(badCopy, position)).getCause());

        var copyError = new AssertionError("fatal copy");
        StructureQuery<String> fatalCopy = query("fatal_copy", _ -> { throw copyError; });
        var copySource = new FakeSource();
        copySource.put(fatalCopy, position, "value");
        var copyPlan = new StructureReadPlan.Builder().read(position, fatalCopy).build();
        assertSame(copyError, assertThrows(AssertionError.class, () -> StructureSnapshot.capture(copyPlan, copySource)));

        StructureQuery<String> query = query("fatal", UnaryOperator.identity());
        BlockPos later = position.add(1, 0, 0);
        var readError = new AssertionError("fatal read");
        var readSource = new FakeSource();
        readSource.supply(query, position, () -> { throw readError; });
        readSource.put(query, later, "not reached");
        var plan = new StructureReadPlan.Builder().read(position, query).read(later, query).build();
        assertSame(readError, assertThrows(AssertionError.class, () -> StructureSnapshot.capture(plan, readSource)));
        assertEquals(0, readSource.readCount(query, later));

        var areaError = new AssertionError("fatal area");
        var areaSource = new FakeSource();
        areaSource.area(StructureBounds.at(position), () -> { throw areaError; });
        assertSame(areaError, assertThrows(AssertionError.class, () -> StructureSnapshot.capture(plan, areaSource)));
        assertEquals(0, areaSource.readCount(query, position));
    }

    @Test
    void workersConsumeOnlyDetachedDeclaredFactsAndCannotCaptureFromTheServerSource() {
        StructureQuery<String> query = query("worker", UnaryOperator.identity());
        StructureQuery<String> undeclared = query("undeclared", UnaryOperator.identity());
        BlockPos position = new BlockPos(1, 2, 3);
        var plan = new StructureReadPlan.Builder().read(position, query).build();
        var source = new FakeSource();
        source.put(query, position, "captured");
        var snapshot = StructureSnapshot.capture(plan, source);
        source.put(query, position, "changed after capture");

        String workerValue = CompletableFuture.supplyAsync(() -> {
            assertThrows(IllegalStateException.class, () -> StructureSnapshot.capture(plan, source));
            assertThrows(IllegalStateException.class, () -> source.read(query, position));
            assertThrows(IllegalStateException.class, () -> source.isAreaLoaded(StructureBounds.at(position)));
            assertThrows(IllegalArgumentException.class, () -> snapshot.read(undeclared, position));
            assertThrows(IllegalArgumentException.class,
                    () -> snapshot.isAreaLoaded(StructureBounds.at(position.add(32, 0, 0))));
            return snapshot.read(query, position);
        }).join();

        assertEquals("captured", workerValue);
        assertEquals(1, source.readCount(query, position));
    }

    private static <T> StructureQuery<T> query(String path, UnaryOperator<T> copier) {
        return new StructureQuery<>(new ResourceLocation("test", path),
                (_, _) -> fail("A fake source must never call the World reader"), copier, Objects::equals);
    }

    private static final class FakeSource implements StructureReadSource {
        private final Thread serverThread = Thread.currentThread();
        private final Object2ObjectOpenHashMap<StructureBounds, Supplier<Boolean>> areas = new Object2ObjectOpenHashMap<>();
        private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Supplier<?>> values = new Object2ObjectOpenHashMap<>();
        private final Object2IntOpenHashMap<StructureReadPlan.Read<?>> readCounts = new Object2IntOpenHashMap<>();

        private void area(StructureBounds bounds, Supplier<Boolean> value) { areas.put(bounds, value); }

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
            if (Thread.currentThread() != serverThread) {
                throw new IllegalStateException("World facts belong to the server thread");
            }
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            var value = areas.get(bounds);
            return value == null || value.get();
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked") // supply binds each descriptor's T to its stored supplier.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            var request = new StructureReadPlan.Read<>(position, query);
            readCounts.addTo(request, 1);
            assertTrue(isAreaLoaded(StructureBounds.at(position)), "Capture must not read an unloaded position");
            var value = values.get(request);
            return value == null ? null : (T) value.get();
        }
    }
}
