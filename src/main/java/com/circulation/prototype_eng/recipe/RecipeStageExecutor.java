package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;

/**
 * One compensation/completion boundary across every requirement type and resource/FE reservation.
 */
public final class RecipeStageExecutor {
    private RecipeStageExecutor() {}

    public static ProcessResult execute(RecipeProcess process, RecipeStage stage) {
        return execute(process, stage, RequirementTransaction.SUCCESS);
    }

    /**
     * Acquires the real START requirements of an unattached NEW candidate, then compensates every
     * acquisition. No business or storage commit, completion, TICK or END is performed.
     * External START callbacks still run and must satisfy the normal reversible acquisition contract.
     */
    public static ProcessResult probeStart(RecipeProcess process) {
        return probeStage(process, RecipeStage.START);
    }

    /**
     * Probes one isolated stage of an unattached NEW candidate. Earlier stages and component callbacks
     * are not executed; the caller must establish any state that this stage requires. This is not a
     * projection of a complete lifecycle. TICK uses the candidate's first frozen tick context for both
     * acquisition and cleanup. Every acquired transaction is rolled back without commit/complete.
     */
    public static ProcessResult probeStage(RecipeProcess process, RecipeStage stage) {
        requireProbeBoundary(process);
        Objects.requireNonNull(stage);
        try {
            ProcessResult result;
            if (stage == RecipeStage.TICK) {
                var state = process.state();
                double speed = process.attributes().value(StandardMachineAttributes.PROCESS_SPEED, 1);
                var tick = new RecipeProcess.Tick(state.progress(), state.progress() + Math.max(0, speed),
                    state.successfulTicks(), Math.incrementExact(state.successfulTicks()));
                result = process.withTickContext(tick, () -> probeAcquisition(process, stage));
            } else {
                result = probeAcquisition(process, stage);
            }
            requireProbeBoundary(process);
            return result;
        } catch (RuntimeException | Error failure) {
            process.invalidatePersistenceBoundary();
            throw failure;
        }
    }

    private static ProcessResult probeAcquisition(RecipeProcess process, RecipeStage stage) {
        var transactions = new ObjectArrayList<RequirementTransaction>();
        var reservations = new ObjectArrayList<ResourceReservation>();
        ProcessResult result;
        try {
            result = acquire(process, stage, transactions, reservations);
        } catch (RuntimeException | Error failure) {
            rollbackAfterFailure(transactions, reservations, failure);
            throw failure;
        }
        ResourceTransactions.rollback(reservations, () -> rollback(transactions, null));
        return result;
    }

    /**
     * The final participant applies process lifecycle/progress in the same reversible boundary.
     * It is supplied before acquisition and is rolled back even when acquisition itself fails.
     */
    public static ProcessResult execute(RecipeProcess process, RecipeStage stage, RequirementTransaction transition) {
        Objects.requireNonNull(transition);
        if (transition.result() != ProcessResult.SUCCESS || !transition.reservations().isEmpty()) {
            throw new IllegalArgumentException("A process transition must be successful and have no storage reservations");
        }
        var transactions = new ObjectArrayList<RequirementTransaction>();
        var reservations = new ObjectArrayList<ResourceReservation>();
        ProcessResult result;
        try {
            result = acquire(process, stage, transactions, reservations);
        } catch (RuntimeException | Error failure) {
            transactions.add(transition);
            rollbackAfterFailure(transactions, reservations, failure);
            throw failure;
        }
        transactions.add(transition);
        if (result == ProcessResult.SUCCESS) {
            boolean accepted = ResourceTransactions.commit(reservations, () -> {
                for (var transaction : transactions) {
                    transaction.commit();
                }
            }, () -> complete(transactions), () -> rollback(transactions, null));
            if (!accepted) {
                return RecipeText.StorageChanged.blocked();
            }
        } else {
            ResourceTransactions.rollback(reservations, () -> rollback(transactions, null));
        }
        return result;
    }

    private static void requireProbeBoundary(RecipeProcess process) {
        Objects.requireNonNull(process).requirePersistenceBoundary();
        if (process.host() != null || process.state().lifecycle() != RecipeProcess.Lifecycle.NEW) {
            throw new IllegalArgumentException("Stage probe requires an unattached NEW process");
        }
    }

    private static ProcessResult acquire(RecipeProcess process, RecipeStage stage,
                                         List<RequirementTransaction> transactions, List<ResourceReservation> reservations) {
        ProcessResult result = ProcessResult.SUCCESS;
        for (var requirement : process.recipe().orderedRequirements()) {
            var transaction = Objects.requireNonNull(requirement.acquire(process, stage), "requirement transaction");
            transactions.add(transaction);
            reservations.addAll(Objects.requireNonNull(transaction.reservations(), "transaction reservations"));
            result = Objects.requireNonNull(transaction.result(), "transaction result");
            if (result != ProcessResult.SUCCESS) {
                break;
            }
        }
        return result;
    }

    private static void rollbackAfterFailure(List<RequirementTransaction> transactions,
                                             List<ResourceReservation> reservations, Throwable failure) {
        // Exceptional cleanup only. Preserve the original fault and expose every cleanup fault.
        try {
            ResourceTransactions.rollback(reservations, () -> rollback(transactions, failure));
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** The terminal boundary has been crossed; a broken callback must not skip later completions. */
    private static void complete(List<RequirementTransaction> transactions) {
        Throwable failure = null;
        for (var transaction : transactions) {
            try {
                transaction.complete();
            } catch (RuntimeException | Error completion) {
                if (failure == null) {
                    failure = completion;
                } else if (failure != completion) {
                    failure.addSuppressed(completion);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void rollback(List<RequirementTransaction> transactions, Throwable primary) {
        Throwable failure = primary;
        for (int i = transactions.size() - 1; i >= 0; i--) {
            try {
                transactions.get(i).rollback();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) { failure = cleanupFailure; }
                else if (failure != cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
        }
        if (primary == null) {
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
        }
    }
}
