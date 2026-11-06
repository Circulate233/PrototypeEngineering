package com.circulation.prototype_eng.api.storage;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Ordered per-storage locks make staged application/compensation invisible to other threads.
 * Call without already holding a storage lock; participants must honor the reservation contract.
 */
public final class ResourceTransactions {
    private static final Runnable NO_OP = () -> {
    };

    private ResourceTransactions() {
    }

    public static boolean commit(List<? extends ResourceReservation> reservations) {
        return commit(reservations, NO_OP, NO_OP, NO_OP);
    }

    /**
     * Apply all reservations, then reversible business changes, before releasing any compensation
     * rights. Business rollback runs before reverse token rollback, under all participant locks.
     * Completion must not fail; faults during completion or notification never trigger rollback.
     * Callbacks may access only enrolled storage domains and must not wait for another execution lane.
     */
    public static boolean commit(List<? extends ResourceReservation> reservations, Runnable apply,
                                 Runnable complete, Runnable rollback) {
        return settle(reservations, true, apply, complete, rollback);
    }

    public static void rollback(List<? extends ResourceReservation> reservations) {
        rollback(reservations, NO_OP);
    }

    public static void rollback(List<? extends ResourceReservation> reservations, Runnable rollback) {
        settle(reservations, false, NO_OP, NO_OP, rollback);
    }

    private static boolean settle(List<? extends ResourceReservation> reservations, boolean commit,
                                  Runnable apply, Runnable complete, Runnable rollback) {
        Objects.requireNonNull(apply);
        Objects.requireNonNull(complete);
        Objects.requireNonNull(rollback);
        // External participants may call back into the owner of the supplied list.
        var participants = new ObjectArrayList<ResourceReservation>(reservations);
        var tokens = new ReferenceOpenHashSet<ResourceReservation>();
        var domains = new ReferenceOpenHashSet<StorageContext>();
        for (var reservation : participants) {
            Objects.requireNonNull(reservation);
            if (!tokens.add(reservation)) {
                throw new IllegalArgumentException("Repeated reservation token");
            }
            domains.add(Objects.requireNonNull(reservation.context()));
        }
        var ordered = new ObjectArrayList<>(domains);
        ordered.sort(Comparator.comparingLong(StorageContext::order));
        ordered.forEach(StorageContext::lock);
        Throwable primary = null;
        try {
            if (!commit) {
                rollbackAll(participants, rollback, null);
                return false;
            }
            boolean accepted = true;
            try {
                for (var reservation : participants) {
                    if (!reservation.canApply()) {
                        accepted = false;
                        break;
                    }
                }
                if (accepted) {
                    for (var reservation : participants) {
                        reservation.apply();
                    }
                    apply.run();
                }
            } catch (RuntimeException | Error failure) {
                rollbackAll(participants, rollback, failure);
                throw failure;
            }
            if (!accepted) {
                rollbackAll(participants, rollback, null);
                return false;
            }
            // Crossing this boundary relinquishes compensation rights. Never roll back a terminal token.
            complete.run();
            for (var reservation : participants) {
                reservation.complete();
            }
            return true;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            for (int i = ordered.size() - 1; i >= 0; i--) {
                ordered.get(i).unlockDeferred();
            }
            publishAll(ordered, primary);
        }
    }

    private static void publishAll(List<StorageContext> contexts, Throwable primary) {
        Throwable failure = primary;
        for (var context : contexts) {
            try {
                context.publishChanges();
            } catch (RuntimeException | Error notification) {
                failure = accumulate(failure, notification);
            }
        }
        if (primary == null) {
            rethrow(failure);
        }
    }

    private static void rollbackAll(List<? extends ResourceReservation> reservations, Runnable rollback,
                                    Throwable primary) {
        Throwable failure = primary;
        try {
            rollback.run();
        } catch (RuntimeException | Error cleanup) {
            failure = accumulate(failure, cleanup);
        }
        for (int i = reservations.size() - 1; i >= 0; i--) {
            try {
                reservations.get(i).rollback();
            } catch (RuntimeException | Error cleanup) {
                failure = accumulate(failure, cleanup);
            }
        }
        if (primary == null) {
            rethrow(failure);
        }
    }

    private static Throwable accumulate(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
