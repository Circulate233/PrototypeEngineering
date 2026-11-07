package com.circulation.prototype_eng.api.storage;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One transferable isolation boundary for a deferred batch. A fence retains logical ownership, not
 * physical locks, between source work and owner settlement. The caller releases it only after every
 * participant has reached a confirmed terminal state; releasing never commits or compensates tokens.
 */
public final class StorageFence {
    private static final ThreadLocal<Access> CURRENT_ACCESS = new ThreadLocal<>();

    private final Thread owner = Thread.currentThread();
    private final ObjectArrayList<StorageContext> contexts;
    private int activeAccesses;
    private boolean released;

    private StorageFence(ObjectArrayList<StorageContext> contexts) {
        this.contexts = contexts;
    }

    /** Conflict never waits and never leaves a partial claim. Call without any storage lock or scope. */
    public static @Nullable StorageFence tryAcquire(Collection<StorageContext> contexts) {
        Objects.requireNonNull(contexts);
        requireOutsideAccess();
        StorageContext.requireNoLocks();
        var unique = new ReferenceOpenHashSet<StorageContext>();
        var ordered = new ObjectArrayList<StorageContext>();
        for (var context : contexts) {
            if (unique.add(Objects.requireNonNull(context))) {
                ordered.add(context);
            }
        }
        ordered.sort(Comparator.comparingLong(StorageContext::order));
        int locked = 0;
        try {
            for (var context : ordered) {
                if (!context.tryPhysicalLock()) { return null; }
                locked++;
                if (context.fence() != null) { return null; }
            }
            var fence = new StorageFence(ordered);
            ordered.forEach(context -> context.setFence(fence));
            return fence;
        } finally {
            for (int index = locked - 1; index >= 0; index--) {
                ordered.get(index).unlockPhysical();
            }
        }
    }

    /** Runs reversible owner business work under all enrolled physical locks, in their stable order. */
    public void access(Runnable action) {
        Objects.requireNonNull(action);
        access(() -> {
            action.run();
            return null;
        });
    }

    public <T> T access(Supplier<T> action) {
        requireOwner();
        return access(new Access(this, null), Objects.requireNonNull(action));
    }

    /** Runs one source action under its physical lock. Its serial execution lane is checked by the caller. */
    public void access(StorageContext context, Runnable action) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(action);
        if (!contexts.contains(context)) {
            throw new IllegalArgumentException("Source context does not belong to this fence");
        }
        access(new Access(this, context), () -> {
            action.run();
            return null;
        });
    }

    private <T> T access(Access access, Supplier<T> action) {
        requireOutsideAccess();
        StorageContext.requireNoLocks();
        synchronized (this) {
            if (released) { throw new IllegalStateException("Storage fence has been released"); }
            activeAccesses = Math.incrementExact(activeAccesses);
        }
        CURRENT_ACCESS.set(access);
        Throwable primary = null;
        int locked = 0;
        try {
            if (access.context == null) {
                for (var context : contexts) {
                    context.lock();
                    locked++;
                }
            } else {
                access.context.lock();
                locked = 1;
            }
            return action.get();
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            boolean leaked = false;
            if (access.context == null) {
                for (int index = locked - 1; index >= 0; index--) {
                    leaked |= contexts.get(index).finishAccess();
                }
            } else if (locked != 0) {
                leaked = access.context.finishAccess();
            }
            CURRENT_ACCESS.remove();
            synchronized (this) {
                activeAccesses--;
            }
            if (leaked) {
                var failure = new IllegalStateException("Storage fence scope leaked a nested physical lock");
                if (primary == null) { throw failure; }
                primary.addSuppressed(failure);
            }
        }
    }

    /**
     * Only the creating owner may release, after all scopes actually exit. A failure leaves either the
     * whole claim active or the whole claim released. Notification faults never revive ownership.
     */
    public void release() {
        requireOwner();
        requireOutsideAccess();
        StorageContext.requireNoLocks();
        boolean[] changed = new boolean[contexts.size()];
        synchronized (this) {
            if (released) { throw new IllegalStateException("Storage fence has already been released"); }
            if (activeAccesses != 0) { throw new IllegalStateException("Storage fence still has active source or owner work"); }
            contexts.forEach(StorageContext::lockPhysical);
            try {
                for (var context : contexts) {
                    if (context.fence() != this) {
                        throw new IllegalStateException("Storage fence lost its context claim");
                    }
                }
                for (int index = 0; index < contexts.size(); index++) {
                    changed[index] = contexts.get(index).takeFenceChanges();
                }
                contexts.forEach(context -> context.setFence(null));
                released = true;
            } finally {
                for (int index = contexts.size() - 1; index >= 0; index--) {
                    contexts.get(index).unlockPhysical();
                }
            }
            notifyAll();
        }
        Throwable failure = null;
        for (int index = 0; index < contexts.size(); index++) {
            if (!changed[index]) { continue; }
            try {
                contexts.get(index).publishFenceChanges();
            } catch (RuntimeException | Error notification) {
                if (failure == null) { failure = notification; }
                else if (failure != notification) { failure.addSuppressed(notification); }
            }
        }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Storage fence owner work requires its creating thread");
        }
    }

    synchronized boolean released() { return released; }

    static void requireOutsideAccess() {
        if (CURRENT_ACCESS.get() != null) {
            throw new IllegalStateException("Storage fence scopes and new batches must not nest");
        }
    }

    static boolean hasAccess() {
        return CURRENT_ACCESS.get() != null;
    }

    static void checkAccess(StorageContext context, @Nullable StorageFence fence) {
        var access = CURRENT_ACCESS.get();
        if (access != null && (access.fence != fence || access.context != null && access.context != context)) {
            throw new IllegalStateException("Storage fence scope cannot access another batch or storage domain");
        }
    }

    static boolean permits(StorageContext context, StorageFence fence) {
        var access = CURRENT_ACCESS.get();
        return access != null && access.fence == fence && (access.context == null || access.context == context);
    }

    void awaitRelease() {
        requireOutsideAccess();
        StorageContext.requireNoLocks();
        if (Thread.currentThread() == owner) {
            throw new IllegalStateException("Storage fence owner must enter its explicit access scope");
        }
        boolean interrupted = false;
        synchronized (this) {
            while (!released) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) { Thread.currentThread().interrupt(); }
    }

    void rejectOwnerWithoutAccess(StorageContext context) {
        if (Thread.currentThread() == owner && !permits(context, this)) {
            throw new IllegalStateException("Storage fence owner must enter its explicit access scope");
        }
    }

    private record Access(StorageFence fence, @Nullable StorageContext context) {
    }
}
