package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** One compensation boundary around ordered local preparation and a candidate's admission check. */
public final class RecipeScanProbes {
    private RecipeScanProbes() {
    }

    public static ProcessResult check(RecipeProcess process, List<RecipeScanPreparation> preparations,
                                      Function<RecipeProcess, ProcessResult> check) {
        requireBoundary(process);
        Objects.requireNonNull(check, "check");
        var ordered = new ObjectArrayList<>(Objects.requireNonNull(preparations, "preparations"));
        var transactions = new ObjectArrayList<RequirementTransaction>();
        var reservations = new ObjectArrayList<ResourceReservation>();
        ProcessResult result = ProcessResult.SUCCESS;
        try {
            for (var preparation : ordered) {
                if (retired(process)) { result = RecipeText.Cancelled.blocked(); break; }
                var transaction = Objects.requireNonNull(Objects.requireNonNull(preparation, "preparation").prepare(process),
                    "preparation transaction");
                transactions.add(transaction);
                reservations.addAll(Objects.requireNonNull(transaction.reservations(), "preparation reservations"));
                if (!reservations.isEmpty()) {
                    throw new IllegalArgumentException("Scan preparation must reserve no resources or energy");
                }
                result = Objects.requireNonNull(transaction.result(), "preparation result");
                if (result != ProcessResult.SUCCESS) {
                    break;
                }
            }
            if (result == ProcessResult.SUCCESS && retired(process)) { result = RecipeText.Cancelled.blocked(); }
            if (result == ProcessResult.SUCCESS) {
                result = Objects.requireNonNull(check.apply(process), "candidate check result");
            }
        } catch (RuntimeException | Error failure) {
            process.invalidatePersistenceBoundary();
            rollbackAfterFailure(transactions, reservations, failure);
            throw failure;
        }
        try {
            ResourceTransactions.rollback(reservations, () -> rollback(transactions, null));
            requireBoundary(process);
        } catch (RuntimeException | Error failure) {
            process.invalidatePersistenceBoundary();
            throw failure;
        }
        return result;
    }

    private static boolean retired(RecipeProcess process) {
        var lifecycle = process.owner().lifecycle();
        return lifecycle != null && lifecycle.retired();
    }

    private static void requireBoundary(RecipeProcess process) {
        Objects.requireNonNull(process).requirePersistenceBoundary();
        if (process.host() != null || process.state().lifecycle() != RecipeProcess.Lifecycle.NEW) {
            throw new IllegalArgumentException("Scan preparation requires an unattached NEW process");
        }
    }

    private static void rollbackAfterFailure(List<RequirementTransaction> transactions,
                                             List<ResourceReservation> reservations, Throwable primary) {
        try {
            ResourceTransactions.rollback(reservations, () -> rollback(transactions, primary));
        } catch (RuntimeException | Error cleanup) {
            if (primary != cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
    }

    private static void rollback(List<RequirementTransaction> transactions, Throwable primary) {
        Throwable failure = primary;
        for (int i = transactions.size() - 1; i >= 0; i--) {
            try {
                transactions.get(i).rollback();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (primary == null) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
