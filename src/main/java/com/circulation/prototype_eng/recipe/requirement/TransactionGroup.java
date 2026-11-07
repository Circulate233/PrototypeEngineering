package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Ordered business effects sharing a terminal decision, retaining each exact cleanup receipt. */
final class TransactionGroup implements ReversibleTransaction {
    private final ObjectList<ReversibleTransaction> transactions;
    private final boolean[] terminal;
    private final boolean[] uncertain;
    @Nullable private Resolution decision;
    private boolean commitAttempted;
    private boolean executing;

    TransactionGroup(List<? extends ReversibleTransaction> transactions) {
        this.transactions = new ObjectArrayList<>(transactions);
        this.transactions.forEach(Objects::requireNonNull);
        terminal = new boolean[transactions.size()];
        uncertain = new boolean[transactions.size()];
    }

    /** Unqueryable extension callbacks remain UNKNOWN if their terminal action throws. */
    static ReversibleTransaction callbacks(Runnable commit, Runnable complete, Runnable rollback) {
        return new ReversibleTransaction() {
            private Resolution resolution = Resolution.REVERSIBLE;

            @Override public void commit() { commit.run(); }
            @Override public void complete() { finish(Resolution.COMPLETED, complete); }
            @Override public void rollback() { finish(Resolution.ROLLED_BACK, rollback); }
            @Override public Resolution resolution() { return resolution; }

            private void finish(Resolution expected, Runnable action) {
                resolution = Resolution.UNKNOWN;
                action.run();
                resolution = expected;
            }
        };
    }

    @Override public void commit() {
        requireIdle();
        if (commitAttempted || decision != null) { throw new IllegalStateException("Cannot replay composite commit"); }
        commitAttempted = true;
        executing = true;
        try { for (var transaction : transactions) { transaction.commit(); } }
        finally { executing = false; }
    }

    @Override public void complete() { settle(Resolution.COMPLETED); }
    @Override public void rollback() { settle(Resolution.ROLLED_BACK); }

    private void settle(Resolution expected) {
        requireIdle();
        if (decision != null && decision != expected) { throw new IllegalStateException("Cannot change composite terminal decision"); }
        decision = expected;
        executing = true;
        Throwable failure = null;
        try {
            for (int offset = 0; offset < transactions.size(); offset++) {
                int index = expected == Resolution.COMPLETED ? offset : transactions.size() - 1 - offset;
                if (terminal[index]) { continue; }
                var transaction = transactions.get(index);
                try {
                    if (uncertain[index] && !confirmOrRetry(index, transaction)) { continue; }
                    if (terminal[index]) { continue; }
                    if (expected == Resolution.COMPLETED) { transaction.complete(); }
                    else { transaction.rollback(); }
                    terminal[index] = true;
                    uncertain[index] = false;
                } catch (RuntimeException | Error cleanup) {
                    uncertain[index] = true;
                    failure = accumulate(failure, cleanup);
                    try { confirmOrRetry(index, transaction); }
                    catch (RuntimeException | Error query) { failure = accumulate(failure, query); }
                }
            }
            // A coordinator retries only after REVERSIBLE proof. A direct UNKNOWN retry violates
            // that boundary and must not return normally, which would falsely certify completion.
            if (failure == null && !allTerminal()) { failure = new IllegalStateException("Composite cleanup requires exact recovery proof"); }
        } finally { executing = false; }
        rethrow(failure);
    }

    @Override public Resolution resolution() {
        requireIdle();
        executing = true;
        try {
            if (decision == null) { return Resolution.REVERSIBLE; }
            boolean retryable = true;
            Throwable failure = null;
            for (int index = 0; index < transactions.size(); index++) {
                if (terminal[index] || !uncertain[index]) { continue; }
                try {
                    if (!confirmOrRetry(index, transactions.get(index)) && !terminal[index]) { retryable = false; }
                } catch (RuntimeException | Error query) { failure = accumulate(failure, query); }
            }
            rethrow(failure);
            if (allTerminal()) { return Objects.requireNonNull(decision); }
            return retryable ? Resolution.REVERSIBLE : Resolution.UNKNOWN;
        } finally {
            executing = false;
        }
    }

    private boolean confirmOrRetry(int index, ReversibleTransaction transaction) {
        var actual = Objects.requireNonNull(transaction.resolution());
        if (actual == decision) { terminal[index] = true; uncertain[index] = false; return false; }
        if (actual == Resolution.UNKNOWN) { return false; }
        if (actual == Resolution.REVERSIBLE) { return true; }
        throw new IllegalStateException("Composite child contradicts its terminal decision: " + actual);
    }

    private boolean allTerminal() {
        for (boolean confirmed : terminal) { if (!confirmed) { return false; } }
        return true;
    }

    private void requireIdle() {
        if (executing) { throw new IllegalStateException("Composite transaction callback is executing"); }
    }

    private static Throwable accumulate(@Nullable Throwable first, Throwable next) {
        if (first == null) { return next; }
        if (first != next) { first.addSuppressed(next); }
        return first;
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }
}
