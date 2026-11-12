package com.circulation.prototype_eng.api.storage;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Ordered per-storage locks make staged application/compensation invisible to other threads.
 * Call without already holding a storage lock; participants must honor the reservation contract.
 */
public final class ResourceTransactions {
    private static final Runnable NO_OP = () -> {
    };
    private static final BooleanSupplier ACCEPT = () -> true;

    private ResourceTransactions() {
    }

    public static boolean commit(List<? extends ResourceReservation> reservations) {
        return commit(reservations, NO_OP, NO_OP, NO_OP);
    }

    /**
     * Apply all reservations, then reversible business changes, before releasing any compensation
     * rights. Business rollback runs before reverse token rollback, under all participant locks.
     * Completion must not fail; if it does, all remaining completions are still attempted and faults
     * are aggregated. Completion/notification faults never trigger rollback.
     * Callbacks may access only enrolled storage domains and must not wait for another execution lane.
     */
    public static boolean commit(List<? extends ResourceReservation> reservations, Runnable apply,
                                 Runnable complete, Runnable rollback) {
        return commit(reservations, ACCEPT, apply, complete, rollback);
    }

    /**
     * Prepares reversible business effects after source application, while holding all domain locks.
     * False rolls back business and sources normally without entering any ordinary business commit.
     * The preparation is attempted once; a failure also takes the existing compensation path.
     */
    public static boolean commit(List<? extends ResourceReservation> reservations, BooleanSupplier prepareCommit,
                                 Runnable apply, Runnable complete, Runnable rollback) {
        return commit(reservations, prepareCommit, ACCEPT, apply, complete, rollback);
    }

    /**
     * A read-only validation after all business commits retains the same full compensation boundary.
     */
    public static boolean commit(List<? extends ResourceReservation> reservations, BooleanSupplier prepareCommit,
                                 BooleanSupplier validateCommit, Runnable apply, Runnable complete, Runnable rollback) {
        return settle(reservations, true, prepareCommit, validateCommit, apply, complete, rollback);
    }

    public static void rollback(List<? extends ResourceReservation> reservations) {
        rollback(reservations, NO_OP);
    }

    public static void rollback(List<? extends ResourceReservation> reservations, Runnable rollback) {
        settle(reservations, false, ACCEPT, ACCEPT, NO_OP, NO_OP, rollback);
    }

    private static boolean settle(List<? extends ResourceReservation> reservations, boolean commit,
                                  BooleanSupplier prepareCommit, BooleanSupplier validateCommit,
                                  Runnable apply, Runnable complete, Runnable rollback) {
        Objects.requireNonNull(prepareCommit);
        Objects.requireNonNull(validateCommit);
        Objects.requireNonNull(apply);
        Objects.requireNonNull(complete);
        Objects.requireNonNull(rollback);
        // External participants may call back into the owner of the supplied list.
        var participants = new ObjectArrayList<ResourceReservation>(reservations);
        var tokens = new ReferenceOpenHashSet<ResourceReservation>(participants.size());
        var domains = new ReferenceOpenHashSet<StorageContext>(participants.size());
        for (var reservation : participants) {
            Objects.requireNonNull(reservation);
            if (!tokens.add(reservation)) {
                throw new IllegalArgumentException("Repeated reservation token");
            }
            domains.add(Objects.requireNonNull(reservation.context()));
        }
        var ordered = new ObjectArrayList<>(domains);
        ordered.sort(Comparator.comparingLong(StorageContext::order));
        StorageContext.lockAll(ordered);
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
                    accepted = prepareCommit.getAsBoolean();
                    if (accepted) {
                        apply.run();
                        accepted = validateCommit.getAsBoolean();
                    }
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
            completeAll(participants, complete);
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

    private static void completeAll(List<? extends ResourceReservation> reservations, Runnable complete) {
        Throwable failure = null;
        try {
            complete.run();
        } catch (RuntimeException | Error completion) {
            failure = completion;
        }
        for (var reservation : reservations) {
            try {
                reservation.complete();
            } catch (RuntimeException | Error completion) {
                failure = accumulate(failure, completion);
            }
        }
        rethrow(failure);
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
