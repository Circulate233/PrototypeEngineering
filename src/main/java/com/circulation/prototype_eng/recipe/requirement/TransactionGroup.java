package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Ordered business effects sharing a terminal decision, retaining each exact cleanup receipt.
 */
final class TransactionGroup implements ReversibleTransaction {
    private final List<? extends ReversibleTransaction> transactions;
    private final boolean[] terminal;
    private final boolean[] uncertain;
    @Nullable
    private Resolution decision;
    private boolean preparationAttempted;
    private boolean preparationAccepted;
    private boolean commitAttempted;
    private boolean validationAttempted;
    private boolean executing;
    private int confirmed;
    private int prepareCursor;
    private int commitCursor;
    private int validateCursor;
    private int settleCursor = -1;
    private int queryCursor = -1;
    private boolean childSelected;
    private boolean querySelected;
    private boolean queryRetryable;
    private boolean budgetPrepare;
    private boolean budgetCommit;
    private boolean budgetValidate;
    @Nullable
    private Throwable settleFailure;
    @Nullable
    private Throwable queryFailure;

    /**
     * Borrows the fixed participant list until all effects reach their terminal decision.
     */
    TransactionGroup(List<? extends ReversibleTransaction> transactions) {
        this.transactions = transactions;
        terminal = new boolean[transactions.size()];
        uncertain = new boolean[transactions.size()];
    }

    /**
     * Unqueryable extension callbacks remain UNKNOWN if their terminal action throws.
     */
    static ReversibleTransaction callbacks(Runnable commit, Runnable complete, Runnable rollback) {
        return new ReversibleTransaction() {
            private Resolution resolution = Resolution.REVERSIBLE;

            @Override
            public void commit() {
                commit.run();
            }

            @Override
            public void complete() {
                finish(Resolution.COMPLETED, complete);
            }

            @Override
            public void rollback() {
                finish(Resolution.ROLLED_BACK, rollback);
            }

            @Override
            public Resolution resolution() {
                return resolution;
            }

            private void finish(Resolution expected, Runnable action) {
                resolution = Resolution.UNKNOWN;
                action.run();
                resolution = expected;
            }
        };
    }

