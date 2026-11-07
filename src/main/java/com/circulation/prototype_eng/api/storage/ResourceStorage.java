package com.circulation.prototype_eng.api.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;

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
                snapshot = ResourceStorage.this.snapshot();
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
                    return snapshot != null && context.isValid() && snapshot.revision() == context.revision();
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

    default boolean isValid() {
        return context().isValid();
    }
}
