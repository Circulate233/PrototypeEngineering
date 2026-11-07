package com.circulation.prototype_eng.api.storage;

import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Reversible source application followed by one owner business decision and source finalization.
 * The enclosing coordinator dispatches each Source on its stable lane, joins actual actions, and
 * retains this batch until finish succeeds. No method waits for another lane or owns an executor.
 * Business callbacks have the same reversible/terminal contract as ResourceTransactions.commit.
 */
public final class ResourceBatch {
    private final Thread owner = Thread.currentThread();
    private final Object lock = new Object();
    private final StorageFence fence;
    private final ObjectList<Source> sources;
    private final ObjectList<ReversibleTransaction> transactions;
    private final boolean[] businessTerminal;
    private final boolean[] businessUncertain;
    private Decision decision = Decision.OPEN;
    private Business business = Business.ACQUIRED;
    private int runningSources;
    private boolean ownerAction;
    private boolean closed;

    private ResourceBatch(StorageFence fence,
                          Reference2ObjectLinkedOpenHashMap<StorageContext, ObjectArrayList<ResourceReservation>> grouped,
                          ObjectList<ReversibleTransaction> transactions) {
        this.fence = fence;
        this.transactions = transactions;
        businessTerminal = new boolean[transactions.size()];
        businessUncertain = new boolean[transactions.size()];
        var ordered = new ObjectArrayList<Source>(grouped.size());
        for (var entry : grouped.reference2ObjectEntrySet()) { ordered.add(new Source(entry.getKey(), entry.getValue())); }
        sources = ObjectLists.unmodifiable(ordered);
    }

    /**
     * Null means a busy domain; ownership of every reservation and business acquisition stays with
     * the caller. Success transfers that cleanup obligation to this batch. Token identity and domain
     * membership are frozen before claiming. Empty batches still preserve the business boundary.
     */
    @Nullable
    public static ResourceBatch tryAcquire(List<? extends ResourceReservation> reservations,
                                           Runnable commit, Runnable complete, Runnable rollback) {
        Objects.requireNonNull(commit);
        Objects.requireNonNull(complete);
        Objects.requireNonNull(rollback);
        return tryAcquire(reservations, List.of(new CallbackTransaction(commit, complete, rollback)));
    }

    /** Each acquired business transaction appears once; failure recovery keeps its exact instance. */
    @Nullable
    public static ResourceBatch tryAcquire(List<? extends ResourceReservation> reservations,
                                           List<? extends ReversibleTransaction> transactions) {
        var businessTransactions = new ObjectArrayList<ReversibleTransaction>(transactions);
        var businessIdentities = new ReferenceOpenHashSet<ReversibleTransaction>();
        for (var transaction : businessTransactions) {
            if (!businessIdentities.add(Objects.requireNonNull(transaction))) {
                throw new IllegalArgumentException("Repeated business transaction");
            }
        }
        var unique = new ReferenceOpenHashSet<ResourceReservation>();
        var grouped = new Reference2ObjectLinkedOpenHashMap<StorageContext, ObjectArrayList<ResourceReservation>>();
        for (var reservation : new ObjectArrayList<>(reservations)) {
            Objects.requireNonNull(reservation);
            if (!unique.add(reservation)) { throw new IllegalArgumentException("Repeated reservation token"); }
            var context = Objects.requireNonNull(reservation.context());
            grouped.computeIfAbsent(context, _ -> new ObjectArrayList<>()).add(reservation);
        }
        var fence = StorageFence.tryAcquire(grouped.keySet());
        return fence == null ? null : new ResourceBatch(fence, grouped, businessTransactions);
    }

    public ObjectList<Source> sources() { return sources; }

    public Decision decision() { synchronized (lock) { return decision; } }

    /** Exact release proof, including when finish threw only while publishing notifications. */
    public boolean finished() { synchronized (lock) { return closed; } }

    /** Current readiness only; finish rechecks this under the same lock before releasing isolation. */
    public boolean readyToFinish() {
        synchronized (lock) { return !closed && !ownerAction && runningSources == 0 && terminalParticipants(); }
    }