    private static Throwable accumulate(@Nullable Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    @Override
    public boolean prepareCommit() {
        requireIdle();
        if (preparationAttempted || commitAttempted || decision != null) {
            throw new IllegalStateException("Cannot replay composite preparation");
        }
        preparationAttempted = true;
        executing = true;
        try {
            for (int index2 = 0; index2 < transactions.size(); index2++) {
                var transaction = transactions.get(index2);
                if (!transaction.prepareCommit()) {
                    return false;
                }
            }
            preparationAccepted = true;
            return true;
        } finally {
            executing = false;
        }
    }

    @Override
    public void commit() {
        requireIdle();
        if (commitAttempted || decision != null || preparationAttempted && !preparationAccepted) {
            throw new IllegalStateException("Cannot replay or commit rejected composite work");
        }
        commitAttempted = true;
        executing = true;
        try {
            for (int index1 = 0; index1 < transactions.size(); index1++) {
                var transaction = transactions.get(index1);
                transaction.commit();
            }
        } finally {
            executing = false;
        }
    }

    @Override
    public void complete() {
        settle(Resolution.COMPLETED);
    }

    @Override
    public void rollback() {
        settle(Resolution.ROLLED_BACK);
    }

    @Override
    public boolean validateCommit() {
        requireIdle();
        if (!commitAttempted || validationAttempted || decision != null) {
            throw new IllegalStateException("Cannot validate uncommitted or resolving composite work");
        }
        validationAttempted = true;
        executing = true;
        try {
            for (int index3 = 0; index3 < transactions.size(); index3++) {
                var transaction = transactions.get(index3);
                if (!transaction.validateCommit()) {
                    return false;
                }
            }
            return true;
        } finally {
            executing = false;
        }
    }

    private void settle(Resolution expected) {
        requireIdle();
        if (decision != null && decision != expected) {
            throw new IllegalStateException("Cannot change composite terminal decision");
        }
        decision = expected;
        executing = true;
        Throwable failure = null;
        try {
            for (int offset = 0; offset < transactions.size(); offset++) {
                int index = expected == Resolution.COMPLETED ? offset : transactions.size() - 1 - offset;
                if (terminal[index]) {
                    continue;
                }
                var transaction = transactions.get(index);
                try {
                    if (uncertain[index] && !confirmOrRetry(index, transaction)) {
                        continue;
                    }
                    if (terminal[index]) {
                        continue;
                    }
                    if (expected == Resolution.COMPLETED) {
                        transaction.complete();
                    } else {
                        transaction.rollback();
                    }
                    confirm(index);
                } catch (RuntimeException | Error cleanup) {
                    uncertain[index] = true;
                    failure = accumulate(failure, cleanup);
                    try {
                        confirmOrRetry(index, transaction);
                    } catch (RuntimeException | Error query) {
                        failure = accumulate(failure, query);
                    }
                }
            }
            // A coordinator retries only after REVERSIBLE proof. A direct UNKNOWN retry violates
            // that boundary and must not return normally, which would falsely certify completion.
            if (failure == null && !allTerminal()) {
                failure = new IllegalStateException("Composite cleanup requires exact recovery proof");
            }
        } finally {
            executing = false;
        }
        rethrow(failure);
    }

    @Override
    public Resolution resolution() {
        requireIdle();
        executing = true;
        try {
            if (decision == null) {
                return Resolution.REVERSIBLE;
            }
            boolean retryable = true;
            Throwable failure = null;
            for (int index = 0; index < transactions.size(); index++) {
                if (terminal[index] || !uncertain[index]) {
                    continue;
                }
                try {
                    if (!confirmOrRetry(index, transactions.get(index)) && !terminal[index]) {
                        retryable = false;
                    }
                } catch (RuntimeException | Error query) {
                    failure = accumulate(failure, query);
                }
            }
            rethrow(failure);
            if (allTerminal()) {
                return Objects.requireNonNull(decision);
            }
            return retryable ? Resolution.REVERSIBLE : Resolution.UNKNOWN;
        } finally {
            executing = false;
        }
    }

    private boolean confirmOrRetry(int index, ReversibleTransaction transaction) {
        var actual = Objects.requireNonNull(transaction.resolution());
        if (actual == decision) {
            confirm(index);
            return false;
        }
        if (actual == Resolution.UNKNOWN) {
            return false;
        }
        if (actual == Resolution.REVERSIBLE) {
            return true;
        }
        throw new IllegalStateException("Composite child contradicts its terminal decision: " + actual);
    }

    private boolean allTerminal() {
        return confirmed == terminal.length;
    }

    private void confirm(int index) {
        if (!terminal[index]) {
            terminal[index] = true;
            confirmed++;
        }
        uncertain[index] = false;
    }

    @Override
    public @Nullable Boolean prepareCommitWithinBudget(RecipeWorkBudget budget) {
        requireIdle();
        if (commitAttempted || decision != null || preparationAttempted && !budgetPrepare)
            throw new IllegalStateException("Cannot replay composite preparation");
        preparationAttempted = true;
        budgetPrepare = true;
        executing = true;
        try {
            while (prepareCursor < transactions.size()) {
                if (!selectChild(budget)) return null;
                var accepted = transactions.get(prepareCursor).prepareCommitWithinBudget(budget);
                if (accepted == null) return null;
                childSelected = false;
                if (!accepted) {
                    budgetPrepare = false;
                    return false;
                }
                prepareCursor++;
            }
            preparationAccepted = true;
            budgetPrepare = false;
            return true;
        } catch (RuntimeException | Error failure) {
            budgetPrepare = false;
            throw failure;
        } finally {
            executing = false;
        }
    }

    @Override
    public boolean commitWithinBudget(RecipeWorkBudget budget) {
        requireIdle();
        if (decision != null || commitAttempted && !budgetCommit || preparationAttempted && !preparationAccepted)
            throw new IllegalStateException("Cannot replay composite commit");
        commitAttempted = true;
        budgetCommit = true;
        executing = true;
        try {
            while (commitCursor < transactions.size()) {
                if (!selectChild(budget)) return false;
                if (!transactions.get(commitCursor).commitWithinBudget(budget)) return false;
                childSelected = false;
                commitCursor++;
            }
            budgetCommit = false;
            return true;
        } catch (RuntimeException | Error failure) {
            budgetCommit = false;
            throw failure;
        } finally {
            executing = false;
        }
    }

    @Override
    public @Nullable Boolean validateCommitWithinBudget(RecipeWorkBudget budget) {
        requireIdle();
        if (decision != null || !commitAttempted || budgetCommit || validationAttempted && !budgetValidate)
            throw new IllegalStateException("Cannot replay composite validation");
        validationAttempted = true;
        budgetValidate = true;
        executing = true;
        try {
            while (validateCursor < transactions.size()) {
                if (!selectChild(budget)) return null;
                var accepted = transactions.get(validateCursor).validateCommitWithinBudget(budget);
                if (accepted == null) return null;
                childSelected = false;
                if (!accepted) {
                    budgetValidate = false;
                    return false;
                }
                validateCursor++;
            }
            budgetValidate = false;
            return true;
        } catch (RuntimeException | Error failure) {
            budgetValidate = false;
            throw failure;
        } finally {
            executing = false;
        }
    }

    @Override
    public boolean completeWithinBudget(RecipeWorkBudget budget) {
        return settleWithinBudget(Resolution.COMPLETED, budget);
    }

    @Override
    public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
        return settleWithinBudget(Resolution.ROLLED_BACK, budget);
    }

