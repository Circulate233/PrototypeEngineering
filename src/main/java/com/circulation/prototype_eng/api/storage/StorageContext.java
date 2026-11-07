package com.circulation.prototype_eng.api.storage;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.Nullable;

/** Shared identity, lifetime and lock of one physical balance, including all of its adapters. */
public final class StorageContext {
    private static final VarHandle NEXT_ORDER;
    private static final VarHandle DIRTY;
    private static final VarHandle REVISION;
    private static final ThreadLocal<HeldLocks> HELD_LOCKS = ThreadLocal.withInitial(HeldLocks::new);
    @SuppressWarnings("unused") // Mutated through NEXT_ORDER, shared by all contexts.
    private static long nextOrder;

    static {
        try {
            var lookup = MethodHandles.lookup();
            NEXT_ORDER = lookup.findStaticVarHandle(StorageContext.class, "nextOrder", long.class);
            DIRTY = lookup.findVarHandle(StorageContext.class, "dirty", boolean.class);
            REVISION = lookup.findVarHandle(StorageContext.class, "revision", long.class);
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
    // Credits promised to exact tokens, separate from the one lifetime invalidation change.
    private long reservedChanges;
    @Nullable private volatile StorageFence fence;

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
        while (true) {
            var currentFence = fence;
            StorageFence.checkAccess(this, currentFence);
            if (currentFence != null) {
                currentFence.rejectOwnerWithoutAccess(this);
                if (!StorageFence.permits(this, currentFence)) { requireNoLocks(); }
            }
            lock.lock();
            StorageFence blocked;
            try {
                var lockedFence = fence;
                StorageFence.checkAccess(this, lockedFence);
                if (lockedFence == null || StorageFence.permits(this, lockedFence)) {
                    HELD_LOCKS.get().count++;
                    return;
                }
                blocked = lockedFence;
            } catch (RuntimeException | Error failure) {
                lock.unlock();
                throw failure;
            }
            lock.unlock();
            blocked.awaitRelease();
        }
    }

    /**
     * Acquires an already ordered, identity-distinct list. Retry/wait never retains an earlier domain
     * lock. Use this instead of looping over lock() when a normal operation touches multiple domains.
     */
    public static void lockAll(List<StorageContext> contexts) {
        long previous = -1;
        for (var context : contexts) {
            if (Objects.requireNonNull(context).order <= previous) {
                throw new IllegalArgumentException("Storage contexts must be distinct and ordered");
            }
            previous = context.order;
        }
        if (StorageFence.hasAccess()) {
            contexts.forEach(context -> StorageFence.checkAccess(context, context.fence));
            contexts.forEach(StorageContext::lock);
            return;
        }
        requireNoLocks();
        while (true) {
            int locked = 0;
            try {
                for (var context : contexts) {
                    if (!context.tryOrdinaryLock()) { break; }
                    locked++;
                }
                if (locked == contexts.size()) { return; }
            } catch (RuntimeException | Error failure) {
                for (int index = locked - 1; index >= 0; index--) {
                    contexts.get(index).unlockDeferred();
                }
                throw failure;
            }
            for (int index = locked - 1; index >= 0; index--) {
                contexts.get(index).unlockDeferred();
            }
            var blocked = contexts.get(locked);
            blocked.lock();
            blocked.unlockDeferred();
        }
    }

    private boolean tryOrdinaryLock() {
        if (!lock.tryLock()) { return false; }
        if (fence != null) {
            lock.unlock();
            return false;
        }
        HELD_LOCKS.get().count++;
        return true;
    }

    public void unlock() {
        unlockDeferred();
        if (!lock.isHeldByCurrentThread()) {
            publishChanges();
        }
    }

    /** Batch coordinators release all participants before publishing any callbacks. */
    public void unlockDeferred() {
        if (StorageFence.hasAccess() && lock.isHeldByCurrentThread() && lock.getHoldCount() == 1) {
            throw new IllegalStateException("Cannot release a physical lock owned by the fence scope");
        }
        lock.unlock();
        HELD_LOCKS.get().count--;
    }

    public void requireLock() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Storage access requires its shared lock");
        }
        StorageFence.checkAccess(this, fence);
        if (fence != null && !StorageFence.permits(this, fence)) {
            throw new IllegalStateException("Storage access requires its fence scope");
        }
    }

    /** Exact transferable isolation proof for exposing reversible effects to later sequence steps. */
    public StorageFence requireFenceAccess() {
        requireLock();
        var activeFence = fence;
        if (activeFence == null) { throw new IllegalStateException("Sequential effects require retained fence isolation"); }
        return activeFence;
    }

    /** Pin isolation before releasing an applied reservation's compensation holds. */
    public StorageFence retainSequentialReservation() {
        var activeFence = requireFenceAccess();
        activeFence.retainSequentialReservation();
        return activeFence;
    }

    /** Release one exact exposure only after its reservation has reached a confirmed terminal state. */
    public void releaseSequentialReservation(StorageFence expected) {
        if (requireFenceAccess() != Objects.requireNonNull(expected)) {
            throw new IllegalStateException("Sequential reservation belongs to another fence");
        }
        expected.releaseSequentialReservation();
    }

    public long revision() {
        requireLock();
        return revision;
    }

    /**
     * Read-only proof for contents previously captured under this context's lock. This samples a
     * published version without entering another storage domain, including from an unrelated
     * transaction's fence. It neither reads balances nor reserves future access to this context.
     */
    public boolean current(long expectedRevision) {
        return valid && (long) REVISION.getAcquire(this) == expectedRevision;
    }

    /** Preflight an ordinary mutation without spending token cleanup or lifetime invalidation credits. */
    public void requireChange() { requireChange(0); }

    /** Preflight one ordinary mutation that also promises additional future token changes. */
    public void requireChange(int additionalReservedChanges) {
        requireLock();
        if (additionalReservedChanges < 0) {
            throw new IllegalArgumentException("Negative reserved revision changes");
        }
        long available = Long.MAX_VALUE - revision - reservedChanges - (valid ? 1 : 0);
        if (available <= additionalReservedChanges) {
            throw new IllegalStateException("Storage revision has no unreserved change capacity");
        }
    }

    /** Retain future changes before recording the reservation's own ordinary availability change. */
    public void reserveChanges(int changes) {
        if (changes <= 0) { throw new IllegalArgumentException("A revision reservation must be positive"); }
        requireChange(changes);
        reservedChanges += changes;
    }

    /** Preflight a token mutation. The token retains its own remaining credit count until terminal. */
    public void requireReservedChange(int remainingChanges) {
        requireLock();
        if (remainingChanges <= 0 || reservedChanges < remainingChanges || revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Storage token lost its reserved revision changes");
        }
    }

    /**
     * Record one token mutation using its promised credit. Terminal cleanup also returns any unused
     * apply credit; ordinary mutations cannot spend these credits, including after invalidation.
     */
    public void reservedChanged(int remainingChanges, boolean terminal) {
        requireReservedChange(remainingChanges);
        reservedChanges -= terminal ? remainingChanges : 1;
        recordChange();
    }

    /** Called after ordinary quantity/availability preflight, while the same shared lock remains held. */
    public void changed() {
        requireChange();
        recordChange();
    }

    private void recordChange() {
        REVISION.setRelease(this, revision + 1);
        DIRTY.setRelease(this, true);
    }

    /** The callback runs on the caller thread and must not access world state from a worker. */
    public void publishChanges() {
        StorageFence.checkAccess(this, fence);
        if (HELD_LOCKS.get().count != 0 || StorageFence.hasAccess() || fence != null) {
            return;
        }
        boolean publish;
        lock.lock();
        try {
            publish = fence == null && (boolean) DIRTY.getAndSet(this, false);
        } finally {
            lock.unlock();
        }
        if (publish) {
            onChanged.run();
        }
    }

    /** One-way invalidation. Replacement hosts must create a new context. */
    public void invalidate() {
        lock();
        try {
            if (valid) {
                // Ordinary mutations always preserve this independent lifetime credit.
                if (Long.MAX_VALUE - revision <= reservedChanges) {
                    throw new IllegalStateException("Storage lost its lifetime invalidation revision credit");
                }
                valid = false;
                recordChange();
            }
        } finally {
            unlock();
        }
    }

    /**
     * Package-local boundary fixture for testing exhaustion with real buffers, without reflection.
     * Only a wholly unused context may be initialized; this is neither public configuration nor a
     * persistence restoration API. Ordinary construction always starts at revision zero.
     */
    void initializeRevision(long initialRevision) {
        requireLock();
        StorageFence.requireOutsideAccess();
        if (initialRevision < 0 || initialRevision == Long.MAX_VALUE || !valid || revision != 0
            || reservedChanges != 0 || fence != null || dirty) {
            throw new IllegalStateException("Revision initialization requires an unused storage context");
        }
        revision = initialRevision;
    }

    static void requireNoLocks() {
        if (HELD_LOCKS.get().count != 0) {
            throw new IllegalStateException("Cannot wait for or acquire a storage fence while holding storage locks");
        }
    }

    boolean tryPhysicalLock() { return lock.tryLock(); }
    void lockPhysical() { lock.lock(); }
    void unlockPhysical() { lock.unlock(); }
    @Nullable StorageFence fence() { return fence; }
    void setFence(@Nullable StorageFence fence) { this.fence = fence; }
    boolean takeFenceChanges() { return (boolean) DIRTY.getAndSet(this, false); }
    void publishFenceChanges() { onChanged.run(); }

    /** Scope teardown reports and unwinds leaked reentrant locks instead of stranding ownership. */
    boolean finishAccess() {
        int held = lock.getHoldCount();
        boolean leaked = held != 1;
        for (int index = 0; index < held; index++) {
            lock.unlock();
            HELD_LOCKS.get().count--;
        }
        return leaked;
    }

    private static final class HeldLocks {
        private int count;
    }
}
