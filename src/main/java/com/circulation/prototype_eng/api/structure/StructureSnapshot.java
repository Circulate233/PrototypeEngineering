package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import java.util.Collections;

/**
 * Detached structure facts for worker matching. Unavailable optional branches and failures remain
 * distinguishable from ordinary predicate mismatches. Runtime read/copy failures are recorded and
 * thrown if that fact is used; they never become AIR/null/false. JVM Errors abort capture immediately.
 * This snapshot grants no world permission and does not prove freshness at a later publication.
 */
public final class StructureSnapshot {
    private final Object2ObjectOpenHashMap<StructureBounds, Fact<Boolean>> areas;
    private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Fact<?>> reads;
    private final List<Failure> failures;
    @Nullable private final StructureReadPlan.Builder trace;
    private long unavailableVisits;

    private StructureSnapshot(Object2ObjectOpenHashMap<StructureBounds, Fact<Boolean>> areas,
                              Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Fact<?>> reads, List<Failure> failures) {
        this.areas = areas;
        this.reads = reads;
        this.failures = failures;
        trace = null;
    }

    /** The runner physically joins its action before returning; false means dispatch was stopped. */
    @FunctionalInterface
    public interface JoinedWorker {
        boolean run(Runnable action);
    }

    /** Only detached facts survive a slice; every borrowed read port is closed after actual exit. */
    public static StructureOperation<StructureSnapshot> beginCapture(StructureReadPlan plan, StructureReadSource source,
                                                                    @Nullable JoinedWorker worker) {
        Objects.requireNonNull(plan); Objects.requireNonNull(source).requireServerThread();
        return new StructureOperation<>() {
            private final Object2ObjectOpenHashMap<StructureBounds, Fact<Boolean>> areas = new Object2ObjectOpenHashMap<>();
            private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Fact<?>> reads = new Object2ObjectOpenHashMap<>();
            private final ObjectArrayList<Failure> failures = new ObjectArrayList<>();
            private int areaIndex;
            private int readIndex;
            @Nullable private StructureOperation<Boolean> areaRead;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                source.requireServerThread();
                if (complete()) return true;
                while (areaIndex < plan.areas().size()) {
                    var bounds = plan.areas().get(areaIndex);
                    try {
                        if (areaRead == null) {
                            if (!work.take()) return false;
                            areaRead = worker == null ? source.beginAreaRead(bounds) : source.beginWorkerAreaRead(bounds);
                        }
                        if (!areaRead.advance(work)) return false;
                        areas.put(bounds, new Fact<>(areaRead.result(), null));
                    } catch (RuntimeException failure) {
                        areas.put(bounds, new Fact<>(null, failure)); failures.add(new Failure(bounds, null, failure));
                    }
                    areaRead = null; areaIndex++;
                }
                do {
                var beforeRead = readIndex;
                var batch = new ObjectArrayList<PendingRead<?>>();
                Throwable primary = null;
                try {
                    while (readIndex < plan.reads().size() && batch.size() < 256) {
                        var read = plan.reads().get(readIndex);
                        var loaded = areas.get(StructureBounds.at(read.position()));
                        if (loaded.failure() != null || !Boolean.TRUE.equals(loaded.value())) {
                            if (!work.take()) break;
                            readIndex++; continue;
                        }
                        var accounting = work instanceof StructureCheckPolicy.Work actual ? actual : null;
                        if (accounting == null ? !work.take(3) : !accounting.reserveAtomic(3)) break;
                        readIndex++;
                        try {
                            if (accounting != null) accounting.completeReserved();
                            if (!source.isAreaLoaded(StructureBounds.at(read.position()))) {
                                areas.put(StructureBounds.at(read.position()), new Fact<>(false, null)); continue;
                            }
                        } catch (RuntimeException failure) {
                            areas.put(StructureBounds.at(read.position()), new Fact<>(null, failure));
                            failures.add(new Failure(StructureBounds.at(read.position()), null, failure)); continue;
                        }
                        prepare(read, batch, accounting);
                    }
                    if (!batch.isEmpty()) {
                        boolean[] stopped = { false };
                        int[] completedReads = { 0 };
                        Runnable action = () -> {
                            for (var read : batch) {
                                if (!work.take(0)) { stopped[0] = true; return; }
                                read.capture(reads, failures, work);
                                completedReads[0]++;
                            }
                        };
                        if (worker == null) action.run();
                        else if (!worker.run(action)) { finish(null); return true; }
                        if (stopped[0]) {
                            readIndex = batch.get(completedReads[0]).index();
                            if (completedReads[0] == 0 && work instanceof StructureCheckPolicy.Work actual)
                                actual.atomicTimeInsufficient();
                            return false;
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    primary = failure;
                    throw failure;
                } finally {
                    closeReads(batch, work, primary);
                }
                if (readIndex == beforeRead && readIndex < plan.reads().size()) return false;
                } while (readIndex < plan.reads().size());
                if (readIndex < plan.reads().size() || !work.take()) return false;
                // All entries were added at their charged visits. This operation owns the list and
                // never mutates it after finish, so publication needs no second linear freeze pass.
                finish(new StructureSnapshot(areas, reads, Collections.unmodifiableList(failures)));
                return true;
            }

            private <T> void prepare(StructureReadPlan.Read<T> read, ObjectArrayList<PendingRead<?>> batch,
                                     @Nullable StructureCheckPolicy.Work accounting) {
                try {
                    if (accounting != null) accounting.completeReserved();
                    if (worker == null) {
                        var value = source.read(read.query(), read.position());
                        if (accounting != null) accounting.completeReserved();
                        reads.put(read, new Fact<>(read.query().copy(value), null));
                        return;
                    }
                    var window = source.beginWorkerRead(read.query(), read.position());
                    if (window == null) areas.put(StructureBounds.at(read.position()), new Fact<>(false, null));
                    else batch.add(new PendingRead<>(readIndex - 1, read, window));
                } catch (RuntimeException failure) {
                    reads.put(read, new Fact<>(null, failure)); failures.add(new Failure(StructureBounds.at(read.position()), read.query(), failure));
                }
            }
        };
    }

    private static void closeReads(ObjectArrayList<PendingRead<?>> batch, RecipeWorkBudget work, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (var read : batch) {
            if (work instanceof StructureCheckPolicy.Work actual) actual.recordCleanup();
            try { read.close(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (primary == null) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }

    private record PendingRead<T>(int index, StructureReadPlan.Read<T> read, StructureReadWindow<T> window) {
        private void capture(Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Fact<?>> reads, ObjectArrayList<Failure> failures, RecipeWorkBudget work) {
            if (work instanceof StructureCheckPolicy.Work actual) actual.completeReserved();
            try { reads.put(read, new Fact<>(read.query().copy(window.read()), null)); }
            catch (RuntimeException failure) {
                reads.put(read, new Fact<>(null, failure)); failures.add(new Failure(StructureBounds.at(read.position()), read.query(), failure));
            }
        }
        private void close() { window.close(); }
    }

    private StructureSnapshot(StructureReadPlan plan, StructureReadSource source) {
        areas = new Object2ObjectOpenHashMap<>();
        reads = new Object2ObjectOpenHashMap<>();
        trace = null;
        var problems = new ObjectArrayList<Failure>();
        for (var area : plan.areas()) {
            source.requireServerThread();
            try { areas.put(area, new Fact<>(source.isAreaLoaded(area), null)); }
            catch (RuntimeException failure) {
                areas.put(area, new Fact<>(null, failure));
                problems.add(new Failure(area, null, failure));
            }
        }
        for (var read : plan.reads()) {
            var area = areas.get(StructureBounds.at(read.position()));
            if (area.failure() != null || !Boolean.TRUE.equals(area.value())) { continue; }
            source.requireServerThread();
            capture(read, source, problems);
        }
        failures = List.copyOf(problems);
    }

    private StructureSnapshot(StructureSnapshot captured, StructureReadPlan.Builder trace) {
        areas = captured.areas;
        reads = captured.reads;
        failures = captured.failures;
        this.trace = Objects.requireNonNull(trace);
    }

    StructureSnapshot tracking(StructureReadPlan.Builder trace) { return new StructureSnapshot(this, trace); }

    /** All source access completes synchronously before the detached snapshot is returned. */
    public static StructureSnapshot capture(StructureReadPlan plan, StructureReadSource source) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(source).requireServerThread();
        return new StructureSnapshot(plan, source);
    }

    /** Full synchronous facade runs the same counted capture cursor. */
    @Nullable
    public static StructureSnapshot capture(StructureReadPlan plan, StructureReadSource source, RecipeWorkBudget work) {
        var operation = beginCapture(plan, source, null);
        return operation.advance(work) ? Objects.requireNonNull(operation.result()) : null;
    }

    public boolean isAreaLoaded(StructureBounds bounds) {
        var fact = areas.get(Objects.requireNonNull(bounds));
        if (fact == null) { throw new IllegalArgumentException("Undeclared structure area: " + bounds); }
        if (trace != null) { trace.area(bounds); }
        if (fact.failure() != null) { throw new IllegalStateException("Area capture failed: " + bounds, fact.failure()); }
        if (!Objects.requireNonNull(fact.value())) unavailableVisits = Math.incrementExact(unavailableVisits);
        return Objects.requireNonNull(fact.value());
    }

    /** Observations by this one matcher, used only for conservative external callback outcomes. */
    long unavailableVisits() {
        return unavailableVisits;
    }

    /** Null is an explicitly captured absent value, never an undeclared or unloaded read. */
    @Nullable
    @SuppressWarnings("unchecked") // Read identity binds query<T> to the Fact<T> written by capture.
    public <T> T read(StructureQuery<T> query, BlockPos position) {
        var request = new StructureReadPlan.Read<>(position, query);
        var fact = (Fact<T>) reads.get(request);
        if (fact == null) {
            if (!isAreaLoaded(StructureBounds.at(request.position()))) {
                throw new IllegalStateException("Cannot read an unavailable structure position: " + position);
            }
            throw new IllegalArgumentException("Undeclared structure read: " + query.id() + " at " + position);
        }
        if (trace != null) { trace.read(request.position(), query); }
        if (fact.failure() != null) { throw new IllegalStateException("Structure capture failed: " + query.id() + " at " + position, fact.failure()); }
        return query.copy(fact.value());
    }

    /** Includes failures in unvisited potential branches; callers must not silently discard diagnostics. */
    public List<Failure> failures() { return failures; }

    /** Failed rechecks request a fresh match: the changed branch may no longer visit that failure. */
    boolean equivalent(StructureSnapshot other, StructureReadPlan used) {
        return Boolean.TRUE.equals(equivalent(other, used, new RecipeWorkBudget(Long.MAX_VALUE))); }

    /** This complete proof occupies one owner window; an incomplete prefix grants no authority. */
    @Nullable
    Boolean equivalent(StructureSnapshot other, StructureReadPlan used, RecipeWorkBudget work) {
        for (var bounds : used.areas()) {
            if (!work.take()) return null;
            var first = areas.get(bounds);
            var second = other.areas.get(bounds);
            if (first == null || second == null || first.failure() != null || second.failure() != null
                || !Objects.equals(first.value(), second.value())) {
                return false;
            }
        }
        for (var read : used.reads()) {
            if (!work.take()) return null;
            if (!equivalentRead(other, read)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked") // Query identity binds both stored values to T.
    private <T> boolean equivalentRead(StructureSnapshot other, StructureReadPlan.Read<T> read) {
        var first = (Fact<T>) reads.get(read);
        var second = (Fact<T>) other.reads.get(read);
        return first != null && second != null && first.failure() == null && second.failure() == null
            && read.query().equivalent(first.value(), second.value());
    }

    private <T> void capture(StructureReadPlan.Read<T> read, StructureReadSource source, ObjectArrayList<Failure> problems) {
        try {
            reads.put(read, new Fact<>(read.query().copy(source.read(read.query(), read.position())), null));
        } catch (RuntimeException failure) {
            reads.put(read, new Fact<>(null, failure));
            problems.add(new Failure(StructureBounds.at(read.position()), read.query(), failure));
        }
    }

    public record Failure(StructureBounds area, @Nullable StructureQuery<?> query, RuntimeException cause) {
        public Failure { Objects.requireNonNull(area); Objects.requireNonNull(cause); }
    }

    private record Fact<T>(@Nullable T value, @Nullable RuntimeException failure) {}
}
