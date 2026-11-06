package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;

/** PM FactoryRecipeProcessorSystem.executeStage, without swallowed acquisition/rollback failures. */
public final class RecipeStageExecutor {
    private RecipeStageExecutor() {}

    public static ProcessResult execute(RecipeProcess process, RecipeStage stage) {
        var transactions = new ObjectArrayList<RequirementTransaction>();
        ProcessResult result = ProcessResult.SUCCESS;
        try {
            for (var requirement : process.recipe().orderedRequirements()) {
                var transaction = Objects.requireNonNull(requirement.acquire(process, stage), "requirement transaction");
                transactions.add(transaction);
                result = Objects.requireNonNull(transaction.result(), "transaction result");
                if (result != ProcessResult.SUCCESS) { break; }
            }
        } catch (RuntimeException | Error failure) {
            // Exceptional cleanup only. Preserve the original fault and expose every cleanup fault.
            rollback(transactions, failure);
            throw failure;
        }
        if (result == ProcessResult.SUCCESS) {
            // A throwing commit is fatal. Do not pretend already-finalized transactions are reversible.
            for (var transaction : transactions) { transaction.commit(); }
        } else {
            rollback(transactions, null);
        }
        return result;
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
