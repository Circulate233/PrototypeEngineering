package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Detached structure facts for worker matching. Unavailable optional branches and failures remain
 * distinguishable from ordinary predicate mismatches. Runtime read/copy failures are recorded and
 * thrown if that fact is used; they never become AIR/null/false. JVM Errors abort capture immediately.
 * This snapshot grants no world permission and does not prove freshness at a later publication.
 */
public final class StructureSnapshot {
    private final Object2ObjectOpenHashMap<StructureBounds, Fact<Boolean>> areas = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Fact<?>> reads = new Object2ObjectOpenHashMap<>();
    private final List<Failure> failures;

    private StructureSnapshot(StructureReadPlan plan, StructureReadSource source) {
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

    /** All source access completes synchronously before the detached snapshot is returned. */
    public static StructureSnapshot capture(StructureReadPlan plan, StructureReadSource source) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(source).requireServerThread();
        return new StructureSnapshot(plan, source);
    }

    public boolean isAreaLoaded(StructureBounds bounds) {
        var fact = areas.get(Objects.requireNonNull(bounds));
        if (fact == null) { throw new IllegalArgumentException("Undeclared structure area: " + bounds); }
        if (fact.failure() != null) { throw new IllegalStateException("Area capture failed: " + bounds, fact.failure()); }
        return Objects.requireNonNull(fact.value());
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
        if (fact.failure() != null) { throw new IllegalStateException("Structure capture failed: " + query.id() + " at " + position, fact.failure()); }
        return query.copy(fact.value());
    }

    /** Includes failures in unvisited potential branches; callers must not silently discard diagnostics. */
    public List<Failure> failures() { return failures; }

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
