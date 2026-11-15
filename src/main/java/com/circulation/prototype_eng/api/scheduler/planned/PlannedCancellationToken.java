package com.circulation.prototype_eng.api.scheduler.planned;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Cooperative cancellation with one retained reason and independently removable registrations.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class PlannedCancellationToken {
    private final Object cancellationLock = new Object();
    private final ObjectLinkedOpenHashSet<Registration> registrations = new ObjectLinkedOpenHashSet<>();
    @Nullable
    private volatile Reason cancellationReason;

    /**
     * Claims all current registrations once; every callback runs outside the registration lock.
     */
    public boolean cancel(Reason reason) {
        ObjectArrayList<Registration> callbacks;
        synchronized (cancellationLock) {
            if (cancellationReason != null) {
                return false;
            }
            callbacks = new ObjectArrayList<>(registrations);
            cancellationReason = reason;
            registrations.clear();
        }
        Throwable firstFailure = null;
        for (int index = 0; index < callbacks.size(); index++) {
            var registration = callbacks.get(index);
            try {
                registration.handler.accept(reason);
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure instanceof RuntimeException failure) {
            throw failure;
        }
        if (firstFailure instanceof Error failure) {
            throw failure;
        }
        if (firstFailure != null) {
            throw new IllegalStateException("Planned cancellation callback failed", firstFailure);
        }
        return true;
    }

    public boolean cancelled() {
        return cancellationReason != null;
    }

    public @Nullable Reason reason() {
        return cancellationReason;
    }

    /**
     * Registers without a check/register race; an already cancelled token invokes it synchronously.
     */
    public Registration onCancel(Consumer<Reason> handler) {
        var registration = new Registration(this, handler);
        Reason currentReason;
        synchronized (cancellationLock) {
            currentReason = cancellationReason;
            if (currentReason == null) {
                registrations.add(registration);
            }
        }
        if (currentReason != null) {
            handler.accept(currentReason);
        }
        return registration;
    }

    public enum Reason {
        EXPLICIT_CANCEL,
        MACHINE_UNLOAD,
        WORLD_UNLOAD,
        SERVER_STOP,
        SCHEDULER_RECONFIGURED,
        OWNER_CHANGED,
        EPOCH_CHANGED,
        RESERVATION_CONFLICT,
        RESERVATION_EXPIRED,
        WORKER_FAILURE,
        WORKER_TIMEOUT,
        DISCOVERY_FAILURE,
        DECISION_ROLLBACK,
        RESOLVE_FAILURE,
        COMMIT_FAILURE,
        INTERRUPTED
    }

    public static final class Registration implements AutoCloseable {
        private final PlannedCancellationToken token;
        private final Consumer<Reason> handler;

        private Registration(PlannedCancellationToken token, Consumer<Reason> handler) {
            this.token = token;
            this.handler = handler;
        }

        /**
         * Removes this registration if cancellation has not already claimed its callback.
         */
        @Override
        public void close() {
            synchronized (token.cancellationLock) {
                token.registrations.remove(this);
            }
        }
    }
}