    private boolean settleWithinBudget(Resolution expected, RecipeWorkBudget budget) {
        requireIdle();
        if (decision != null && decision != expected)
            throw new IllegalStateException("Cannot change composite terminal decision");
        if (allTerminal()) return true;
        if (decision == null) {
            decision = expected;
            childSelected = false;
        }
        if (settleCursor == -1) settleCursor = expected == Resolution.COMPLETED ? 0 : transactions.size() - 1;
        executing = true;
        try {
            while (settleCursor >= 0 && settleCursor < transactions.size()) {
                if (!selectChild(budget)) return false;
                int index = settleCursor;
                var transaction = transactions.get(index);
                if (!terminal[index]) {
                    if (uncertain[index]) {
                        try {
                            var actual = transaction.resolutionWithinBudget(budget);
                            if (actual == null) return false;
                            if (actual == expected) confirm(index);
                            else if (actual == Resolution.REVERSIBLE) uncertain[index] = false;
                            else if (actual != Resolution.UNKNOWN)
                                throw new IllegalStateException("Composite child contradicts its terminal decision: " + actual);
                        } catch (RuntimeException | Error failure) {
                            settleFailure = accumulate(settleFailure, failure);
                            if (failure instanceof VirtualMachineError fatal) throw fatal;
                        }
                    }
                    if (!terminal[index] && !uncertain[index]) {
                        try {
                            boolean done = expected == Resolution.COMPLETED ? transaction.completeWithinBudget(budget) : transaction.rollbackWithinBudget(budget);
                            if (!done) return false;
                            confirm(index);
                        } catch (RuntimeException | Error failure) {
                            uncertain[index] = true;
                            settleFailure = accumulate(settleFailure, failure);
                            if (failure instanceof VirtualMachineError fatal) throw fatal;
                        }
                    }
                }
                childSelected = false;
                settleCursor += expected == Resolution.COMPLETED ? 1 : -1;
            }
            settleCursor = -1;
            var failure = settleFailure;
            settleFailure = null;
            rethrow(failure);
            if (!allTerminal()) throw new IllegalStateException("Composite cleanup requires exact recovery proof");
            return true;
        } finally {
            executing = false;
        }
    }

    @Override
    public @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
        requireIdle();
        if (decision == null) return Resolution.REVERSIBLE;
        if (allTerminal()) return decision;
        if (queryCursor == -1) {
            queryCursor = 0;
            queryRetryable = true;
        }
        executing = true;
        try {
            while (queryCursor < transactions.size()) {
                if (!querySelected) {
                    if (!budget.take()) return null;
                    querySelected = true;
                }
                int index = queryCursor;
                if (!terminal[index] && uncertain[index]) {
                    try {
                        var actual = transactions.get(index).resolutionWithinBudget(budget);
                        if (actual == null) return null;
                        if (actual == decision) confirm(index);
                        else if (actual == Resolution.UNKNOWN) queryRetryable = false;
                        else if (actual != Resolution.REVERSIBLE)
                            throw new IllegalStateException("Composite child contradicts its terminal decision: " + actual);
                    } catch (RuntimeException | Error failure) {
                        queryFailure = accumulate(queryFailure, failure);
                        if (failure instanceof VirtualMachineError fatal) throw fatal;
                    }
                }
                querySelected = false;
                queryCursor++;
            }
            queryCursor = -1;
            var failure = queryFailure;
            queryFailure = null;
            rethrow(failure);
            return allTerminal() ? decision : queryRetryable ? Resolution.REVERSIBLE : Resolution.UNKNOWN;
        } finally {
            executing = false;
        }
    }

    private boolean selectChild(RecipeWorkBudget budget) {
        if (childSelected) return true;
        if (!budget.take()) return false;
        childSelected = true;
        return true;
    }

    private void requireIdle() {
        if (executing) {
            throw new IllegalStateException("Composite transaction callback is executing");
        }
    }
}
