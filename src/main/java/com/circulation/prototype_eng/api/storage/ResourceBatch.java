package com.circulation.prototype_eng.api.storage;

import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;

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
    private final boolean sequential;
    private final ReferenceOpenHashSet<StorageContext> contexts;
    private final ReferenceOpenHashSet<ResourceReservation> tokenIdentities = new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<ReversibleTransaction> businessIdentities = new ReferenceOpenHashSet<>();
    private final Reference2ObjectLinkedOpenHashMap<StorageContext, Source> sourceDomains = new Reference2ObjectLinkedOpenHashMap<>();
    private final ObjectArrayList<Source> sourceList = new ObjectArrayList<>();
    private final ObjectList<Source> sources;
    private final ObjectArrayList<ReversibleTransaction> transactions;
    private boolean[] businessTerminal;
    private boolean[] businessUncertain;
    private int stagedTransactions;
    private boolean stagedBoundary;
    private boolean nextAcquired;
    private final ObjectArrayList<Savepoint> savepoints = new ObjectArrayList<>();
    @Nullable private Savepoint rewind;
    private Decision decision = Decision.OPEN;
    private Business business = Business.ACQUIRED;
    private int runningSources;
    private boolean ownerAction;
    private boolean closed;

    private ResourceBatch(StorageFence fence,
                          Reference2ObjectLinkedOpenHashMap<StorageContext, ObjectArrayList<ResourceReservation>> grouped,
                          ObjectList<ReversibleTransaction> transactions,
                          ReferenceOpenHashSet<StorageContext> contexts, boolean sequential) {
        this.fence = fence;
        this.sequential = sequential;
        this.contexts = contexts;
        this.transactions = new ObjectArrayList<>(transactions);
        businessIdentities.addAll(transactions);
        businessTerminal = new boolean[transactions.size()];
        businessUncertain = new boolean[transactions.size()];
        for (var entry : grouped.reference2ObjectEntrySet()) {
            var source = new Source(entry.getKey(), entry.getValue());
            sourceList.add(source);
            sourceDomains.put(entry.getKey(), source);
            tokenIdentities.addAll(entry.getValue());
        }
        sources = ObjectLists.unmodifiable(sourceList);
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
        return acquire(List.of(), reservations, transactions, false);
    }

    /**
     * Claims every declared domain together with the initial tokens' domains. Later acquisition may
     * only use this frozen set. Unsupported tokens are rejected before any claim or cleanup transfer.
     */
    @Nullable
    public static ResourceBatch tryAcquireSequential(Collection<StorageContext> declaredContexts,
                                                     List<? extends ResourceReservation> reservations,
                                                     List<? extends ReversibleTransaction> transactions) {
        return acquire(declaredContexts, reservations, transactions, true);
    }

    private static @Nullable ResourceBatch acquire(Collection<StorageContext> declaredContexts,
                                                   List<? extends ResourceReservation> reservations,
                                                   List<? extends ReversibleTransaction> transactions,
                                                   boolean sequential) {
        var contexts = new ReferenceOpenHashSet<StorageContext>();
        for (var context : declaredContexts) { contexts.add(Objects.requireNonNull(context)); }
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
            if (sequential && !reservation.supportsSequentialUse()) {
                throw new IllegalArgumentException("Reservation does not support sequential use");
            }
            var context = Objects.requireNonNull(reservation.context());
            contexts.add(context);
            grouped.computeIfAbsent(context, _ -> new ObjectArrayList<>()).add(reservation);
        }
        var fence = StorageFence.tryAcquire(contexts);
        return fence == null ? null : new ResourceBatch(fence, grouped, businessTransactions, contexts, sequential);
    }

    /**
     * Commits only this segment's acquired business effects while retaining every undo obligation.
     * Success stays OPEN and retains provisional effects; final validateCommit runs exactly once in
     * commit. All source actions must have actually exited before entering this boundary. Preparation
     * and commit are never replayed, including when authorization rejects.
     */
    public boolean stage(BooleanSupplier authorization) {
        return commitBusiness(authorization, true);
    }

    /**
     * Runs one next-segment acquisition under the full original fence after successful staging.
     * A throwing callback retains the original participants; append any exact acquisitions it made
     * before deciding rollback. This callback is never automatically retried.
     */
    public void acquireNext(Runnable acquisition) {
        requireOwnerBoundary();
        Objects.requireNonNull(acquisition);
        synchronized (lock) {
            requireAppendBoundary();
            if (nextAcquired) { throw new IllegalStateException("Next segment acquisition already attempted"); }
            nextAcquired = true;
            ownerAction = true;
        }
        try { fence.access(acquisition); }
        finally { synchronized (lock) { ownerAction = false; } }
    }

    /** Owner reads and exact acquisition cleanup use the original domains, with no source in flight. */
    public void access(Runnable action) {
        requireOwnerBoundary();
        Objects.requireNonNull(action);
        synchronized (lock) { requireIdleOwner(); ownerAction = true; }
        try { fence.access(action); }
        finally { synchronized (lock) { ownerAction = false; } }
    }

    /**
     * Transfers newly acquired participants after acquireNext exits. The complete append is checked
     * before modifying this batch; rejection leaves the original participants and their lanes intact.
     * The caller still owns rejected acquisitions and must clean them in the original fence scope.
     */
    public void append(List<? extends ResourceReservation> reservations,
                       List<? extends ReversibleTransaction> businessTransactions) {
        appendParticipants(reservations, businessTransactions, false);
    }

    /**
     * Registers an acquired rejected segment for exact source-lane cleanup, then decides rollback.
     * Sequential capability is unnecessary because none of these new tokens will be applied. Domain
     * and identity checks still precede every mutation; this method invokes no participant callback.
     */
    public void appendForRollback(List<? extends ResourceReservation> reservations,
                                  List<? extends ReversibleTransaction> businessTransactions) {
        appendParticipants(reservations, businessTransactions, true);
    }

    private void appendParticipants(List<? extends ResourceReservation> reservations,
                                    List<? extends ReversibleTransaction> businessTransactions,
                                    boolean rollbackOnly) {
        requireOwnerBoundary();
        synchronized (lock) {
            requireAppendBoundary();
            if (!nextAcquired) { throw new IllegalStateException("Next segment has not been acquired"); }
            var addedTokens = new ObjectArrayList<ResourceReservation>(reservations);
            var addedBusiness = new ObjectArrayList<ReversibleTransaction>(businessTransactions);
            var uniqueTokens = new ReferenceOpenHashSet<ResourceReservation>();
            var uniqueBusiness = new ReferenceOpenHashSet<ReversibleTransaction>();
            var grouped = new Reference2ObjectLinkedOpenHashMap<StorageContext, ObjectArrayList<ResourceReservation>>();
            for (var token : addedTokens) {
                Objects.requireNonNull(token);
                if (tokenIdentities.contains(token) || !uniqueTokens.add(token)) {
                    throw new IllegalArgumentException("Repeated reservation token");
                }
                if (!rollbackOnly && !token.supportsSequentialUse()) { throw new IllegalArgumentException("Reservation does not support sequential use"); }
                var context = Objects.requireNonNull(token.context());
                if (!contexts.contains(context)) { throw new IllegalArgumentException("Reservation domain was not declared"); }
                grouped.computeIfAbsent(context, _ -> new ObjectArrayList<>()).add(token);
            }
            for (var transaction : addedBusiness) {
                Objects.requireNonNull(transaction);
                if (businessIdentities.contains(transaction) || !uniqueBusiness.add(transaction)) {
                    throw new IllegalArgumentException("Repeated business transaction");
                }
            }
            businessTerminal = Arrays.copyOf(businessTerminal, transactions.size() + addedBusiness.size());
            businessUncertain = Arrays.copyOf(businessUncertain, transactions.size() + addedBusiness.size());
            for (var entry : grouped.reference2ObjectEntrySet()) {
                var source = sourceDomains.get(entry.getKey());
                if (source == null) {
                    source = new Source(entry.getKey(), entry.getValue());
                    sourceDomains.put(entry.getKey(), source);
                    sourceList.add(source);
                } else { source.appendTokens(entry.getValue()); }
            }
            tokenIdentities.addAll(addedTokens);
            transactions.addAll(addedBusiness);
            businessIdentities.addAll(addedBusiness);
            stagedBoundary = false;
            nextAcquired = false;
            if (rollbackOnly) { decision = Decision.ROLLBACK; invalidateSavepoints(); }
        }
    }

    private void requireAppendBoundary() {
        requireIdleOwner();
        if (!sequential || !stagedBoundary || decision != Decision.OPEN || business != Business.ACQUIRED || rewind != null) {
            throw new IllegalStateException("Sequential segment is not ready for acquisition or append");
        }
    }

    public boolean acquiringNext() {
        synchronized (lock) {
            return nextAcquired;
        }
    }

    public ObjectList<Source> sources() { return sources; }

    public Decision decision() { synchronized (lock) { return decision; } }

    /** Exact candidate boundary within this retained sequence; the mark never owns another fence. */
    public Savepoint savepoint() {
        requireOwnerBoundary();
        synchronized (lock) {
            requireAppendBoundary();
            if (nextAcquired) { throw new IllegalStateException("Cannot mark an unregistered acquisition"); }
            var mark = new Savepoint(this, transactions.size());
            for (var source : sources) { mark.tokenPrefixes.put(source, source.tokens.size()); }
            savepoints.add(mark);
            return mark;
        }
    }

    /** Accepts one nested candidate without completing any of its reversible participants. */
    public void releaseSavepoint(Savepoint mark) {
        requireOwnerBoundary();
        synchronized (lock) {
            requireIdleOwner();
            requireSavepoint(mark);
            if (decision != Decision.OPEN || rewind != null || nextAcquired || savepoints.getLast() != mark) {
                throw new IllegalStateException("Savepoints must be released at their nested idle boundary");
            }
            savepoints.removeLast();
            mark.active = false;
        }
    }

    /**
     * Stops forward work and reverses only participants acquired after this mark. Already entered
     * source work must actually exit; false retains the rewind for later exact business confirmation.
     * Source compensation remains the enclosing coordinator's original lane responsibility.
     */
    public boolean rollbackTo(Savepoint mark) {
        requireOwnerBoundary();
        synchronized (lock) {
            requireSavepoint(mark);
            if (closed || ownerAction || decision != Decision.OPEN || rewind != null || nextAcquired) {
                throw new IllegalStateException("Batch cannot begin candidate rollback at this boundary");
            }
            rewind = mark;
            business = Business.ROLLBACK_PENDING;
            if (runningSources != 0) { return false; }
            ownerAction = true;
        }
        try { return fence.access(() -> settleBusiness(Decision.ROLLBACK)); }
        finally { synchronized (lock) { ownerAction = false; } }
    }

    public boolean rewinding() { synchronized (lock) { return rewind != null; } }

    /**
     * Consumes the target and descendant marks only after every suffix action has exited and reached
     * a confirmed terminal state. Prefix effects and the original fence remain available to a new
     * candidate; no callback, source application or business commit is replayed while trimming.
     */
    public boolean finishRollbackTo(Savepoint mark) {
        requireOwnerBoundary();
        synchronized (lock) {
            requireSavepoint(mark);
            if (closed || ownerAction || decision != Decision.OPEN || rewind != mark || nextAcquired) {
                throw new IllegalStateException("Savepoint does not own the current candidate rollback");
            }
            if (runningSources != 0 || !allConfirmed(businessTerminal, mark.businessPrefix)) { return false; }
            for (var source : sources) {
                if (!allConfirmed(source.terminal, mark.tokenPrefixes.getInt(source))) { return false; }
            }
            for (int index = mark.businessPrefix; index < transactions.size(); index++) {
                businessIdentities.remove(transactions.get(index));
            }
            transactions.subList(mark.businessPrefix, transactions.size()).clear();
            businessTerminal = Arrays.copyOf(businessTerminal, mark.businessPrefix);
            businessUncertain = Arrays.copyOf(businessUncertain, mark.businessPrefix);
            for (var source : sources) { source.trimTo(mark.tokenPrefixes.getInt(source)); }
            int markIndex = savepoints.indexOf(mark);
            for (int index = savepoints.size() - 1; index >= markIndex; index--) {
                savepoints.remove(index).active = false;
            }
            rewind = null;
            business = Business.ACQUIRED;
            stagedTransactions = transactions.size();
            stagedBoundary = true;
            return true;
        }
    }

    private void requireSavepoint(Savepoint mark) {
        Objects.requireNonNull(mark);
        if (mark.batch != this || !mark.active || !savepoints.contains(mark)) {
            throw new IllegalStateException("Savepoint is foreign, consumed or stale");
        }
    }

    private void invalidateSavepoints() {
        for (var mark : savepoints) { mark.active = false; }
        savepoints.clear();
    }

    /** Opaque nested marker; only its originating batch may inspect or consume its exact prefixes. */
    public static final class Savepoint {
        private final ResourceBatch batch;
        private final int businessPrefix;
        private final Reference2IntOpenHashMap<Source> tokenPrefixes = new Reference2IntOpenHashMap<>();
        private boolean active = true;

        private Savepoint(ResourceBatch batch, int businessPrefix) {
            this.batch = batch;
            this.businessPrefix = businessPrefix;
        }
    }

    /** Exact release proof, including when finish threw only while publishing notifications. */
    public boolean finished() { synchronized (lock) { return closed; } }

    /** Current readiness only; finish rechecks this under the same lock before releasing isolation. */
    public boolean readyToFinish() {
        synchronized (lock) { return !closed && !ownerAction && runningSources == 0 && terminalParticipants(); }
    }

    private boolean terminalParticipants() {
        if (nextAcquired || rewind != null) { return false; }
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
        return commitBusiness(authorization, false);
    }

    private boolean commitBusiness(BooleanSupplier authorization, boolean staging) {
        requireOwnerBoundary();
        Objects.requireNonNull(authorization);
        synchronized (lock) {
            requireIdleOwner();
            if (staging && (!sequential || stagedBoundary) || nextAcquired || rewind != null
                || decision != Decision.OPEN || business != Business.ACQUIRED
                || sources.stream().anyMatch(source -> source.state != SourceState.APPLIED)) {
                throw new IllegalStateException("Source application is not ready for business commit");
            }
            ownerAction = true;
        }
        try {
            return fence.access(() -> {
                if (!authorized(authorization)) { decide(Decision.ROLLBACK); return false; }
                for (int index = stagedTransactions; index < transactions.size(); index++) {
                    var transaction = transactions.get(index);
                    if (!transaction.prepareCommit()) { decide(Decision.ROLLBACK); return false; }
                }
                for (int index = stagedTransactions; index < transactions.size(); index++) { transactions.get(index).commit(); }
                if (!staging) {
                    for (var transaction : transactions) {
                        if (!transaction.validateCommit()) { decide(Decision.ROLLBACK); return false; }
                    }
                }
                if (!authorized(authorization)) { decide(Decision.ROLLBACK); return false; }
                if (staging) {
                    synchronized (lock) { stagedTransactions = transactions.size(); stagedBoundary = true; }
                    return true;
                }
                decide(Decision.COMPLETE);
                return settleBusiness(Decision.COMPLETE);
            });
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                if (decision == Decision.OPEN) { decision = Decision.ROLLBACK; invalidateSavepoints(); }
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
            if (closed || ownerAction || decision == Decision.COMPLETE || nextAcquired) {
                throw new IllegalStateException("Batch cannot decide rollback at this boundary");
            }
            decision = Decision.ROLLBACK;
            if (rewind != null) {
                rewind = null;
                business = Business.ROLLBACK_PENDING;
                for (var source : sources) {
                    if (source.state != SourceState.APPLYING && source.state != SourceState.RESOLVING
                        && !allConfirmed(source.terminal, 0)) { source.state = SourceState.CONFIRMATION_PENDING; }
                }
            }
            invalidateSavepoints();
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
            if (closed || ownerAction || decision == Decision.OPEN && rewind == null) {
                throw new IllegalStateException("Business settlement has not been decided or is executing");
            }
            if (runningSources != 0) { return false; }
            ownerAction = true;
            settling = rewind == null ? decision : Decision.ROLLBACK;
        }
        try { return fence.access(() -> settleBusiness(settling)); }
        finally { synchronized (lock) { ownerAction = false; } }
    }

    private boolean settleBusiness(Decision settling) {
        int first = rewind == null ? 0 : rewind.businessPrefix;
        business = settling == Decision.COMPLETE ? Business.COMPLETION_PENDING : Business.ROLLBACK_PENDING;
        Throwable failure = null;
        for (int offset = 0; offset < transactions.size() - first; offset++) {
            int index = settling == Decision.COMPLETE ? offset : transactions.size() - 1 - offset;
            if (businessTerminal[index]) { continue; }
            var transaction = transactions.get(index);
            try {
                if (businessUncertain[index] && !confirmBusinessOrRetry(index, transaction, settling)) {
                    if (sequential && settling == Decision.ROLLBACK && !businessTerminal[index]) { break; }
                    continue;
                }
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
                if (sequential && settling == Decision.ROLLBACK && !businessTerminal[index]) { break; }
            }
        }
        boolean done = allConfirmed(businessTerminal, first);
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
        for (var context : contexts) { if (!context.isValid()) { return false; } }
        return authorization.getAsBoolean();
    }

    private void decide(Decision next) {
        synchronized (lock) { decision = next; if (next != Decision.OPEN) { invalidateSavepoints(); } }
    }

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
        private final ObjectArrayList<ResourceReservation> tokens;
        private boolean[] terminal;
        private boolean[] uncertain;
        private int appliedTokens;
        private int checkedTokens;
        private final String affinity;
        private SourceState state = SourceState.RESERVED;

        private Source(StorageContext context, ObjectList<ResourceReservation> tokens) {
            this.context = context;
            this.tokens = new ObjectArrayList<>(tokens);
            terminal = new boolean[tokens.size()];
            uncertain = new boolean[tokens.size()];
            affinity = "storage:" + context.order();
        }

        private void appendTokens(ObjectList<ResourceReservation> added) {
            terminal = Arrays.copyOf(terminal, tokens.size() + added.size());
            uncertain = Arrays.copyOf(uncertain, tokens.size() + added.size());
            tokens.addAll(added);
            checkedTokens = appliedTokens;
            state = SourceState.RESERVED;
        }

        private void trimTo(int first) {
            for (int index = first; index < tokens.size(); index++) { tokenIdentities.remove(tokens.get(index)); }
            tokens.subList(first, tokens.size()).clear();
            terminal = Arrays.copyOf(terminal, first);
            uncertain = Arrays.copyOf(uncertain, first);
            appliedTokens = first;
            checkedTokens = first;
            state = SourceState.APPLIED;
        }

        public String affinity() { return affinity; }
        public StorageContext context() { return context; }
        public SourceState state() { synchronized (lock) { return state; } }

        /** Called on the assigned serial source lane. False is a known rejection, never a timeout. */
        public boolean apply() {
            return applyWithinBudget(new RecipeWorkBudget(Long.MAX_VALUE));
                }

        /**
         * True includes an ordinary work yield; APPLIED alone proves all token effects finished.
         */
        public boolean applyWithinBudget(RecipeWorkBudget budget) {
            StorageFence.requireOutsideAccess();
            StorageContext.requireNoLocks();
            synchronized (lock) {
                if (closed || ownerAction || state != SourceState.RESERVED) {
                    throw new IllegalStateException("Source cannot apply at this boundary");
                }
                if (decision != Decision.OPEN || rewind != null) { return false; }
                state = SourceState.APPLYING;
                runningSources++;
            }
            boolean[] accepted = {false};
            boolean[] yielded = {false};
            SourceState outcome = SourceState.APPLY_FAILED;
            try {
                fence.access(context, () -> {
                    while (checkedTokens < tokens.size()) {
                        if (!budget.take()) {
                            yielded[0] = true;
                            return;
                        }
                        if (!tokens.get(checkedTokens).canApply()) { return; }
                        checkedTokens++;
                    }
                    while (appliedTokens < tokens.size()) {
                        if (!budget.take()) {
                            yielded[0] = true;
                            return;
                        }
                        var token = tokens.get(appliedTokens);
                        token.apply();
                        if (sequential) { token.exposeApplied(); }
                        appliedTokens++;
                    }
                    accepted[0] = true;
                });
                outcome = yielded[0] ? SourceState.RESERVED : accepted[0] ? SourceState.APPLIED : SourceState.REJECTED;
                return accepted[0] || yielded[0];
            } finally {
                synchronized (lock) { state = outcome; runningSources--; }
            }
        }

        /**
         * Resolves only the decided direction, on this source's original lane. Ordinary batches attempt
         * all tokens even if one throws; sequential rollback stops before any unconfirmed dependency.
         * Later calls skip confirmed terminal tokens; a failed token is retried only
         * after its exact resolution proves a safe transition. UNKNOWN stays confirmation-pending.
         */
        public boolean resolve() {
            StorageFence.requireOutsideAccess();
            StorageContext.requireNoLocks();
            Decision settling;
            int first;
            synchronized (lock) {
                if (closed || ownerAction || decision == Decision.OPEN && rewind == null
                    || state == SourceState.APPLYING || state == SourceState.RESOLVING
                    || business == Business.ACQUIRED && rewind == null) {
                    throw new IllegalStateException("Source cannot resolve at this boundary");
                }
                if ((rewind != null || sequential && decision == Decision.ROLLBACK) && business != Business.ROLLED_BACK) { return false; }
                settling = rewind == null ? decision : Decision.ROLLBACK;
                first = rewind == null ? 0 : rewind.tokenPrefixes.getInt(this);
                if (allConfirmed(terminal, first)) {
                    if (rewind == null) { state = settling == Decision.COMPLETE ? SourceState.COMPLETED : SourceState.ROLLED_BACK; }
                    return true;
                }
                state = SourceState.RESOLVING;
                runningSources++;
            }
            try {
                fence.access(context, () -> resolveTokens(settling, first));
            } finally {
                synchronized (lock) {
                    int currentFirst = rewind == null ? 0 : rewind.tokenPrefixes.getInt(this);
                    boolean done = allConfirmed(terminal, currentFirst);
                    state = done ? (settling == Decision.COMPLETE ? SourceState.COMPLETED : SourceState.ROLLED_BACK)
                        : SourceState.CONFIRMATION_PENDING;
                    runningSources--;
                }
            }
            return state() != SourceState.CONFIRMATION_PENDING;
        }

        private void resolveTokens(Decision settling, int first) {
            Throwable failure = null;
            for (int offset = 0; offset < tokens.size() - first; offset++) {
                int index = settling == Decision.COMPLETE ? offset : tokens.size() - 1 - offset;
                if (terminal[index]) { continue; }
                var token = tokens.get(index);
                try {
                    if (uncertain[index] && !confirmOrRetry(index, token, settling)) {
                        if (sequential && settling == Decision.ROLLBACK && !terminal[index]) { break; }
                        continue;
                    }
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
                    if (sequential && settling == Decision.ROLLBACK && !terminal[index]) { break; }
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

    private static boolean allConfirmed(boolean[] confirmed, int first) {
        for (int index = first; index < confirmed.length; index++) { if (!confirmed[index]) { return false; } }
        return true;
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
