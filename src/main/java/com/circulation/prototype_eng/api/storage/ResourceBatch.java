package com.circulation.prototype_eng.api.storage;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.transaction.ReservedTransaction;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * Original reversible batch and source lanes, with owner-local resumable preparation/settlement.
 * A budget pause retains the exact participants and cursor, never a lock or scope. A source action
 * is counted until its finally has actually exited; state alone is not a worker-exit proof.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class ResourceBatch {
    private final Thread owner = Thread.currentThread();
    private final Object lock = new Object();
    private final boolean sequential;
    private final ReferenceOpenHashSet<StorageContext> contexts = new ReferenceOpenHashSet<>();
    private final ObjectArrayList<StorageContext> contextList = new ObjectArrayList<>();
    private final ReferenceOpenHashSet<ResourceReservation> tokenIdentities = new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<ReversibleTransaction> businessIdentities = new ReferenceOpenHashSet<>();
    private final Reference2ObjectLinkedOpenHashMap<StorageContext, Source> sourceDomains = new Reference2ObjectLinkedOpenHashMap<>();
    private final ObjectArrayList<Source> sources = new ObjectArrayList<>();
    private final ObjectArrayList<Participant> transactions = new ObjectArrayList<>();
    private StorageFence fence;
    private ObjectArrayList<Savepoint> savepoints = new ObjectArrayList<>();
    private Object savepointGeneration = new Object();
    private int stagedTransactions, appliedSources, terminalSources, fullyConfirmedSources, confirmedBusiness, runningSources;
    private boolean stagedBoundary, nextAcquired, ownerAction, closed, workYielded;
    private Decision decision = Decision.OPEN;
    private Business business = Business.ACQUIRED;
    private @Nullable Savepoint rewind;
    private @Nullable Stage stage;
    private @Nullable Append append;
    private @Nullable Rejected rejected;
    private @Nullable Savepoint captureMark;
    private int captureSource;
    private @Nullable Trim trim;
    private int businessCursor = -1;
    private @Nullable Throwable businessFailure;
    private @Nullable StorageFence.Release release;

    private ResourceBatch(boolean sequential) {
        this.sequential = sequential;
    }

    private static RecipeWorkBudget unlimited() {
        return new RecipeWorkBudget(Long.MAX_VALUE);
    }

    public static Acquire beginAcquire(List<? extends ResourceReservation> reservations,
                                       List<? extends ReversibleTransaction> transactions) {
        return new Acquire(List.of(), reservations, transactions, false);
    }

    public static Acquire beginAcquireSequential(Collection<StorageContext> declaredContexts,
                                                 List<? extends ResourceReservation> reservations,
                                                 List<? extends ReversibleTransaction> transactions) {
        return new Acquire(declaredContexts, reservations, transactions, true);
    }

    public static @Nullable ResourceBatch tryAcquire(List<? extends ResourceReservation> reservations,
                                                     Runnable commit, Runnable complete, Runnable rollback) {
        return tryAcquire(reservations, List.of(new CallbackTransaction(Objects.requireNonNull(commit),
            Objects.requireNonNull(complete), Objects.requireNonNull(rollback))));
    }

    @SuppressWarnings("StatementWithEmptyBody") // The original cursor performs every synchronous drain step.
    public static @Nullable ResourceBatch tryAcquire(List<? extends ResourceReservation> reservations,
                                                     List<? extends ReversibleTransaction> transactions) {
        var acquisition = beginAcquire(reservations, transactions);
        while (!acquisition.advance(unlimited())) {
        }
        return acquisition.result();
    }

    @SuppressWarnings("StatementWithEmptyBody") // The original cursor performs every synchronous drain step.
    public static @Nullable ResourceBatch tryAcquireSequential(Collection<StorageContext> contexts,
                                                               List<? extends ResourceReservation> reservations,
                                                               List<? extends ReversibleTransaction> transactions) {
        var acquisition = beginAcquireSequential(contexts, reservations, transactions);
        while (!acquisition.advance(unlimited())) {
        }
        return acquisition.result();
    }

    private static @Nullable Throwable accumulate(@Nullable Throwable first, Throwable next) {
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

    private void addContext(StorageContext context) {
        if (contexts.add(context)) {
            contextList.add(context);
        }
    }

    private void addToken(StorageContext context, ResourceReservation token) {
        var source = sourceDomains.get(context);
        if (source == null) {
            source = new Source(context);
            sourceDomains.put(context, source);
            sources.add(source);
        }
        source.tokens.add(new Token(token));
        source.refreshConfirmed();
        source.checkedTokens = source.appliedTokens;
        source.checking = null;
        source.applying = null;
        source.pendingExpose = false;
        source.resolveCursor = -1;
        source.setState(SourceState.RESERVED);
    }

    public ObjectList<Source> sources() {
        return sources;
    }

    /**
     * O(1) frozen domain membership for exact continuation adoption, including initial token domains.
     * This is classification only: it grants no storage access, active scope or cleanup permission.
     */
    public boolean ownsContext(StorageContext context) {
        requireOwnerBoundary();
        return contexts.contains(Objects.requireNonNull(context));
    }

    public Decision decision() {
        synchronized (lock) {
            return decision;
        }
    }

    public boolean acquiringNext() {
        synchronized (lock) {
            return nextAcquired;
        }
    }

    public boolean rewinding() {
        synchronized (lock) {
            return rewind != null;
        }
    }

    /**
     * False return + true here is allowance/gate yield; false here is known rejection or UNKNOWN.
     */
    public boolean workYielded() {
        return workYielded;
    }

    /**
     * Legacy owner callback retains the full physical base-lock contract.
     */
    public void access(Runnable action) {
        requireOwnerBoundary();
        Objects.requireNonNull(action);
        synchronized (lock) {
            requireIdleOwner();
            if (stage != null) {
                throw new IllegalStateException("Business stage is pending");
            }
            ownerAction = true;
        }
        try {
            fence.access(action);
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    /**
     * Caller drives its transparent callback cursor; each invocation gets a fresh owner capability.
     */
    public boolean accessWithinBudget(Runnable action) {
        requireOwnerBoundary();
        Objects.requireNonNull(action);
        workYielded = false;
        synchronized (lock) {
            requireIdleOwner();
            if (stage != null) {
                throw new IllegalStateException("Business stage is pending");
            }
            ownerAction = true;
        }
        try {
            boolean entered = fence.tryAccessWithinBudget(action);
            workYielded = !entered;
            return entered;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    public void acquireNext(Runnable acquisition) {
        requireOwnerBoundary();
        Objects.requireNonNull(acquisition);
        synchronized (lock) {
            requireAppendBoundary();
            if (nextAcquired) {
                throw new IllegalStateException("Next acquisition already attempted");
            }
            nextAcquired = true;
            ownerAction = true;
        }
        try {
            fence.access(acquisition);
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    /**
     * Reentry resumes caller's same acquisition cursor, never automatically replays its callbacks.
     */
    public boolean acquireNextWithinBudget(Runnable acquisition) {
        requireOwnerBoundary();
        Objects.requireNonNull(acquisition);
        workYielded = false;
        synchronized (lock) {
            requireAppendBoundary();
            ownerAction = true;
        }
        try {
            boolean entered = fence.tryAccessWithinBudget(() -> {
                nextAcquired = true;
                acquisition.run();
            });
            workYielded = !entered;
            return entered;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    // The original cursor performs the synchronous drain in the loop condition.
    public void append(List<? extends ResourceReservation> reservations, List<? extends ReversibleTransaction> transactions) {
        while (!appendWithinBudget(reservations, transactions, false, unlimited())) {
        }
    }

    @SuppressWarnings("StatementWithEmptyBody") // The original cursor performs every synchronous drain step.
    public void appendForRollback(List<? extends ResourceReservation> reservations, List<? extends ReversibleTransaction> transactions) {
        while (!appendWithinBudget(reservations, transactions, true, unlimited())) {
        }
    }

    /**
     * Full validation precedes adoption. During a partial adoption the batch admits no other work.
     */
    public boolean appendWithinBudget(List<? extends ResourceReservation> reservations,
                                      List<? extends ReversibleTransaction> transactions,
                                      boolean rollbackOnly, RecipeWorkBudget budget) {
        requireOwnerBoundary();
        Objects.requireNonNull(budget);
        workYielded = false;
        synchronized (lock) {
            if (append == null) {
                requireAppendBoundary();
                if (!nextAcquired) {
                    throw new IllegalStateException("Next segment has not been acquired");
                }
                append = new Append(reservations, transactions, rollbackOnly);
            } else if (append.inputTokens != reservations || append.inputBusiness != transactions) {
                throw new IllegalStateException("Append must resume its exact stable input lists");
            }
            if (rollbackOnly) {
                append.rollbackOnly = true;
            }
            if (ownerAction || runningSources != 0 || closed) {
                throw new IllegalStateException("Append is executing");
            }
            ownerAction = true;
        }
        try {
            if (!append.advance(budget)) {
                workYielded = true;
                return false;
            }
            boolean rollback = append.rollbackOnly;
            append = null;
            nextAcquired = false;
            stagedBoundary = false;
            if (rollback) {
                decide(Decision.ROLLBACK);
            }
            return true;
        } catch (RuntimeException | Error failure) {
            // Validation never transferred cleanup; allow the caller to clean rejected inputs.
            if (append != null && append.phase < 2) {
                append = null;
            }
            throw failure;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    /**
     * Adopts only a failed child of the current unregistered acquisition for rollback. No apply,
     * preparation or stage occurs. Its private suffix mark reuses the original Source objects and
     * fence. Stable child input lists remain attached until this exact adoption completes.
     */
    public boolean adoptRejectedWithinBudget(List<? extends ResourceReservation> reservations,
                                             List<? extends ReversibleTransaction> transactions,
                                             RecipeWorkBudget budget) {
        requireOwnerBoundary();
        Objects.requireNonNull(budget);
        workYielded = false;
        synchronized (lock) {
            if (rejected == null) {
                requireAppendBoundary();
                if (!nextAcquired) {
                    throw new IllegalStateException("Rejected child requires the original acquisition window");
                }
                rejected = new Rejected(reservations, transactions);
            } else if (rejected.preparation.inputTokens != reservations || rejected.preparation.inputBusiness != transactions
                || rejected.adopted) {
                throw new IllegalStateException("Rejected child must resume its exact pending adoption");
            }
            if (ownerAction || runningSources != 0 || closed) {
                throw new IllegalStateException("Rejected child adoption is executing");
            }
            ownerAction = true;
        }
        try {
            while (!rejected.captured) {
                if (rejected.sourceIndex == sources.size()) {
                    savepoints.add(rejected.mark);
                    rejected.captured = true;
                    break;
                }
                if (!budget.take()) {
                    workYielded = true;
                    return false;
                }
                var source = sources.get(rejected.sourceIndex++);
                rejected.mark.tokenPrefixes.put(source, source.tokens.size());
            }
            if (!rejected.preparation.advance(budget)) {
                workYielded = true;
                return false;
            }
            rejected.adopted = true;
            rewind = rejected.mark;
            businessCursor = -1;
            business = Business.ROLLBACK_PENDING;
            return true;
        } catch (RuntimeException | Error failure) {
            if (rejected != null && rejected.preparation.phase < 2) {
                if (rejected.captured) {
                    savepoints.removeLast().active = false;
                }
                rejected = null;
            }
            throw failure;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    /**
     * After original business/source lanes confirm the child suffix, trims it and resumes the same
     * nextAcquired window. This cleanup needs no fresh forward authorization and releases no fence.
     */
    public boolean finishRejectedWithinBudget(RecipeWorkBudget budget) {
        requireOwnerBoundary();
        Objects.requireNonNull(budget);
        workYielded = false;
        synchronized (lock) {
            if (rejected == null || !rejected.adopted || decision != Decision.OPEN || rewind != rejected.mark
                || !nextAcquired || closed || ownerAction) {
                throw new IllegalStateException("No exact adopted child cleanup is pending");
            }
            if (runningSources != 0 || business != Business.ROLLED_BACK) {
                return false;
            }
            if (trim == null) {
                trim = new Trim(rejected.mark);
            }
            ownerAction = true;
        }
        try {
            if (!trim.advance(budget)) {
                return false;
            }
            trim = null;
            rewind = null;
            rejected = null;
            business = Business.ACQUIRED;
            businessCursor = -1;
            stagedTransactions = transactions.size();
            stagedBoundary = true;
            return true;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    public Savepoint savepoint() {
        Savepoint mark;
        do {
            mark = savepointWithinBudget(unlimited());
        } while (mark == null);
        return mark;
    }

    /**
     * Null is capture yield. A mark grants no cleanup boundary until its whole prefix is frozen.
     */
    public @Nullable Savepoint savepointWithinBudget(RecipeWorkBudget budget) {
        requireOwnerBoundary();
        workYielded = false;
        synchronized (lock) {
            if (captureMark == null) {
                requireAppendBoundary();
                if (nextAcquired) {
                    throw new IllegalStateException("Cannot mark an unregistered acquisition");
                }
                captureMark = new Savepoint(this, transactions.size(), savepoints.size(), savepointGeneration);
                captureSource = 0;
            }
            if (ownerAction || runningSources != 0 || closed) {
                throw new IllegalStateException("Savepoint capture is executing");
            }
            ownerAction = true;
        }
        try {
            while (captureSource < sources.size()) {
                if (!budget.take()) {
                    workYielded = true;
                    return null;
                }
                var source = sources.get(captureSource++);
                captureMark.tokenPrefixes.put(source, source.tokens.size());
            }
            var mark = captureMark;
            savepoints.add(mark);
            captureMark = null;
            return mark;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    public void releaseSavepoint(Savepoint mark) {
        requireOwnerBoundary();
        synchronized (lock) {
            requireIdleOwner();
            requireSavepoint(mark);
            if (decision != Decision.OPEN || rewind != null || nextAcquired || savepoints.getLast() != mark) {
                throw new IllegalStateException("Savepoints require their nested idle boundary");
            }
            savepoints.removeLast();
            mark.active = false;
        }
    }

    public boolean rollbackTo(Savepoint mark) {
        return rollbackTo(mark, unlimited(), true);
    }

    public boolean rollbackToWithinBudget(Savepoint mark, RecipeWorkBudget budget) {
        return rollbackTo(mark, budget, false);
    }

    private boolean rollbackTo(Savepoint mark, RecipeWorkBudget budget, boolean legacy) {
        requireOwnerBoundary();
        workYielded = false;
        synchronized (lock) {
            requireSavepoint(mark);
            if (closed || ownerAction || decision != Decision.OPEN || nextAcquired || append != null || captureMark != null
                || rewind != null && rewind != mark) {
                throw new IllegalStateException("Cannot begin candidate rollback");
            }
            if (rewind == null) {
                rewind = mark;
                stage = null;
                businessCursor = -1;
                business = Business.ROLLBACK_PENDING;
            }
        }
        return resolveBusiness(budget, legacy);
    }

    public boolean finishRollbackTo(Savepoint mark) {
        return finishRollbackToWithinBudget(mark, unlimited());
    }

    /**
     * Verify the exact suffix, then trim one identity/token/mark per unit along the same fence.
     */
    public boolean finishRollbackToWithinBudget(Savepoint mark, RecipeWorkBudget budget) {
        requireOwnerBoundary();
        workYielded = false;
        synchronized (lock) {
            requireSavepoint(mark);
            if (closed || ownerAction || decision != Decision.OPEN || rewind != mark || nextAcquired) {
                throw new IllegalStateException("Savepoint does not own this rewind");
            }
            if (runningSources != 0 || business != Business.ROLLED_BACK) {
                return false;
            }
            if (trim == null) {
                trim = new Trim(mark);
            }
            ownerAction = true;
        }
        try {
            if (!trim.advance(budget)) {
                return false;
            }
            trim = null;
            rewind = null;
            business = Business.ACQUIRED;
            businessCursor = -1;
            stagedTransactions = transactions.size();
            stagedBoundary = true;
            return true;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    private void requireSavepoint(Savepoint mark) {
        Objects.requireNonNull(mark);
        if (mark.batch != this || !mark.active || mark.generation != savepointGeneration || mark.index >= savepoints.size()
            || savepoints.get(mark.index) != mark) {
            throw new IllegalStateException("Savepoint is foreign, consumed or stale");
        }
    }

    private void invalidateSavepoints() {
        savepointGeneration = new Object();
        savepoints = new ObjectArrayList<>();
        captureMark = null;
    }

    public boolean stage(BooleanSupplier authorization) {
        return commitBusiness(authorization, true, unlimited(), true);
    }

    public boolean commit(BooleanSupplier authorization) {
        return commitBusiness(authorization, false, unlimited(), true);
    }

    public boolean stageWithinBudget(BooleanSupplier authorization, RecipeWorkBudget budget) {
        return commitBusiness(authorization, true, budget, false);
    }

    public boolean commitWithinBudget(BooleanSupplier authorization, RecipeWorkBudget budget) {
        return commitBusiness(authorization, false, budget, false);
    }

    private boolean commitBusiness(BooleanSupplier authorization, boolean staging, RecipeWorkBudget budget, boolean legacy) {
        requireOwnerBoundary();
        Objects.requireNonNull(authorization);
        Objects.requireNonNull(budget);
        workYielded = false;
        synchronized (lock) {
            requireIdleOwner();
            if (stage == null) {
                if (staging && (!sequential || stagedBoundary) || nextAcquired || rewind != null || decision != Decision.OPEN
                    || business != Business.ACQUIRED || appliedSources != sources.size()) {
                    throw new IllegalStateException("Source application is not ready for business commit");
                }
                stage = new Stage(staging, legacy);
            } else if (stage.staging != staging) {
                throw new IllegalStateException("Cannot change a pending stage operation");
            }
            ownerAction = true;
        }
        boolean[] done = {false};
        try {
            var activeStage = Objects.requireNonNull(stage);
            if (legacy) {
                fence.access(() -> done[0] = activeStage.advance(authorization, budget));
            } else if (!fence.tryAccessWithinBudget(() -> done[0] = activeStage.advance(authorization, budget))) {
                workYielded = true;
                return false;
            }
            if (!done[0]) {
                return false;
            }
            stage = null;
        } catch (RuntimeException | Error failure) {
            if (decision == Decision.OPEN) {
                decide(Decision.ROLLBACK);
            }
            stage = null;
            throw failure;
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
        if (decision == Decision.ROLLBACK) {
            return false;
        }
        return staging || resolveBusiness(budget, legacy);
    }

    public boolean rollback() {
        return rollback(unlimited(), true);
    }

    public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
        return rollback(budget, false);
    }

    private boolean rollback(RecipeWorkBudget budget, boolean legacy) {
        requireOwnerBoundary();
        workYielded = false;
        synchronized (lock) {
            if (closed || ownerAction || decision == Decision.COMPLETE || nextAcquired || append != null) {
                throw new IllegalStateException("Cannot decide rollback at this boundary");
            }
            if (decision != Decision.ROLLBACK || rewind != null) {
                // A partial trim removed only already-confirmed suffix participants. Expansion keeps
                // every remaining original participant and skips those exact confirmed removals.
                decision = Decision.ROLLBACK;
                rewind = null;
                trim = null;
                stage = null;
                businessCursor = -1;
                business = Business.ROLLBACK_PENDING;
                invalidateSavepoints();
            }
        }
        return resolveBusiness(budget, legacy);
    }

    public boolean resolveBusiness() {
        return resolveBusiness(unlimited(), true);
    }

    public boolean resolveBusinessWithinBudget(RecipeWorkBudget budget) {
        return resolveBusiness(budget, false);
    }

    private boolean resolveBusiness(RecipeWorkBudget budget, boolean legacy) {
        requireOwnerBoundary();
        workYielded = false;
        synchronized (lock) {
            if (closed || ownerAction || append != null || captureMark != null || trim != null && trim.phase != 0
                || decision == Decision.OPEN && rewind == null) {
                throw new IllegalStateException("Business settlement is not decided or is executing");
            }
            if (runningSources != 0) {
                return false;
            }
            if (business == Business.COMPLETED || business == Business.ROLLED_BACK) {
                return true;
            }
            ownerAction = true;
        }
        boolean[] done = {false};
        try {
            if (legacy) {
                fence.access(() -> done[0] = settleBusiness(budget, true));
            } else if (!fence.tryAccessWithinBudget(() -> done[0] = settleBusiness(budget, false))) {
                workYielded = true;
                return false;
            }
            return done[0];
        } finally {
            synchronized (lock) {
                ownerAction = false;
            }
        }
    }

    private boolean settleBusiness(RecipeWorkBudget budget, boolean legacy) {
        Decision settling = rewind == null ? decision : Decision.ROLLBACK;
        int first = rewind == null ? 0 : rewind.businessPrefix;
        business = settling == Decision.COMPLETE ? Business.COMPLETION_PENDING : Business.ROLLBACK_PENDING;
        if (businessCursor < 0) {
            businessCursor = settling == Decision.COMPLETE ? first : transactions.size() - 1;
        }
        while (businessCursor >= first && businessCursor < transactions.size()) {
            var participant = transactions.get(businessCursor);
            if (!participant.selected) {
                if (!budget.take()) {
                    workYielded = true;
                    return false;
                }
                participant.selected = true;
            }
            if (participant.terminal) {
                participant.selected = false;
                businessCursor += settling == Decision.COMPLETE ? 1 : -1;
                continue;
            }
            if (participant.uncertain) {
                boolean queryAfterFailure = participant.failureQuery;
                try {
                    var resolution = legacy && !participant.budgetWork ? Objects.requireNonNull(participant.transaction.resolution())
                        : participant.transaction.resolutionWithinBudget(budget);
                    if (resolution == null) {
                        workYielded = true;
                        return false;
                    }
                    participant.failureQuery = false;
                    var expected = settling == Decision.COMPLETE ? ReversibleTransaction.Resolution.COMPLETED : ReversibleTransaction.Resolution.ROLLED_BACK;
                    if (resolution == expected) {
                        participant.terminal = true;
                        participant.uncertain = false;
                        confirmedBusiness++;
                    } else if (resolution == ReversibleTransaction.Resolution.REVERSIBLE) {
                        if (!queryAfterFailure) {
                            participant.uncertain = false;
                            continue;
                        }
                    } else if (resolution != ReversibleTransaction.Resolution.UNKNOWN) {
                        throw new IllegalStateException("Business recovery contradicts batch decision: " + resolution);
                    }
                } catch (RuntimeException | Error query) {
                    if (query instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                    businessFailure = accumulate(businessFailure, query);
                }
                if (!participant.terminal && sequential && settling == Decision.ROLLBACK) {
                    throwBusinessFailure();
                    return false;
                }
                participant.selected = false;
                businessCursor += settling == Decision.COMPLETE ? 1 : -1;
                continue;
            }
            participant.budgetWork |= !legacy;
            try {
                if (participant.budgetWork) {
                    boolean done = settling == Decision.COMPLETE ? participant.transaction.completeWithinBudget(budget) : participant.transaction.rollbackWithinBudget(budget);
                    if (!done) {
                        workYielded = true;
                        return false;
                    }
                } else if (settling == Decision.COMPLETE) {
                    participant.transaction.complete();
                } else {
                    participant.transaction.rollback();
                }
                participant.terminal = true;
                participant.uncertain = false;
                confirmedBusiness++;
            } catch (RuntimeException | Error failure) {
                participant.uncertain = true;
                participant.failureQuery = true;
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                businessFailure = accumulate(businessFailure, failure);
                continue;
            }
            participant.selected = false;
            businessCursor += settling == Decision.COMPLETE ? 1 : -1;
        }
        boolean done = confirmedBusiness == transactions.size() - first;
        if (done) {
            business = settling == Decision.COMPLETE ? Business.COMPLETED : Business.ROLLED_BACK;
        }
        businessCursor = -1;
        throwBusinessFailure();
        return done;
    }

    private void throwBusinessFailure() {
        var failure = businessFailure;
        businessFailure = null;
        rethrow(failure);
    }

    public boolean readyToFinish() {
        synchronized (lock) {
            return !closed && !ownerAction && runningSources == 0 && terminalParticipants();
        }
    }

    private boolean terminalParticipants() {
        return !nextAcquired && rewind == null && append == null && rejected == null && captureMark == null && trim == null
            && terminalSources == sources.size() && fullyConfirmedSources == sources.size() && (decision == Decision.COMPLETE && business == Business.COMPLETED
            || decision == Decision.ROLLBACK && business == Business.ROLLED_BACK);
    }

    /**
     * Completion includes every saved notification attempt; ownership alone may already be open.
     */
    public boolean finished() {
        synchronized (lock) {
            return closed;
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    // The original cursor performs the synchronous drain in the loop condition.
    public void finish() {
        while (!finishWithinBudget(unlimited())) {
        }
    }

    public boolean finishWithinBudget(RecipeWorkBudget budget) {
        requireOwnerBoundary();
        workYielded = false;
        synchronized (lock) {
            if (closed || ownerAction || runningSources != 0 || !terminalParticipants()) {
                throw new IllegalStateException("Batch still has unconfirmed settlement or is executing");
            }
            ownerAction = true;
        }
        try {
            if (release == null) {
                release = fence.beginRelease();
            }
            boolean done = release.advance(budget::take);
            workYielded = !done;
            return done;
        } finally {
            synchronized (lock) {
                closed = release != null && release.done();
                ownerAction = false;
            }
        }
    }

    private void decide(Decision next) {
        synchronized (lock) {
            decision = next;
            if (next != Decision.OPEN) {
                invalidateSavepoints();
            }
        }
    }

    private void requireOwnerBoundary() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Resource batch requires its owner thread");
        }
        StorageFence.requireOutsideAccess();
        StorageFence.requireOutsideSourceWork();
        StorageContext.requireNoLocks();
    }

    private void requireIdleOwner() {
        if (closed || ownerAction || runningSources != 0 || append != null || rejected != null || captureMark != null || trim != null || release != null) {
            throw new IllegalStateException("Resource batch is closed or executing");
        }
    }

    private void requireAppendBoundary() {
        requireIdleOwner();
        if (!sequential || !stagedBoundary || decision != Decision.OPEN || business != Business.ACQUIRED || rewind != null || stage != null) {
            throw new IllegalStateException("Sequential segment is not ready for acquisition or append");
        }
    }

    public enum Decision {OPEN, COMPLETE, ROLLBACK}

    public enum SourceState {RESERVED, APPLYING, APPLIED, REJECTED, APPLY_FAILED, RESOLVING, CONFIRMATION_PENDING, COMPLETED, ROLLED_BACK}

    private enum Business {ACQUIRED, COMPLETION_PENDING, COMPLETED, ROLLBACK_PENDING, ROLLED_BACK}

    /**
     * Stable input lists stay caller-owned until terminal non-null result transfers cleanup.
     */
    public static final class Acquire {
        private final ResourceBatch batch;
        private final Iterator<StorageContext> declared;
        private final Iterator<? extends ResourceReservation> reservations;
        private final Iterator<? extends ReversibleTransaction> transactions;
        private @Nullable Iterator<? extends ResourceReservation> participantTokens;
        private int phase;
        private boolean cancelled, done;
        private @Nullable StorageFence.Acquire claim;

        private Acquire(Collection<StorageContext> declared, List<? extends ResourceReservation> reservations,
                        List<? extends ReversibleTransaction> transactions, boolean sequential) {
            StorageFence.requireOutsideAccess();
            StorageFence.requireOutsideSourceWork();
            StorageContext.requireNoLocks();
            batch = new ResourceBatch(sequential);
            this.declared = Objects.requireNonNull(declared).iterator();
            this.reservations = Objects.requireNonNull(reservations).iterator();
            this.transactions = Objects.requireNonNull(transactions).iterator();
        }

        /**
         * True is terminal; a null result is BUSY/cancelled with no effective partial claim.
         */
        public boolean advance(RecipeWorkBudget budget) {
            batch.requireOwnerBoundary();
            Objects.requireNonNull(budget);
            if (done) {
                return true;
            }
            if (cancelled && claim == null) {
                done = true;
                return true;
            }
            while (phase == 0) {
                if (!declared.hasNext()) {
                    phase = 1;
                    break;
                }
                if (!budget.take()) {
                    return false;
                }
                batch.addContext(Objects.requireNonNull(declared.next()));
            }
            while (phase == 1) {
                if (participantTokens != null) {
                    while (participantTokens.hasNext()) {
                        if (!budget.take()) return false;
                        addReservation(participantTokens.next());
                    }
                    participantTokens = null;
                }
                if (!transactions.hasNext()) {
                    phase = 2;
                    break;
                }
                if (!budget.take()) {
                    return false;
                }
                var transaction = Objects.requireNonNull(transactions.next());
                if (!batch.businessIdentities.add(transaction)) {
                    throw new IllegalArgumentException("Repeated business transaction");
                }
                batch.transactions.add(new Participant(transaction));
                if (transaction instanceof ReservedTransaction reserved)
                    participantTokens = reserved.reservations().iterator();
            }
            while (phase == 2) {
                if (!reservations.hasNext()) {
                    phase = 3;
                    break;
                }
                if (!budget.take()) {
                    return false;
                }
                addReservation(reservations.next());
            }
            if (claim == null) {
                claim = StorageFence.beginAcquire(batch.contextList);
            }
            if (!claim.advance(budget::take)) {
                return false;
            }
            batch.fence = claim.result();
            done = true;
            return true;
        }

        private void addReservation(ResourceReservation token) {
            if (!batch.tokenIdentities.add(token)) throw new IllegalArgumentException("Repeated reservation token");
            if (batch.sequential && !token.supportsSequentialUse())
                throw new IllegalArgumentException("Reservation does not support sequential use");
            var context = token.context();
            batch.addContext(context);
            batch.addToken(context, token);
        }

        public void cancel() {
            batch.requireOwnerBoundary();
            if (done) {
                throw new IllegalStateException("Batch acquisition already finished");
            }
            cancelled = true;
            if (claim != null) {
                claim.cancel();
            }
        }

        public @Nullable ResourceBatch result() {
            batch.requireOwnerBoundary();
            if (!done) {
                throw new IllegalStateException("Batch acquisition is pending");
            }
            return cancelled || batch.fence == null ? null : batch;
        }
    }

    public static final class Savepoint {
        private final ResourceBatch batch;
        private final int businessPrefix, index;
        private final Object generation;
        private final Reference2IntOpenHashMap<Source> tokenPrefixes = new Reference2IntOpenHashMap<>();
        private boolean active = true;

        private Savepoint(ResourceBatch batch, int prefix, int index, Object generation) {
            this.batch = batch;
            businessPrefix = prefix;
            this.index = index;
            this.generation = generation;
        }
    }

    private static final class Participant {
        private final ReversibleTransaction transaction;
        private boolean terminal, uncertain, failureQuery, selected, budgetWork;

        private Participant(ReversibleTransaction transaction) {
            this.transaction = transaction;
        }
    }

    private static final class Token {
        private final ResourceReservation token;
        private boolean terminal, uncertain, failureQuery, selected;

        private Token(ResourceReservation token) {
            this.token = token;
        }
    }

    private record Step<T>(@Nullable T result, boolean spent) {
    }

    private record CallbackTransaction(Runnable commitAction, Runnable completeAction,
                                       Runnable rollbackAction) implements ReversibleTransaction {
        @Override
        public void commit() {
            commitAction.run();
        }

        @Override
        public void complete() {
            completeAction.run();
        }

        @Override
        public void rollback() {
            rollbackAction.run();
        }
    }

    private final class Append {
        private final List<? extends ResourceReservation> inputTokens;
        private final List<? extends ReversibleTransaction> inputBusiness;
        private final ObjectArrayList<ResourceReservation> tokens = new ObjectArrayList<>();
        private final ObjectArrayList<StorageContext> domains = new ObjectArrayList<>();
        private final ObjectArrayList<ReversibleTransaction> business = new ObjectArrayList<>();
        private final ReferenceOpenHashSet<ResourceReservation> tokenSet = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<ReversibleTransaction> businessSet = new ReferenceOpenHashSet<>();
        private int phase, index;
        private boolean rollbackOnly;
        private @Nullable Iterator<? extends ResourceReservation> participantTokens;

        private Append(List<? extends ResourceReservation> tokens, List<? extends ReversibleTransaction> business, boolean rollbackOnly) {
            inputTokens = Objects.requireNonNull(tokens);
            inputBusiness = Objects.requireNonNull(business);
            this.rollbackOnly = rollbackOnly;
        }

        private boolean advance(RecipeWorkBudget budget) {
            while (phase < 4) {
                if (participantTokens != null) {
                    while (participantTokens.hasNext()) {
                        if (!budget.take()) return false;
                        captureToken(participantTokens.next());
                    }
                    participantTokens = null;
                }
                int size = phase == 0 ? inputTokens.size() : phase == 1 ? inputBusiness.size() : phase == 2 ? tokens.size() : business.size();
                if (index == size) {
                    phase++;
                    index = 0;
                    continue;
                }
                if (!budget.take()) {
                    return false;
                }
                if (phase == 0) {
                    captureToken(inputTokens.get(index));
                } else if (phase == 1) {
                    var transaction = Objects.requireNonNull(inputBusiness.get(index));
                    if (businessIdentities.contains(transaction) || !businessSet.add(transaction)) {
                        throw new IllegalArgumentException("Repeated business transaction");
                    }
                    business.add(transaction);
                    if (transaction instanceof ReservedTransaction reserved)
                        participantTokens = reserved.reservations().iterator();
                } else if (phase == 2) {
                    var token = tokens.get(index);
                    tokenIdentities.add(token);
                    addToken(domains.get(index), token);
                } else {
                    var transaction = business.get(index);
                    businessIdentities.add(transaction);
                    transactions.add(new Participant(transaction));
                }
                index++;
            }
            return true;
        }

        private void captureToken(ResourceReservation token) {
            if (tokenIdentities.contains(token) || !tokenSet.add(token))
                throw new IllegalArgumentException("Repeated reservation token");
            if (!rollbackOnly && !token.supportsSequentialUse())
                throw new IllegalArgumentException("Reservation does not support sequential use");
            var context = token.context();
            if (!contexts.contains(context)) throw new IllegalArgumentException("Reservation domain was not declared");
            tokens.add(token);
            domains.add(context);
        }
    }

    private final class Rejected {
        private final Savepoint mark = new Savepoint(ResourceBatch.this, transactions.size(), savepoints.size(), savepointGeneration);
        private final Append preparation;
        private int sourceIndex;
        private boolean captured, adopted;

        private Rejected(List<? extends ResourceReservation> tokens, List<? extends ReversibleTransaction> business) {
            preparation = new Append(tokens, business, true);
        }
    }

    private final class Trim {
        private final Savepoint mark;
        private int phase, sourceIndex;

        private Trim(Savepoint mark) {
            this.mark = mark;
        }

        private boolean advance(RecipeWorkBudget budget) {
            while (phase == 0 && sourceIndex < sources.size()) {
                if (!budget.take()) {
                    workYielded = true;
                    return false;
                }
                var source = sources.get(sourceIndex);
                if (!source.suffixConfirmed(mark.tokenPrefixes.getInt(source))) {
                    return false;
                }
                sourceIndex++;
            }
            if (phase == 0) {
                phase = 1;
                sourceIndex = 0;
            }
            while (transactions.size() > mark.businessPrefix) {
                if (!budget.take()) {
                    workYielded = true;
                    return false;
                }
                var participant = transactions.removeLast();
                businessIdentities.remove(participant.transaction);
                if (participant.terminal) {
                    confirmedBusiness--;
                }
            }
            while (sourceIndex < sources.size()) {
                var source = sources.get(sourceIndex);
                int first = mark.tokenPrefixes.getInt(source);
                while (source.tokens.size() > first) {
                    if (!budget.take()) {
                        workYielded = true;
                        return false;
                    }
                    var token = source.tokens.removeLast();
                    tokenIdentities.remove(token.token);
                    if (token.terminal) {
                        source.confirmedTokens--;
                    }
                    source.refreshConfirmed();
                }
                if (!budget.take()) {
                    workYielded = true;
                    return false;
                }
                source.appliedTokens = first;
                source.checkedTokens = first;
                source.resolveCursor = -1;
                source.pendingExpose = false;
                source.checking = null;
                source.applying = null;
                source.setState(SourceState.APPLIED);
                sourceIndex++;
            }
            while (savepoints.size() > mark.index) {
                if (!budget.take()) {
                    workYielded = true;
                    return false;
                }
                savepoints.removeLast().active = false;
            }
            return true;
        }
    }

    private final class Stage {
        private final boolean staging;
        private final boolean legacy;
        private @Nullable Participant pending;
        private int phase, index;

        private Stage(boolean staging, boolean legacy) {
            this.staging = staging;
            this.legacy = legacy;
        }

        private boolean advance(BooleanSupplier authorization, RecipeWorkBudget budget) {
            while (phase < 8) {
                if (phase == 0 || phase == 5) {
                    while (index < contextList.size()) {
                        if (!budget.take()) {
                            workYielded = true;
                            return false;
                        }
                        var context = contextList.get(index++);
                        context.lock();
                        try {
                            if (!context.isValid()) {
                                decide(Decision.ROLLBACK);
                                return true;
                            }
                        } finally {
                            context.unlockDeferred();
                        }
                    }
                    phase++;
                    index = 0;
                    continue;
                }
                if (phase == 1 || phase == 6) {
                    if (!budget.take()) {
                        workYielded = true;
                        return false;
                    }
                    if (!authorization.getAsBoolean()) {
                        decide(Decision.ROLLBACK);
                        return true;
                    }
                    phase++;
                    index = phase == 2 ? stagedTransactions : 0;
                    continue;
                }
                if (phase == 2 || phase == 3 || phase == 4) {
                    if (phase == 4 && staging) {
                        phase = 5;
                        index = 0;
                        continue;
                    }
                    while (index < transactions.size()) {
                        if (pending == null) {
                            if (!budget.take()) {
                                workYielded = true;
                                return false;
                            }
                            pending = transactions.get(index); // Freeze exact attempted position once.
                        }
                        // The current guard belongs to this attempted item, not to a saved grant.
                        // A resumed slice never forwards using only phase 1's old authorization.
                        if (!authorization.getAsBoolean()) {
                            decide(Decision.ROLLBACK);
                            return true;
                        }
                        var participant = Objects.requireNonNull(pending);
                        Boolean accepted;
                        if (legacy) {
                            accepted = phase == 2 ? participant.transaction.prepareCommit() : phase == 4 ? participant.transaction.validateCommit() : Boolean.TRUE;
                            if (phase == 3) {
                                participant.transaction.commit();
                            }
                        } else {
                            accepted = phase == 2 ? participant.transaction.prepareCommitWithinBudget(budget)
                                : phase == 4 ? participant.transaction.validateCommitWithinBudget(budget)
                                : participant.transaction.commitWithinBudget(budget) ? Boolean.TRUE : null;
                        }
                        if (accepted == null) {
                            workYielded = true;
                            return false;
                        }
                        if (!accepted) {
                            decide(Decision.ROLLBACK);
                            return true;
                        }
                        pending = null;
                        index++;
                    }
                    phase++;
                    index = phase == 3 ? stagedTransactions : 0;
                    continue;
                }
                if (staging) {
                    stagedTransactions = transactions.size();
                    stagedBoundary = true;
                } else {
                    decide(Decision.COMPLETE);
                }
                phase = 8;
            }
            return true;
        }
    }

    /**
     * One original physical source/affinity; append and rewind retain its exact identity.
     */
    public final class Source {
        private final StorageContext context;
        private final ObjectArrayList<Token> tokens = new ObjectArrayList<>();
        private final String affinity;
        private int appliedTokens, checkedTokens, confirmedTokens, resolveCursor = -1;
        private int resolvingFirst;
        private Decision resolvingDecision;
        private SourceState state = SourceState.RESERVED;
        private boolean workYielded, pendingExpose, fullyConfirmed;
        private @Nullable Token checking;
        private @Nullable Token applying;
        private @Nullable Throwable failure;

        private Source(StorageContext context) {
            this.context = context;
            affinity = "storage:" + context.order();
        }

        public String affinity() {
            return affinity;
        }

        public StorageContext context() {
            return context;
        }

        public SourceState state() {
            synchronized (lock) {
                return state;
            }
        }

        public boolean workYielded() {
            return workYielded;
        }

        private void refreshConfirmed() {
            synchronized (lock) {
                boolean next = confirmedTokens == tokens.size();
                if (next != fullyConfirmed) {
                    fullyConfirmedSources += next ? 1 : -1;
                    fullyConfirmed = next;
                }
            }
        }

        private void setState(SourceState next) {
            synchronized (lock) {
                if (state == SourceState.APPLIED) {
                    appliedSources--;
                }
                if (state == SourceState.COMPLETED || state == SourceState.ROLLED_BACK) {
                    terminalSources--;
                }
                state = next;
                if (state == SourceState.APPLIED) {
                    appliedSources++;
                }
                if (state == SourceState.COMPLETED || state == SourceState.ROLLED_BACK) {
                    terminalSources++;
                }
            }
        }

        public boolean apply() {
            return applyWithinBudget(unlimited(), true);
        }

        private <T> Step<T> sourceStep(RecipeWorkBudget budget, Function<RecipeWorkBudget, T> action) {
            var child = budget.allocate(1);
            try {
                T result = fence.sourceAccess(context, () -> action.apply(child));
                return new Step<>(result, child.remaining() == 0);
            } finally {
                // The physical scope and callback have actually exited before reclaiming unused work.
                budget.reclaim(child);
            }
        }

        private boolean forwardAllowed() {
            synchronized (lock) {
                return decision == Decision.OPEN && rewind == null;
            }
        }

        /**
         * True includes yield; APPLIED plus actual method exit proves all effects finished.
         */
        public boolean applyWithinBudget(RecipeWorkBudget budget) {
            return applyWithinBudget(budget, false);
        }

        private boolean applyWithinBudget(RecipeWorkBudget budget, boolean legacy) {
            StorageFence.requireOutsideAccess();
            StorageFence.requireOutsideSourceWork();
            StorageContext.requireNoLocks();
            Objects.requireNonNull(budget);
            workYielded = false;
            synchronized (lock) {
                if (closed || ownerAction || append != null || rejected != null || captureMark != null || trim != null || stage != null || release != null || state != SourceState.RESERVED) {
                    throw new IllegalStateException("Source cannot apply at this boundary");
                }
                if (decision != Decision.OPEN || rewind != null) {
                    return false;
                }
                setState(SourceState.APPLYING);
                runningSources++;
            }
            SourceState outcome = SourceState.APPLY_FAILED;
            boolean entered = false;
            try {
                fence.beginSourceWork(context);
                entered = true;
                while (checkedTokens < tokens.size()) {
                    if (!legacy && !forwardAllowed()) {
                        outcome = SourceState.REJECTED;
                        return false;
                    }
                    if (checking == null) {
                        if (!budget.take()) {
                            workYielded = true;
                            outcome = SourceState.RESERVED;
                            return true;
                        }
                        checking = tokens.get(checkedTokens);
                    }
                    var checked = Objects.requireNonNull(checking);
                    Boolean accepted;
                    if (legacy) {
                        accepted = fence.sourceAccess(context, checked.token::canApply);
                    } else {
                        if (budget.remaining() == 0) {
                            workYielded = true;
                            outcome = SourceState.RESERVED;
                            return true;
                        }
                        var step = sourceStep(budget, checked.token::canApplyWithinBudget);
                        accepted = step.result;
                        if (accepted == null) {
                            if (step.spent && budget.remaining() != 0) {
                                continue;
                            }
                            workYielded = true;
                            outcome = SourceState.RESERVED;
                            return true;
                        }
                    }
                    if (!accepted) {
                        outcome = SourceState.REJECTED;
                        return false;
                    }
                    checking = null;
                    checkedTokens++;
                }
                while (appliedTokens < tokens.size()) {
                    if (!legacy && !forwardAllowed()) {
                        outcome = SourceState.REJECTED;
                        return false;
                    }
                    if (applying == null) {
                        if (!budget.take()) {
                            workYielded = true;
                            outcome = SourceState.RESERVED;
                            return true;
                        }
                        applying = tokens.get(appliedTokens);
                    }
                    var applied = Objects.requireNonNull(applying);
                    if (legacy) {
                        if (pendingExpose) {
                            fence.access(context, applied.token::exposeApplied);
                        } else {
                            fence.access(context, applied.token::apply);
                        }
                    } else {
                        if (budget.remaining() == 0) {
                            workYielded = true;
                            outcome = SourceState.RESERVED;
                            return true;
                        }
                        var step = sourceStep(budget, pendingExpose ? applied.token::exposeAppliedWithinBudget : applied.token::applyWithinBudget);
                        if (!Boolean.TRUE.equals(step.result)) {
                            if (step.spent && budget.remaining() != 0) {
                                continue;
                            }
                            workYielded = true;
                            outcome = SourceState.RESERVED;
                            return true;
                        }
                    }
                    if (pendingExpose || !sequential) {
                        applying = null;
                        pendingExpose = false;
                        appliedTokens++;
                    } else {
                        pendingExpose = true;
                    }
                }
                outcome = SourceState.APPLIED;
                return true;
            } finally {
                try {
                    if (entered) {
                        fence.endSourceWork(context);
                    }
                } finally {
                    synchronized (lock) {
                        setState(outcome);
                        runningSources--;
                    }
                }
            }
        }

        public boolean resolve() {
            return resolveWithinBudget(unlimited(), true);
        }

        /**
         * False and workYielded=false means UNKNOWN/dependency; never replay an uncertain callback.
         */
        public boolean resolveWithinBudget(RecipeWorkBudget budget) {
            return resolveWithinBudget(budget, false);
        }

        private boolean resolveWithinBudget(RecipeWorkBudget budget, boolean legacy) {
            StorageFence.requireOutsideAccess();
            StorageFence.requireOutsideSourceWork();
            StorageContext.requireNoLocks();
            Objects.requireNonNull(budget);
            workYielded = false;
            Decision settling;
            int first;
            synchronized (lock) {
                if (closed || ownerAction || append != null || captureMark != null || trim != null && trim.phase != 0 || release != null
                    || decision == Decision.OPEN && rewind == null || state == SourceState.APPLYING || state == SourceState.RESOLVING
                    || business == Business.ACQUIRED && rewind == null) {
                    throw new IllegalStateException("Source cannot resolve at this boundary");
                }
                if ((rewind != null || sequential && decision == Decision.ROLLBACK) && business != Business.ROLLED_BACK) {
                    return false;
                }
                settling = rewind == null ? decision : Decision.ROLLBACK;
                first = rewind == null ? 0 : rewind.tokenPrefixes.getInt(this);
                if (suffixConfirmed(first)) {
                    if (rewind == null) {
                        setState(settling == Decision.COMPLETE ? SourceState.COMPLETED : SourceState.ROLLED_BACK);
                    }
                    return true;
                }
                if (resolveCursor < 0 || resolvingDecision != settling || resolvingFirst != first) {
                    resolveCursor = settling == Decision.COMPLETE ? first : tokens.size() - 1;
                    resolvingDecision = settling;
                    resolvingFirst = first;
                }
                setState(SourceState.RESOLVING);
                runningSources++;
            }
            boolean entered = false;
            try {
                fence.beginSourceWork(context);
                entered = true;
                while (resolveCursor >= first && resolveCursor < tokens.size()) {
                    var token = tokens.get(resolveCursor);
                    if (!token.selected) {
                        if (!budget.take()) {
                            workYielded = true;
                            return false;
                        }
                        token.selected = true;
                    }
                    if (token.terminal) {
                        token.selected = false;
                        resolveCursor += settling == Decision.COMPLETE ? 1 : -1;
                        continue;
                    }
                    if (token.uncertain) {
                        boolean queryAfterFailure = token.failureQuery;
                        try {
                            ResourceReservation.Resolution resolution;
                            if (legacy) {
                                resolution = fence.sourceAccess(context, token.token::resolution);
                            } else {
                                if (budget.remaining() == 0) {
                                    workYielded = true;
                                    return false;
                                }
                                var step = sourceStep(budget, token.token::resolutionWithinBudget);
                                resolution = step.result;
                                if (resolution == null) {
                                    if (step.spent && budget.remaining() != 0) {
                                        continue;
                                    }
                                    workYielded = true;
                                    return false;
                                }
                            }
                            token.failureQuery = false;
                            var expected = settling == Decision.COMPLETE ? ResourceReservation.Resolution.COMPLETED : ResourceReservation.Resolution.ROLLED_BACK;
                            if (resolution == expected) {
                                token.terminal = true;
                                token.uncertain = false;
                                confirmedTokens++;
                                refreshConfirmed();
                            } else if (resolution == ResourceReservation.Resolution.APPLIED || resolution == ResourceReservation.Resolution.REVERSIBLE
                                || settling == Decision.ROLLBACK && resolution == ResourceReservation.Resolution.RESERVED) {
                                if (!queryAfterFailure) {
                                    token.uncertain = false;
                                    continue;
                                }
                            } else if (resolution != ResourceReservation.Resolution.UNKNOWN) {
                                throw new IllegalStateException("Reservation recovery contradicts batch decision: " + resolution);
                            }
                        } catch (RuntimeException | Error query) {
                            if (query instanceof VirtualMachineError fatal) {
                                throw fatal;
                            }
                            failure = accumulate(failure, query);
                        }
                        if (!token.terminal && sequential && settling == Decision.ROLLBACK) {
                            throwFailure();
                            return false;
                        }
                        token.selected = false;
                        resolveCursor += settling == Decision.COMPLETE ? 1 : -1;
                        continue;
                    }
                    try {
                        if (legacy) {
                            fence.access(context, settling == Decision.COMPLETE ? token.token::complete : token.token::rollback);
                        } else {
                            if (budget.remaining() == 0) {
                                workYielded = true;
                                return false;
                            }
                            var step = sourceStep(budget, settling == Decision.COMPLETE ? token.token::completeWithinBudget : token.token::rollbackWithinBudget);
                            if (!Boolean.TRUE.equals(step.result)) {
                                if (step.spent && budget.remaining() != 0) {
                                    continue;
                                }
                                workYielded = true;
                                return false;
                            }
                        }
                        token.terminal = true;
                        token.uncertain = false;
                        confirmedTokens++;
                        refreshConfirmed();
                    } catch (RuntimeException | Error cleanup) {
                        token.uncertain = true;
                        token.failureQuery = true;
                        if (cleanup instanceof VirtualMachineError fatal) {
                            throw fatal;
                        }
                        failure = accumulate(failure, cleanup);
                        continue;
                    }
                    token.selected = false;
                    resolveCursor += settling == Decision.COMPLETE ? 1 : -1;
                }
                boolean done = suffixConfirmed(first);
                resolveCursor = -1;
                throwFailure();
                return done;
            } finally {
                try {
                    if (entered) {
                        fence.endSourceWork(context);
                    }
                } finally {
                    synchronized (lock) {
                        boolean confirmed = suffixConfirmed(first);
                        setState(confirmed ? settling == Decision.COMPLETE ? SourceState.COMPLETED : SourceState.ROLLED_BACK : SourceState.CONFIRMATION_PENDING);
                        runningSources--;
                    }
                }
            }
        }

        public boolean fullySettled() {
            synchronized (lock) {
                return confirmedTokens == tokens.size();
            }
        }

        private boolean suffixConfirmed(int first) {
            return confirmedTokens == tokens.size() - first;
        }

        private void throwFailure() {
            var caught = failure;
            failure = null;
            rethrow(caught);
        }
    }
}
