package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;

import java.time.Duration;
import java.util.Objects;

/**
 * Local structure work policy; a retained attempt never renews its deadline.
 */
public record StructureCheckPolicy(long workUnits, Duration sliceTime, Duration timeout, FinalMode finalMode) {
    public static final StructureCheckPolicy DEFAULT = new StructureCheckPolicy(4096, Duration.ofMillis(5), Duration.ofSeconds(5), FinalMode.COOPERATIVE);

    public StructureCheckPolicy(long workUnits, Duration timeout, FinalMode finalMode) {
        this(workUnits, Duration.ofMillis(50), timeout, finalMode);
    }

    public StructureCheckPolicy {
        if (workUnits <= 0 || Objects.requireNonNull(timeout).isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Structure work allowance and timeout must be positive");
        Objects.requireNonNull(finalMode);
        if (Objects.requireNonNull(sliceTime).isNegative() || sliceTime.isZero())
            throw new IllegalArgumentException("Structure slice time must be positive");
    }

    public enum FinalMode {COOPERATIVE, STRICT}

    public enum Status {IDLE, YIELD, MATCHED, MISMATCH, UNAVAILABLE, NEEDS_MORE_BUDGET, EXPIRED}

    /**
     * Counts successful units on either thread, including a cooperative tail beyond its soft slice.
     */
    public static final class Work extends RecipeWorkBudget {
        private final PlannedDeadline deadline;
        private final boolean cooperative;
        private final PlannedDeadline sliceDeadline;
        private final long allowance;
        private long spent;
        private long atomicMinimum;
        private boolean atomicTimeInsufficient;
        private boolean exhausted;

        public Work(long units, PlannedDeadline deadline, Duration sliceTime, boolean cooperative) {
            this(units, deadline, PlannedDeadline.after(sliceTime), cooperative);
        }

        private Work(long units, PlannedDeadline deadline, PlannedDeadline sliceDeadline, boolean cooperative) {
            super(units);
            this.deadline = Objects.requireNonNull(deadline);
            this.cooperative = cooperative;
            this.sliceDeadline = sliceDeadline;
            allowance = units;
        }

        @Override
        public boolean take(long units) {
            if (units < 0) throw new IllegalArgumentException("Negative structure work cost");
            if (deadline.expired(System.nanoTime())) return false;
            if (!cooperative && sliceDeadline.expired(System.nanoTime())) {
                exhausted = true;
                return false;
            }
            if (!cooperative && !super.take(units)) {
                exhausted = true;
                if (units > allowance) atomicMinimum = Math.max(atomicMinimum, units);
                return false;
            }
            if (cooperative) super.take(Math.min(units, remaining()));
            spent = Math.addExact(spent, units);
            return true;
        }

        public long spent() {
            return spent;
        }

        public boolean expired() {
            return deadline.expired(System.nanoTime());
        }

        public boolean exhausted() {
            return exhausted;
        }

        /**
         * An indivisible live read or installation cannot safely retain only a cross-tick prefix.
         */
        public long atomicMinimum() {
            return atomicMinimum;
        }

        /**
         * A borrowed batch could not read any fact before its local time window ended.
         */
        public void atomicTimeInsufficient() {
            atomicTimeInsufficient = true;
        }

        public boolean needsAtomicTime() {
            return atomicTimeInsufficient;
        }

        /**
         * Admission consumes allowance; actual accepted writes/callbacks are recorded separately.
         */
        public boolean reserveAtomic(long units) {
            if (!take(units)) return false;
            spent -= units;
            return true;
        }

        public void completeReserved() {
            spent = Math.incrementExact(spent);
        }

        /**
         * Borrowed ports must close even after expiry; this cost cannot revoke accepted cleanup.
         */
        public void recordCleanup() {
            super.take(Math.min(1, remaining()));
            spent = Math.incrementExact(spent);
        }

        public Work tail(boolean cooperative) {
            return new Work(remaining(), deadline, sliceDeadline, cooperative);
        }
    }
}
