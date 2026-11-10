package com.circulation.prototype_eng.api.storage;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One retained descriptor; pending/draining links also block ordinary access. No scope crosses yield.
 */
public final class StorageFence {
    private static final ThreadLocal<Access> CURRENT_ACCESS = new ThreadLocal<>();
    private static final ThreadLocal<SourceWork> CURRENT_SOURCE_WORK = new ThreadLocal<>();
    private static final VarHandle PHASE;
    private static final int PREPARING = 0, CLAIMING = 1, ACTIVE = 2, DRAINING = 3, RELEASED = 4;

    static {
        try {
            PHASE = MethodHandles.lookup().findVarHandle(StorageFence.class, "phase", int.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final Thread owner = Thread.currentThread();
    private final ReferenceOpenHashSet<StorageContext> membership = new ReferenceOpenHashSet<>();
    private ObjectArrayList<StorageContext> contexts = new ObjectArrayList<>();
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    // PHASE release/acquire writes publish each exact claim and release.
    private int phase = PREPARING;
    // All gate transitions share this monitor: source exit -> owner enter -> source enter is HB.
    private boolean ownerAccess;
    private int sourceAccesses, sequentialReservations, claimed;
    private @Nullable Release release;

    private StorageFence() {
    }

    /**
     * Input must stay stable until this creating-owner cursor ends or is cancelled.
     */
    public static Acquire beginAcquire(Collection<StorageContext> contexts) {
        requireOutsideAccess();
        requireOutsideSourceWork();
        StorageContext.requireNoLocks();
        return new Acquire(Objects.requireNonNull(contexts).iterator());
    }

    /**
     * Null never retains an effective partial claim. Legacy synchronous compatibility.
     */
    @SuppressWarnings("StatementWithEmptyBody") // The original cursor performs every synchronous drain step.
    public static @Nullable StorageFence tryAcquire(Collection<StorageContext> contexts) {
        var acquisition = beginAcquire(contexts);
        while (!acquisition.advance(() -> true)) {
        }
        return acquisition.result();
    }

    static void requireOutsideAccess() {
        if (CURRENT_ACCESS.get() != null) {
            throw new IllegalStateException("Storage fence scopes and new batches must not nest");
        }
    }

    static void requireOutsideSourceWork() {
        if (CURRENT_SOURCE_WORK.get() != null) {
            throw new IllegalStateException("Storage boundaries cannot nest inside actual source work");
        }
    }

    static boolean hasAccess() {
        return CURRENT_ACCESS.get() != null || CURRENT_SOURCE_WORK.get() != null;
    }

    static boolean budgetOwnerAccess() {
        var access = CURRENT_ACCESS.get();
        return access != null && access.budgetOwner;
    }

    static void checkAccess(StorageContext context, @Nullable StorageFence fence) {
        var access = CURRENT_ACCESS.get();
        if (access == null && CURRENT_SOURCE_WORK.get() != null) {
            throw new IllegalStateException("Source work must enter its exact physical access scope");
        }
        if (access != null && (access.fence != fence || !access.fence.membership.contains(context)
            || access.context != null && access.context != context || access.fence.phase() != ACTIVE)) {
            throw new IllegalStateException("Storage fence scope cannot access another batch or storage domain");
        }
    }

    static boolean permits(StorageContext context, StorageFence fence) {
        var access = CURRENT_ACCESS.get();
        return access != null && access.fence == fence && fence.phase() == ACTIVE
            && fence.membership.contains(context) && (access.context == null || access.context == context);
    }

    static boolean ownerCapability(StorageContext context, @Nullable StorageFence fence) {
        var access = CURRENT_ACCESS.get();
        return access != null && access.budgetOwner && fence != null && permits(context, fence);
    }

    static boolean ownsBaseLock(StorageContext context) {
        var access = CURRENT_ACCESS.get();
        return access != null && !access.budgetOwner && (access.context == null || access.context == context);
    }

    static void acquiredExplicitLock(StorageContext context) {
        var access = CURRENT_ACCESS.get();
        if (access != null && access.budgetOwner) {
            access.explicitLocks.add(context);
        }
    }

    static void releasedExplicitLock(StorageContext context) {
        var access = CURRENT_ACCESS.get();
        if (access != null && access.budgetOwner) {
            access.explicitLocks.remove(context);
        }
    }

    /**
     * Legacy owner scope automatically holds every member physical base lock.
     */
    public void access(Runnable action) {
        Objects.requireNonNull(action);
        access(() -> {
            action.run();
            return null;
        });
    }

    public <T> T access(Supplier<T> action) {
        requireOwner();
        return runAccess(new Access(this, null, false), Objects.requireNonNull(action), false).value;
    }

    /**
     * A source always holds its single physical base lock; distinct sources may run concurrently.
     */
    public void access(StorageContext context, Runnable action) {
        Objects.requireNonNull(action);
        if (!membership.contains(Objects.requireNonNull(context))) {
            throw new IllegalArgumentException("Source context does not belong to this fence");
        }
        runAccess(new Access(this, context, false), () -> {
            action.run();
            return null;
        }, false);
    }

    <T> T sourceAccess(StorageContext context, Supplier<T> action) {
        if (!membership.contains(Objects.requireNonNull(context))) {
            throw new IllegalArgumentException("Source context does not belong to this fence");
        }
        return runAccess(new Access(this, context, false), Objects.requireNonNull(action), false).value;
    }

    /**
     * O(1) exclusive owner capability, false while a source has not actually exited. requireLock
     * accepts this exact capability OR a real lock; ordinary/source access still requires real locks.
     * Capability is confined to this callback/thread. Library loops charge each item and use one
     * domain short lock with immediate finally unlock; callers never retain the scope across yield.
     */
    public boolean tryAccessWithinBudget(Runnable action) {
        requireOwner();
        Objects.requireNonNull(action);
        return runAccess(new Access(this, null, true), () -> {
            action.run();
            return null;
        }, true).entered;
    }

    /**
     * Original Source advance is active until actual finally exit, even between its short physical
     * scopes. This gate count grants no access capability and is never retained across an advance.
     */
    void beginSourceWork(StorageContext context) {
        requireOutsideAccess();
        requireOutsideSourceWork();
        StorageContext.requireNoLocks();
        if (!membership.contains(context)) {
            throw new IllegalArgumentException("Source domain was not enrolled");
        }
        var work = new SourceWork(this, context);
        boolean interrupted = false;
        try {
            synchronized (this) {
                while (true) {
                    if (phase() != ACTIVE) {
                        throw new IllegalStateException("Storage fence is not active");
                    }
                    if (!ownerAccess && context.sourceWorkOwner == null && context.sourceScopes == 0) {
                        int next = Math.incrementExact(sourceAccesses);
                        context.sourceWorkOwner = Thread.currentThread();
                        sourceAccesses = next;
                        CURRENT_SOURCE_WORK.set(work);
                        return;
                    }
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void endSourceWork(StorageContext context) {
        requireOutsideAccess();
        StorageContext.requireNoLocks();
        synchronized (this) {
            var work = CURRENT_SOURCE_WORK.get();
            if (work == null || work.fence != this || work.context != context || sourceAccesses == 0
                || context.sourceWorkOwner != Thread.currentThread() || context.sourceScopes != 0) {
                throw new IllegalStateException("Source lost its exact actual work gate");
            }
            CURRENT_SOURCE_WORK.remove();
            context.sourceWorkOwner = null;
            sourceAccesses--;
            notifyAll();
        }
    }

    private <T> AccessResult<T> runAccess(Access access, Supplier<T> action, boolean nonblocking) {
        requireOutsideAccess();
        StorageContext.requireNoLocks();
        var sourceWork = CURRENT_SOURCE_WORK.get();
        if (sourceWork != null && (sourceWork.fence != this || access.context != sourceWork.context)) {
            throw new IllegalStateException("Source work cannot enter another domain or owner scope");
        }
        boolean interrupted = false;
        try {
            synchronized (this) {
                while (true) {
                    if (phase() != ACTIVE) {
                        throw new IllegalStateException("Storage fence is not active");
                    }
                    var sourceOwner = access.context == null ? null : access.context.sourceWorkOwner;
                    if (!ownerAccess && (access.context == null ? sourceAccesses == 0
                        : sourceOwner == null || sourceOwner == Thread.currentThread())) {
                        break;
                    }
                    if (nonblocking) {
                        return new AccessResult<>(false, null);
                    }
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (access.context == null) {
                    ownerAccess = true;
                } else {
                    int next = Math.incrementExact(sourceAccesses);
                    int contextNext = Math.incrementExact(access.context.sourceScopes);
                    sourceAccesses = next;
                    access.context.sourceScopes = contextNext;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        CURRENT_ACCESS.set(access);
        Throwable primary = null;
        int locked = 0;
        try {
            if (!access.budgetOwner) {
                if (access.context == null) {
                    for (var context : contexts) {
                        context.lock();
                        locked++;
                    }
                } else {
                    access.context.lock();
                    locked = 1;
                }
            }
            return new AccessResult<>(true, action.get());
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            boolean leaked = false;
            if (access.budgetOwner) {
                // Fault containment only: broken callbacks cannot strand real locks across ticks.
                if (!access.explicitLocks.isEmpty()) {
                    for (var context : access.explicitLocks) {
                        context.finishExplicitAccess();
                        leaked = true;
                    }
                }
            } else if (access.context == null) {
                for (int index = locked - 1; index >= 0; index--) {
                    leaked |= contexts.get(index).finishAccess();
                }
            } else if (locked != 0) {
                leaked = access.context.finishAccess();
            }
            CURRENT_ACCESS.remove();
            synchronized (this) {
                if (access.context == null) {
                    ownerAccess = false;
                } else {
                    access.context.sourceScopes--;
                    sourceAccesses--;
                }
                notifyAll();
            }
            if (leaked) {
                var failure = new IllegalStateException("Storage fence scope leaked a nested physical lock");
                if (primary == null) {
                    throw failure;
                }
                primary.addSuppressed(failure);
            }
        }
    }

    /**
     * Reuses the exact release cursor; ownership publication and notification completion differ.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public Release beginRelease() {
        requireOwnerBoundary();
        synchronized (this) {
            if (release != null) {
                return release;
            }
            if (ownerAccess || sourceAccesses != 0 || sequentialReservations != 0) {
                throw new IllegalStateException("Storage fence retains active work or exposures");
            }
            if (released()) {
                throw new IllegalStateException("Storage fence already released");
            }
            PHASE.setRelease(this, DRAINING);
            release = new Release();
            return release;
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    // The original cursor performs the synchronous drain in the loop condition.
    public void release() {
        requireOwnerBoundary();
        if (released()) {
            throw new IllegalStateException("Storage fence has already been released");
        }
        var ending = beginRelease();
        while (!ending.advance(() -> true)) {
        }
    }

    private int phase() {
        return (int) PHASE.getAcquire(this);
    }

    boolean released() {
        return phase() == RELEASED;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Storage fence owner requires its creating thread");
        }
    }

    private void requireOwnerBoundary() {
        requireOwner();
        requireOutsideAccess();
        requireOutsideSourceWork();
        StorageContext.requireNoLocks();
    }

    synchronized void retainSequentialReservation() {
        if (phase() != ACTIVE || !hasAccess() || !ownerAccess && sourceAccesses == 0) {
            throw new IllegalStateException("Sequential reservation requires an active fence scope");
        }
        sequentialReservations = Math.incrementExact(sequentialReservations);
    }

    synchronized void releaseSequentialReservation() {
        if (phase() != ACTIVE || !hasAccess() || sequentialReservations == 0) {
            throw new IllegalStateException("Sequential reservation lost its fence retention");
        }
        sequentialReservations--;
    }

    void awaitRelease() {
        requireOutsideAccess();
        StorageContext.requireNoLocks();
        if (Thread.currentThread() == owner) {
            throw new IllegalStateException("Fence owner requires its explicit scope");
        }
        boolean interrupted = false;
        synchronized (this) {
            while (!released()) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void rejectOwnerWithoutAccess(StorageContext context) {
        if (Thread.currentThread() == owner && !permits(context, this)) {
            throw new IllegalStateException("Storage fence owner must enter its explicit access scope");
        }
    }

    public static final class Acquire {
        private final StorageFence fence = new StorageFence();
        private final Iterator<StorageContext> input;
        private ObjectArrayList<StorageContext> scratch = new ObjectArrayList<>();
        private int width = 1, start, left, right, middle, end;
        private boolean merging, captured, sorted, cancelled, done;

        private Acquire(Iterator<StorageContext> input) {
            this.input = input;
        }

        /**
         * True means terminal; null result is failure with the exact prefix fully released.
         */
        public boolean advance(BooleanSupplier takeBudget) {
            fence.requireOwnerBoundary();
            Objects.requireNonNull(takeBudget);
            if (done) {
                return true;
            }
            if (cancelled) {
                if (!fence.beginRelease().advance(takeBudget)) {
                    return false;
                }
                done = true;
                return true;
            }
            while (!captured) {
                if (!input.hasNext()) {
                    captured = true;
                    break;
                }
                if (!takeBudget.getAsBoolean()) {
                    return false;
                }
                var context = Objects.requireNonNull(input.next());
                if (fence.membership.add(context)) {
                    fence.contexts.add(context);
                }
            }
            // Each merge compare/output is charged; no bulk sorting or copying at publication.
            while (!sorted) {
                int size = fence.contexts.size();
                if (width >= size) {
                    sorted = true;
                    PHASE.setRelease(fence, CLAIMING);
                    break;
                }
                if (start >= size) {
                    fence.contexts = scratch;
                    scratch = new ObjectArrayList<>();
                    start = 0;
                    width = width > size / 2 ? size : width * 2;
                    continue;
                }
                if (!merging) {
                    left = start;
                    middle = (int) Math.min(size, (long) start + width);
                    right = middle;
                    end = (int) Math.min(size, (long) middle + width);
                    merging = true;
                }
                if (!takeBudget.getAsBoolean()) {
                    return false;
                }
                if (left < middle && (right == end || fence.contexts.get(left).order() < fence.contexts.get(right).order())) {
                    scratch.add(fence.contexts.get(left++));
                } else {
                    scratch.add(fence.contexts.get(right++));
                }
                if (left == middle && right == end) {
                    start = end;
                    merging = false;
                }
            }
            while (fence.claimed < fence.contexts.size()) {
                if (!takeBudget.getAsBoolean()) {
                    return false;
                }
                var context = fence.contexts.get(fence.claimed);
                if (!context.tryPhysicalLock()) {
                    cancelled = true;
                    return advance(takeBudget);
                }
                boolean conflict;
                try {
                    conflict = context.fence() != null;
                    if (!conflict) {
                        context.setFence(fence);
                        fence.claimed++;
                    }
                } finally {
                    context.unlockPhysical();
                }
                if (conflict) {
                    cancelled = true;
                    return advance(takeBudget);
                }
            }
            PHASE.setRelease(fence, ACTIVE);
            done = true;
            return true;
        }

        public void cancel() {
            fence.requireOwnerBoundary();
            if (done) {
                throw new IllegalStateException("Acquisition already finished");
            }
            cancelled = true;
        }

        public @Nullable StorageFence result() {
            fence.requireOwner();
            if (!done) {
                throw new IllegalStateException("Acquisition is pending");
            }
            return cancelled ? null : fence;
        }
    }

    private static final class Access {
        private final StorageFence fence;
        private final @Nullable StorageContext context;
        private final boolean budgetOwner;
        private final ReferenceOpenHashSet<StorageContext> explicitLocks = new ReferenceOpenHashSet<>();

        private Access(StorageFence fence, @Nullable StorageContext context, boolean budgetOwner) {
            this.fence = fence;
            this.context = context;
            this.budgetOwner = budgetOwner;
        }
    }

    private record AccessResult<T>(boolean entered, @Nullable T value) {
    }

    private record SourceWork(StorageFence fence, StorageContext context) {
    }

    public final class Release {
        private final ObjectArrayList<StorageContext> notifications = new ObjectArrayList<>();
        private int capture, cleanup, notification;
        private @Nullable Throwable failure;
        private boolean done;

        private Release() {
        }

        public boolean advance(BooleanSupplier takeBudget) {
            requireOwnerBoundary();
            Objects.requireNonNull(takeBudget);
            if (done) {
                return true;
            }
            while (capture < claimed) {
                if (!takeBudget.getAsBoolean()) {
                    return false;
                }
                var context = contexts.get(capture);
                if (!context.tryPhysicalLock()) {
                    return false;
                }
                try {
                    if (context.fence() != StorageFence.this) {
                        throw new IllegalStateException("Storage fence lost its claim");
                    }
                    if (context.takeFenceChanges()) {
                        notifications.add(context);
                    }
                    capture++;
                } finally {
                    context.unlockPhysical();
                }
            }
            if (!released()) {
                synchronized (StorageFence.this) {
                    PHASE.setRelease(StorageFence.this, RELEASED);
                    StorageFence.this.notifyAll();
                }
            }
            while (cleanup < claimed) {
                if (!takeBudget.getAsBoolean()) {
                    return false;
                }
                var context = contexts.get(cleanup);
                if (!context.tryPhysicalLock()) {
                    return false;
                }
                try {
                    context.clearReleasedFence(StorageFence.this);
                    cleanup++;
                } finally {
                    context.unlockPhysical();
                }
            }
            while (notification < notifications.size()) {
                if (!takeBudget.getAsBoolean()) {
                    return false;
                }
                var context = notifications.get(notification++); // Record attempt before callback.
                try {
                    context.publishFenceChanges();
                } catch (RuntimeException | Error fault) {
                    if (fault instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                    if (failure == null) {
                        failure = fault;
                    } else if (failure != fault) {
                        failure.addSuppressed(fault);
                    }
                }
            }
            done = true;
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return true;
        }

        public boolean done() {
            requireOwner();
            return done;
        }
    }
}
