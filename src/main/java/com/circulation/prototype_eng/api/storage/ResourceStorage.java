package com.circulation.prototype_eng.api.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Independent thread-safe resource balance. AE2 provides resource identity, not storage semantics. */
public interface ResourceStorage {
    StorageContext context();
    StorageSnapshot snapshot();

    /**
     * A revision-bound one-key planning view. External implementations may retain their snapshot callback.
     */
    default StorageSnapshot snapshotFor(AEKey key) {
        return snapshot();
    }

    /**
     * Owner-side quantity capture; implementations may copy incrementally under their own lock.
     */
    interface QuantityCapture {
        boolean advance(RecipeWorkBudget budget);

        AEKey2LongMap available();

        boolean current();
    }

    /**
     * Legacy external snapshots remain one cooperative callback; built-in buffers override this.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Legacy capture retains its original storage callback scope.
    default QuantityCapture beginQuantityCapture() {
        return new QuantityCapture() {
            private StorageSnapshot snapshot;

            @Override
            public boolean advance(RecipeWorkBudget budget) {
                if (snapshot != null) {
                    return true;
                }
                if (!budget.take()) {
                    return false;
                }
                var captured = ResourceStorage.this.snapshot();
                if (captured.context() != ResourceStorage.this.context()) {
                    throw new IllegalStateException("Input snapshot changed its physical context");
                }
                snapshot = captured;
                return true;
            }

            @Override
            public AEKey2LongMap available() {
                if (snapshot == null) {
                    throw new IllegalStateException("Quantity capture is incomplete");
                }
                return snapshot.available();
            }

            @Override
            public boolean current() {
                var context = context();
                context.lock();
                try {
                    return snapshot != null && context.current(snapshot.revision());
                } finally {
                    context.unlock();
                }
            }
        };
    }
    long amount(AEKey key);
    long availableAmount(AEKey key);
    long insert(AEKey key, long amount, Actionable mode);
    long extract(AEKey key, long amount, Actionable mode);
    ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount);
    ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount);

    /** Owner capture; result transfers an accepted token only after capture fully exits.
     * cancel runs under the original context lock, including its exact source lane. */
    interface ReservationCapture {
        boolean advance(RecipeWorkBudget budget);
        ReservationResult result();
        long amount();
        boolean hasObligations();
        boolean cancel(RecipeWorkBudget budget);
        /** Legacy owner settlement keeps the original context lock through complete compensation. */
        default boolean cancel() { return cancel(new RecipeWorkBudget(Long.MAX_VALUE)); }
        /** Exact cleanup query requires the original context scope. UNKNOWN forbids replay after a throw. */
        default ResourceReservation.Resolution resolution() { return ResourceReservation.Resolution.UNKNOWN; }
        default @Nullable ResourceReservation.Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            return budget.take() ? resolution() : null;
        }
    }

    /** Reserves up to amount; amount() reports the actual input portion for cross-source composition. */
    default ReservationCapture beginReserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
        return new LegacyReservationCapture(this, snapshot, key, amount, false);
    }

    /** Reserves up to maxAmount; amount() reports the actual output portion, including zero. */
    default ReservationCapture beginReserveInsert(StorageSnapshot snapshot, AEKey key, long maxAmount) {
        return new LegacyReservationCapture(this, snapshot, key, maxAmount, true);
    }

    /** One external legacy simulation/reserve/cancel callback per actual work unit. */
    final class LegacyReservationCapture implements ReservationCapture {
        private final Thread owner = Thread.currentThread();
        private final ResourceStorage storage;
        private final StorageSnapshot snapshot;
        private final AEKey key;
        private final long maximum;
        private final boolean insert;
        private int phase;
        private long amount;
        private ReservationResult result;
        private boolean attempted, transferred, cancelled, uncertainCancel;
        private LegacyReservationCapture(ResourceStorage storage, StorageSnapshot snapshot, AEKey key, long maximum, boolean insert) {
            this.storage = storage; this.snapshot = Objects.requireNonNull(snapshot); this.key = Objects.requireNonNull(key);
            if (maximum <= 0) { throw new IllegalArgumentException("A reservation needs a positive amount"); }
            this.maximum = maximum; amount = maximum; this.insert = insert;
        }
        private void requireOwner() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Reservation capture requires its creating owner"); }
        }
        @Override public boolean advance(RecipeWorkBudget budget) {
            requireOwner();
            if (result != null || cancelled) { return true; }
            if (attempted) { throw new IllegalStateException("Legacy reservation callback has unenumerated effects"); }
            if (phase == 0) {
                if (!budget.take()) { return false; }
                amount = insert ? storage.insert(key, maximum, Actionable.SIMULATE) : storage.extract(key, maximum, Actionable.SIMULATE);
                if (amount < 0 || amount > maximum) { throw new IllegalStateException("Storage violated simulated transfer contract"); }
                phase = 1;
                if (amount == 0) { result = new ReservationResult(ReservationResult.Status.INSUFFICIENT, null); return true; }
            }
            if (!budget.take()) { return false; }
            attempted = true;
            result = insert ? storage.reserveInsert(snapshot, key, amount) : storage.reserveExtract(snapshot, key, amount);
            if (result.status() != ReservationResult.Status.ACCEPTED) { amount = 0; }
            return true;
        }
        @Override public ReservationResult result() {
            requireOwner();
            if (result == null) { throw new IllegalStateException("Reservation capture is incomplete"); }
            transferred = result.status() == ReservationResult.Status.ACCEPTED; return result;
        }
        @Override public long amount() {
            requireOwner(); if (result == null) { throw new IllegalStateException("Reservation capture is incomplete"); } return amount;
        }
        @Override public boolean hasObligations() {
            requireOwner(); return !cancelled && !transferred && attempted && (result == null || result.status() == ReservationResult.Status.ACCEPTED);
        }
        @Override public ResourceReservation.Resolution resolution() {
            storage.context().requireLock();
            if (cancelled) { return ResourceReservation.Resolution.ROLLED_BACK; }
            if (result != null && result.status() == ReservationResult.Status.ACCEPTED) {
                return Objects.requireNonNull(result.reservation()).resolution();
            }
            return attempted && result == null ? ResourceReservation.Resolution.UNKNOWN : ResourceReservation.Resolution.REVERSIBLE;
        }
        @Override public @Nullable ResourceReservation.Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            storage.context().requireLock();
            if (!cancelled && result != null && result.status() == ReservationResult.Status.ACCEPTED) {
                return Objects.requireNonNull(result.reservation()).resolutionWithinBudget(budget);
            }
            return budget.take() ? resolution() : null;
        }
        @Override public boolean cancel(RecipeWorkBudget budget) {
            storage.context().requireLock();
            if (cancelled || transferred) { return true; }
            if (attempted && result == null) { throw new IllegalStateException("Legacy capture retains unenumerated effects"); }
            if (result != null && result.status() == ReservationResult.Status.ACCEPTED) {
                if (!budget.take()) { return false; }
                var token = Objects.requireNonNull(result.reservation()); var context = token.context(); context.lock();
                try {
                    if (uncertainCancel) {
                        var resolution = token.resolution();
                        if (resolution == ResourceReservation.Resolution.ROLLED_BACK) { cancelled = true; }
                        else if (resolution == ResourceReservation.Resolution.UNKNOWN) { throw new IllegalStateException("Legacy capture retains unknown cancellation effects"); }
                        else if (resolution == ResourceReservation.Resolution.RESERVED || resolution == ResourceReservation.Resolution.APPLIED
                            || resolution == ResourceReservation.Resolution.REVERSIBLE) { uncertainCancel = false; return false; }
                        else { throw new IllegalStateException("Legacy reservation cancel contradicts exact token outcome"); }
                    } else {
                        uncertainCancel = true; token.rollback(); cancelled = true;
                    }
                } finally { context.unlock(); }
            } else { cancelled = true; }
            amount = 0; result = new ReservationResult(ReservationResult.Status.INSUFFICIENT, null); return true;
        }
    }

    default boolean isValid() {
        return context().isValid();
    }
}
