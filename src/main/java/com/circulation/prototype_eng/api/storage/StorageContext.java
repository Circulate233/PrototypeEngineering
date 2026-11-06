package com.circulation.prototype_eng.api.storage;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Shared identity, lifetime and lock of one physical balance, including all of its adapters. */
public final class StorageContext {
    private static final VarHandle NEXT_ORDER;
    private static final VarHandle DIRTY;
    @SuppressWarnings("unused") // Mutated through NEXT_ORDER, shared by all contexts.
    private static long nextOrder;

    static {
        try {
            var lookup = MethodHandles.lookup();
            NEXT_ORDER = lookup.findStaticVarHandle(StorageContext.class, "nextOrder", long.class);
            DIRTY = lookup.findVarHandle(StorageContext.class, "dirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final long order = (long) NEXT_ORDER.getAndAdd(1L);
    private final ReentrantLock lock = new ReentrantLock();
    private final Runnable onChanged;
    private volatile boolean valid = true;
    @SuppressWarnings("unused") // Mutated through DIRTY to coalesce cross-thread notifications.
    private boolean dirty;
    private long revision;

    public StorageContext(Runnable onChanged) {
        this.onChanged = Objects.requireNonNull(onChanged);
        if (order < 0) {
            throw new IllegalStateException("Storage lock order exhausted");
        }
    }

    public long order() {
        return order;
    }

    public boolean isValid() {
        return valid;
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        unlockDeferred();
        if (!lock.isHeldByCurrentThread()) {
            publishChanges();
        }
    }

    /** Batch coordinators release all participants before publishing any callbacks. */
    public void unlockDeferred() {
        lock.unlock();
    }

    public void requireLock() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Storage access requires its shared lock");
        }
    }

    public long revision() {
        requireLock();
        return revision;
    }

    /** Called under the shared lock for a committed quantity or availability change. */
    public void changed() {
        requireLock();
        revision = Math.incrementExact(revision);
        DIRTY.setRelease(this, true);
    }

    /** The callback runs on the caller thread and must not access world state from a worker. */
    public void publishChanges() {
        if (lock.isHeldByCurrentThread()) {
            return;
        }
        if ((boolean) DIRTY.getAndSet(this, false)) {
            onChanged.run();
        }
    }

    /** One-way invalidation. Replacement hosts must create a new context. */
    public void invalidate() {
        lock();
        try {
            if (valid) {
                valid = false;
                changed();
            }
        } finally {
            unlock();
        }
    }
}