    private boolean terminalParticipants() {
        boolean committed = decision == Decision.COMPLETE && business == Business.COMPLETED;
        boolean rolledBack = decision == Decision.ROLLBACK && business == Business.ROLLED_BACK;
        SourceState terminal = committed ? SourceState.COMPLETED : SourceState.ROLLED_BACK;
        return (committed || rolledBack) && sources.stream().allMatch(source -> source.state == terminal);
    }

    /**
     * All source actions must have exited successfully. Authorization and domain validity are checked
     * before and after reversible business commit, under the full fence scope. False decides rollback;
     * a throwing commit also decides rollback. Success irrevocably decides completion BEFORE invoking
     * business completion. A completion failure may never be converted into rollback.
     */
    public boolean commit(BooleanSupplier authorization) {
        requireOwnerBoundary();
        Objects.requireNonNull(authorization);
        synchronized (lock) {
            requireIdleOwner();
            if (decision != Decision.OPEN || business != Business.ACQUIRED
                || sources.stream().anyMatch(source -> source.state != SourceState.APPLIED)) {
                throw new IllegalStateException("Source application is not ready for business commit");
            }
            ownerAction = true;
        }
        try {
            return fence.access(() -> {
                if (!authorized(authorization)) { decide(Decision.ROLLBACK); return false; }
                for (var transaction : transactions) {
                    if (!transaction.prepareCommit()) { decide(Decision.ROLLBACK); return false; }
                }
                for (var transaction : transactions) { transaction.commit(); }
                for (var transaction : transactions) {
                    if (!transaction.validateCommit()) { decide(Decision.ROLLBACK); return false; }
                }
                if (!authorized(authorization)) { decide(Decision.ROLLBACK); return false; }
                decide(Decision.COMPLETE);
                return settleBusiness(Decision.COMPLETE);
            });
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                if (decision == Decision.OPEN) { decision = Decision.ROLLBACK; }
            }
            throw failure;
        } finally {
            synchronized (lock) { ownerAction = false; }
        }
    }

    /**
     * Stops new source application immediately. Returns false while already entered actions still
     * run, or after a failed business cleanup whose outcome is unknown. Retry after actual exit;
     * only an exact transaction resolution permits retrying a failed business callback.
     */
    public boolean rollback() {
        requireOwnerBoundary();
        synchronized (lock) {
            if (closed || ownerAction || decision == Decision.COMPLETE) {
                throw new IllegalStateException("Batch cannot decide rollback at this boundary");
            }
            decision = Decision.ROLLBACK;
            if (runningSources != 0) { return false; }
            if (business == Business.ROLLED_BACK) { return true; }
            ownerAction = true;
        }
        try {
            return fence.access(() -> settleBusiness(Decision.ROLLBACK));
        } finally {
            synchronized (lock) { ownerAction = false; }
        }
    }

    /** Queries pending business confirmations and retries only proven-safe terminal transitions. */
    public boolean resolveBusiness() {
        requireOwnerBoundary();
        Decision settling;
        synchronized (lock) {
            if (closed || ownerAction || decision == Decision.OPEN) {
                throw new IllegalStateException("Business settlement has not been decided or is executing");
            }
            if (runningSources != 0) { return false; }
            ownerAction = true;
            settling = decision;
        }
        try { return fence.access(() -> settleBusiness(settling)); }
        finally { synchronized (lock) { ownerAction = false; } }
    }

    private boolean settleBusiness(Decision settling) {
        business = settling == Decision.COMPLETE ? Business.COMPLETION_PENDING : Business.ROLLBACK_PENDING;
        Throwable failure = null;
        for (int offset = 0; offset < transactions.size(); offset++) {
            int index = settling == Decision.COMPLETE ? offset : transactions.size() - 1 - offset;
            if (businessTerminal[index]) { continue; }
            var transaction = transactions.get(index);
            try {
                if (businessUncertain[index] && !confirmBusinessOrRetry(index, transaction, settling)) { continue; }
                if (businessTerminal[index]) { continue; }
                if (settling == Decision.COMPLETE) { transaction.complete(); }
                else { transaction.rollback(); }
                businessTerminal[index] = true;
                businessUncertain[index] = false;
            } catch (RuntimeException | Error cleanup) {
                businessUncertain[index] = true;
                failure = accumulate(failure, cleanup);
                try { confirmBusinessOrRetry(index, transaction, settling); }
                catch (RuntimeException | Error query) { failure = accumulate(failure, query); }
            }
        }
        boolean done = true;
        for (boolean terminal : businessTerminal) { done &= terminal; }
        if (done) { business = settling == Decision.COMPLETE ? Business.COMPLETED : Business.ROLLED_BACK; }
        rethrow(failure);
        return done;
    }

    private boolean confirmBusinessOrRetry(int index, ReversibleTransaction transaction, Decision settling) {
        var resolution = Objects.requireNonNull(transaction.resolution());
        if (resolution == ReversibleTransaction.Resolution.UNKNOWN) { return false; }
        var expected = settling == Decision.COMPLETE ? ReversibleTransaction.Resolution.COMPLETED
            : ReversibleTransaction.Resolution.ROLLED_BACK;
        if (resolution == expected) { businessTerminal[index] = true; businessUncertain[index] = false; return false; }
        if (resolution == ReversibleTransaction.Resolution.REVERSIBLE) { return true; }
        throw new IllegalStateException("Business recovery contradicts the batch decision: " + resolution);
    }

    /**
     * Releases isolation only after business and every exact token have a confirmed terminal outcome.
     * A notification failure happens after release; it does not reopen or compensate this batch.
     */
    public void finish() {
        requireOwnerBoundary();
        synchronized (lock) {
            requireIdleOwner();
            if (!terminalParticipants()) {
                throw new IllegalStateException("Batch still has unconfirmed settlement");
            }
            ownerAction = true;
        }
        try { fence.release(); }
        finally {
            boolean released = fence.released();
            synchronized (lock) { closed = released; ownerAction = false; }
        }
    }

    private boolean authorized(BooleanSupplier authorization) {
        for (var source : sources) { if (!source.context.isValid()) { return false; } }
        return authorization.getAsBoolean();
    }

    private void decide(Decision next) { synchronized (lock) { decision = next; } }

    private void requireOwnerBoundary() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Resource batch requires its owner thread"); }
        StorageFence.requireOutsideAccess();
        StorageContext.requireNoLocks();
    }

    private void requireIdleOwner() {
        if (closed || ownerAction || runningSources != 0) { throw new IllegalStateException("Resource batch is closed or executing"); }
    }

    public enum Decision { OPEN, COMPLETE, ROLLBACK }
    public enum SourceState {
        RESERVED, APPLYING, APPLIED, REJECTED, APPLY_FAILED, RESOLVING, CONFIRMATION_PENDING, COMPLETED, ROLLED_BACK
    }
    private enum Business { ACQUIRED, COMPLETION_PENDING, COMPLETED, ROLLBACK_PENDING, ROLLED_BACK }

    private record CallbackTransaction(Runnable commitAction, Runnable completeAction, Runnable rollbackAction)
        implements ReversibleTransaction {
        @Override public void commit() { commitAction.run(); }
        @Override public void complete() { completeAction.run(); }
        @Override public void rollback() { rollbackAction.run(); }
    }

    /** One physical domain, shared by all aliases and stable across batches on that domain. */
    public final class Source {
        private final StorageContext context;
        private final ObjectList<ResourceReservation> tokens;
        private final boolean[] terminal;
        private final boolean[] uncertain;
        private final String affinity;
        private SourceState state = SourceState.RESERVED;

        private Source(StorageContext context, ObjectList<ResourceReservation> tokens) {
            this.context = context;
            this.tokens = tokens;
            terminal = new boolean[tokens.size()];
            uncertain = new boolean[tokens.size()];
            affinity = "storage:" + context.order();
        }

        public String affinity() { return affinity; }
        public StorageContext context() { return context; }
        public SourceState state() { synchronized (lock) { return state; } }

        /** Called on the assigned serial source lane. False is a known rejection, never a timeout. */
        public boolean apply() {
            StorageFence.requireOutsideAccess();
            StorageContext.requireNoLocks();
            synchronized (lock) {
                if (closed || ownerAction || state != SourceState.RESERVED) {
                    throw new IllegalStateException("Source cannot apply at this boundary");
                }
                if (decision != Decision.OPEN) { return false; }
                state = SourceState.APPLYING;
                runningSources++;
            }
            boolean[] accepted = {false};
            SourceState outcome = SourceState.APPLY_FAILED;
            try {
                fence.access(context, () -> {
                    for (var token : tokens) { if (!token.canApply()) { return; } }
                    for (var token : tokens) { token.apply(); }
                    accepted[0] = true;
                });
                outcome = accepted[0] ? SourceState.APPLIED : SourceState.REJECTED;
                return accepted[0];
            } finally {
                synchronized (lock) { state = outcome; runningSources--; }
            }
        }

        /**
         * Resolves only the decided direction, on this source's original lane. All tokens are attempted
         * even if one throws. Later calls skip confirmed terminal tokens; a failed token is retried only
         * after its exact resolution proves a safe transition. UNKNOWN stays confirmation-pending.
         */
        public boolean resolve() {
            StorageFence.requireOutsideAccess();
            StorageContext.requireNoLocks();
            Decision settling;
            synchronized (lock) {
                if (closed || ownerAction || decision == Decision.OPEN
                    || state == SourceState.APPLYING || state == SourceState.RESOLVING
                    || business == Business.ACQUIRED) {
                    throw new IllegalStateException("Source cannot resolve at this boundary");
                }
                settling = decision;
                if (state == SourceState.COMPLETED || state == SourceState.ROLLED_BACK) { return true; }
                state = SourceState.RESOLVING;
                runningSources++;
            }
            try {
                fence.access(context, () -> resolveTokens(settling));
            } finally {
                synchronized (lock) {
                    boolean done = true;
                    for (boolean confirmed : terminal) { done &= confirmed; }
                    state = done ? (settling == Decision.COMPLETE ? SourceState.COMPLETED : SourceState.ROLLED_BACK)
                        : SourceState.CONFIRMATION_PENDING;
                    runningSources--;
                }
            }
            return state() != SourceState.CONFIRMATION_PENDING;
        }

        private void resolveTokens(Decision settling) {
            Throwable failure = null;
            for (int offset = 0; offset < tokens.size(); offset++) {
                int index = settling == Decision.COMPLETE ? offset : tokens.size() - 1 - offset;
                if (terminal[index]) { continue; }
                var token = tokens.get(index);
                try {
                    if (uncertain[index] && !confirmOrRetry(index, token, settling)) { continue; }
                    if (terminal[index]) { continue; }
                    if (settling == Decision.COMPLETE) { token.complete(); }
                    else { token.rollback(); }
                    terminal[index] = true;
                    uncertain[index] = false;
                } catch (RuntimeException | Error cleanup) {
                    uncertain[index] = true;
                    failure = accumulate(failure, cleanup);
                    // A callback can throw after mutation. Query exact state without replaying it.
                    try { confirmOrRetry(index, token, settling); }
                    catch (RuntimeException | Error query) { failure = accumulate(failure, query); }
                }
            }
            rethrow(failure);
        }

        private boolean confirmOrRetry(int index, ResourceReservation token, Decision settling) {
            var resolution = Objects.requireNonNull(token.resolution());
            if (resolution == ResourceReservation.Resolution.UNKNOWN) { return false; }
            var expected = settling == Decision.COMPLETE ? ResourceReservation.Resolution.COMPLETED
                : ResourceReservation.Resolution.ROLLED_BACK;
            if (resolution == expected) { terminal[index] = true; uncertain[index] = false; return false; }
            if (resolution == ResourceReservation.Resolution.APPLIED
                || settling == Decision.ROLLBACK && resolution == ResourceReservation.Resolution.RESERVED) { return true; }
            throw new IllegalStateException("Reservation recovery contradicts the batch decision: " + resolution);
        }
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
