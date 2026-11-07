package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
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
            var result = process.withProbeContext(() -> {
                if (stage == RecipeStage.TICK) {
                    var state = process.state();
                    double speed = process.attributes().value(StandardMachineAttributes.PROCESS_SPEED, 1);
                    var tick = new RecipeProcess.Tick(state.progress(), state.progress() + Math.max(0, speed),
                        state.successfulTicks(), Math.incrementExact(state.successfulTicks()));
                    return process.withTickContext(tick, () -> probeAcquisition(process, stage));
                }
                return probeAcquisition(process, stage);
            });
            requireProbeBoundary(process);
            return result;
        } catch (RuntimeException | Error failure) {
            process.invalidatePersistenceBoundary();
            throw failure;
        }
    }

    private static ProcessResult probeAcquisition(RecipeProcess process, RecipeStage stage) {
        var acquisition = prepare(process, stage, RequirementTransaction.SUCCESS);
        try {
            acquisition.acquire();
        } catch (RuntimeException | Error failure) {
            rollbackAfterFailure(acquisition, failure);
            throw failure;
        }
        acquisition.rollbackSynchronously();
        return acquisition.result();
    }

    /**
     * The final participant applies process lifecycle/progress in the same reversible boundary.
     * It is supplied before acquisition and is rolled back even when acquisition itself fails.
     */
    public static ProcessResult execute(RecipeProcess process, RecipeStage stage, RequirementTransaction transition) {
        var acquisition = prepare(process, stage, transition);
        try { acquisition.acquire(); }
        catch (RuntimeException | Error failure) {
            rollbackAfterFailure(acquisition, failure);
            throw failure;
        }
        return acquisition.settleSynchronously();
    }

    /** Creates a framework-owned handle before any real requirement acquisition can throw. */
    public static Acquisition prepare(RecipeProcess process, RecipeStage stage, RequirementTransaction transition) {
        Objects.requireNonNull(process);
        Objects.requireNonNull(stage);
        Objects.requireNonNull(transition);
        if (transition.result() != ProcessResult.SUCCESS || !transition.reservations().isEmpty()) {
            throw new IllegalArgumentException("A process transition must be successful and have no storage reservations");
        }
        return new Acquisition(process, stage, transition);
    }

    private static void requireProbeBoundary(RecipeProcess process) {
        Objects.requireNonNull(process).requirePersistenceBoundary();
        if (process.host() != null || process.state().lifecycle() != RecipeProcess.Lifecycle.NEW) {
            throw new IllegalArgumentException("Stage probe requires an unattached NEW process");
        }
    }

    private static void rollbackAfterFailure(Acquisition acquisition, Throwable failure) {
        // Exceptional cleanup only. Preserve the original fault and expose every cleanup fault.
        try {
            acquisition.rollbackSynchronously(failure);
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * One retained real stage acquisition, including partial acquisitions after a thrown callback.
     * This is framework plumbing; RecipeProcess owns the public stage lease and version boundary.
     * Deferred lists retain original participant identities. Synchronous settlement observes private
     * wrappers, and resolved() reports only recorded terminal proofs, never inferred exception state.
     */
    public static final class Acquisition {
        private final Thread creator = Thread.currentThread();
        private final RecipeProcess process;
        private final RecipeStage stage;
        private final RequirementTransaction transition;
        private List<RequirementTransaction> transactions = List.of();
        private List<ResourceReservation> reservations = List.of();
        private final ObjectArrayList<BusinessObservation> business = new ObjectArrayList<>();
        private final ObjectArrayList<SourceObservation> sources = new ObjectArrayList<>();
        private final ObjectArrayList<ResourceReservation> synchronousSources = new ObjectArrayList<>();
        private ProcessResult result = ProcessResult.SUCCESS;
        @Nullable private Throwable failure;
        private AcquisitionState state = AcquisitionState.NEW;
        private Settlement decision = Settlement.NONE;
        private boolean inventoryComplete = true;
        private boolean acquisitionFailed;
        private boolean synchronousStarted;
        private boolean executing;

        private Acquisition(RecipeProcess process, RecipeStage stage, RequirementTransaction transition) {
            this.process = process;
            this.stage = stage;
            this.transition = transition;
        }

        public void acquire() {
            requireOwner();
            process.requireAcquisitionBoundary();
            if (state != AcquisitionState.NEW || executing) { throw new IllegalStateException("Stage acquisition already entered"); }
            state = AcquisitionState.ACQUIRING;
            var acquired = new ObjectArrayList<RequirementTransaction>();
            var tokens = new ObjectArrayList<ResourceReservation>();
            try {
                for (var requirement : process.orderedRequirements()) {
                    var transaction = Objects.requireNonNull(requirement.acquire(process, stage), "requirement transaction");
                    if (transaction != RequirementTransaction.SUCCESS) { acquired.add(transaction); }
                    try {
                        for (var token : Objects.requireNonNull(transaction.reservations(), "transaction reservations")) {
                            tokens.add(Objects.requireNonNull(token));
                        }
                    } catch (RuntimeException | Error listingFailure) {
                        inventoryComplete = false;
                        throw listingFailure;
                    }
                    result = Objects.requireNonNull(transaction.result(), "transaction result");
                    if (result != ProcessResult.SUCCESS) { break; }
                }
            } catch (RuntimeException | Error acquisitionFailure) {
                acquisitionFailed = true;
                failure = acquisitionFailure;
                throw acquisitionFailure;
            } finally {
                acquired.add(transition);
                transactions = ObjectLists.unmodifiable(acquired);
                reservations = ObjectLists.unmodifiable(tokens);
                for (var transaction : acquired) { business.add(new BusinessObservation(transaction)); }
                var identities = new Reference2ObjectOpenHashMap<ResourceReservation, SourceObservation>();
                for (var token : tokens) {
                    var observation = identities.get(token);
                    if (observation == null) {
                        observation = new SourceObservation(token);
                        identities.put(token, observation);
                        sources.add(observation);
                    }
                    // Repeated originals retain one wrapper identity, so validation cannot hide aliases.
                    synchronousSources.add(observation);
                }
                state = AcquisitionState.SEALED;
            }
        }

        public ProcessResult result() { requireSealed(); return result; }
        @Nullable public Throwable failure() { requireSealed(); return failure; }
        public List<RequirementTransaction> transactions() { requireSealed(); return transactions; }
        public List<ResourceReservation> reservations() { requireSealed(); return reservations; }
        public boolean inventoryComplete() { return state == AcquisitionState.SEALED && inventoryComplete; }

        public ProcessResult settleSynchronously() {
            requireReady();
            if (synchronousStarted || decision != Settlement.NONE) { throw new IllegalStateException("Cannot replay stage settlement"); }
            if (acquisitionFailed || !inventoryComplete) { throw new IllegalStateException("Cannot commit a failed acquisition"); }
            if (result != ProcessResult.SUCCESS) { rollbackSynchronously(); return result; }
            executing = true;
            try {
                boolean accepted = ResourceTransactions.commit(synchronousSources, this::prepareBusiness, this::validateBusiness, this::commitBusiness,
                    () -> settleBusiness(Settlement.COMPLETE, null), () -> settleBusiness(Settlement.ROLLBACK, null));
                return accepted ? result : RecipeText.StorageChanged.blocked();
            } catch (RuntimeException | Error settlementFailure) {
                remember(settlementFailure);
                throw settlementFailure;
            } finally { executing = false; }
        }

        public void rollbackSynchronously() { rollbackSynchronously(null); }

        private void rollbackSynchronously(@Nullable Throwable primary) {
            requireReady();
            if (decision == Settlement.COMPLETE) { throw new IllegalStateException("Completed stage cannot roll back"); }
            executing = true;
            try { ResourceTransactions.rollback(synchronousSources, () -> settleBusiness(Settlement.ROLLBACK, primary)); }
            catch (RuntimeException | Error cleanup) { remember(cleanup); throw cleanup; }
            finally { executing = false; }
        }

        /** Refreshes exact proofs and retries only safe terminal cleanup; UNKNOWN returns pending. */
        public boolean resolveSynchronously() {
            requireReady();
            if (decision == Settlement.NONE) { throw new IllegalStateException("Stage settlement has not been decided"); }
            executing = true;
            var contexts = new ObjectArrayList<StorageContext>();
            Throwable primary = null;
            boolean locked = false;
            try {
                contexts.addAll(orderedContexts());
                StorageContext.lockAll(contexts);
                locked = true;
                boolean retryable = true;
                Throwable queryFailure = null;
                for (var observation : business) {
                    try { retryable &= observation.confirmOrRetry(); }
                    catch (RuntimeException | Error query) { queryFailure = accumulate(queryFailure, query); }
                }
                for (var observation : sources) {
                    try { retryable &= observation.confirmOrRetry(); }
                    catch (RuntimeException | Error query) { queryFailure = accumulate(queryFailure, query); }
                }
                rethrow(queryFailure);
                if (!retryable) { return false; }
                Throwable cleanup = null;
                try { settleBusiness(decision, null); }
                catch (RuntimeException | Error businessFailure) { cleanup = businessFailure; }
                for (int offset = 0; offset < sources.size(); offset++) {
                    int index = decision == Settlement.COMPLETE ? offset : sources.size() - 1 - offset;
                    try { sources.get(index).finish(decision); }
                    catch (RuntimeException | Error sourceFailure) { cleanup = accumulate(cleanup, sourceFailure); }
                }
                rethrow(cleanup);
                return inventoryComplete && allTerminal();
            } catch (RuntimeException | Error recoveryFailure) {
                primary = recoveryFailure;
                remember(recoveryFailure);
                throw recoveryFailure;
            } finally {
                if (locked) {
                    for (int index = contexts.size() - 1; index >= 0; index--) { contexts.get(index).unlockDeferred(); }
                }
                executing = false;
                if (locked) { publish(contexts, primary); }
            }
        }

        /** Queries never guess: resolveSynchronously refreshes proofs for a previously failed callback. */
        public boolean resolved() {
            return state == AcquisitionState.SEALED && !executing && inventoryComplete
                && decision != Settlement.NONE && allTerminal();
        }

        private boolean prepareBusiness() {
            synchronousStarted = true;
            for (var observation : business) { if (!observation.prepareCommit()) { return false; } }
            return true;
        }

        private boolean validateBusiness() {
            for (var observation : business) { if (!observation.validateCommit()) { return false; } }
            return true;
        }

        private void commitBusiness() {
            synchronousStarted = true;
            for (var observation : business) { observation.commit(); }
        }

        private void settleBusiness(Settlement expected, @Nullable Throwable primary) {
            if (decision != Settlement.NONE && decision != expected) { throw new IllegalStateException("Cannot change stage terminal decision"); }
            synchronousStarted = true;
            decision = expected;
            Throwable cleanup = primary;
            for (int offset = 0; offset < business.size(); offset++) {
                int index = expected == Settlement.COMPLETE ? offset : business.size() - 1 - offset;
                try { business.get(index).finish(expected); }
                catch (RuntimeException | Error businessFailure) { cleanup = accumulate(cleanup, businessFailure); }
            }
            if (primary == null) { rethrow(cleanup); }
        }

        private ObjectArrayList<StorageContext> orderedContexts() {
            var unique = new ReferenceOpenHashSet<StorageContext>();
            var contexts = new ObjectArrayList<StorageContext>();
            for (var observation : sources) {
                var context = observation.context();
                if (unique.add(context)) { contexts.add(context); }
            }
            contexts.sort(Comparator.comparingLong(StorageContext::order));
            return contexts;
        }

        private boolean allTerminal() {
            for (var observation : business) { if (!observation.terminal) { return false; } }
            for (var observation : sources) { if (!observation.terminal) { return false; } }
            return true;
        }

        private void requireOwner() {
            if (Thread.currentThread() != creator) { throw new IllegalStateException("Stage acquisition requires its creating thread"); }
        }

        private void requireSealed() {
            if (state != AcquisitionState.SEALED) { throw new IllegalStateException("Stage acquisition has not sealed its participants"); }
        }

        private void requireReady() {
            requireOwner();
            requireSealed();
            if (executing) { throw new IllegalStateException("Stage settlement callback is executing"); }
        }

        private void remember(Throwable observed) {
            if (failure == null) { failure = observed; }
        }

        private final class BusinessObservation {
            private final RequirementTransaction transaction;
            private boolean preparationAttempted;
            private boolean preparationAccepted;
            private boolean committed;
            private boolean validationAttempted;
            private boolean uncertain;
            private boolean terminal;

            private BusinessObservation(RequirementTransaction transaction) { this.transaction = transaction; }

            private boolean prepareCommit() {
                if (preparationAttempted || committed || terminal) { throw new IllegalStateException("Cannot replay business preparation"); }
                preparationAttempted = true;
                preparationAccepted = transaction.prepareCommit();
                return preparationAccepted;
            }

            private void commit() {
                if (committed || terminal || !preparationAccepted) { throw new IllegalStateException("Cannot replay or commit unprepared business work"); }
                committed = true;
                transaction.commit();
            }

            private boolean validateCommit() {
                if (!committed || validationAttempted || terminal) { throw new IllegalStateException("Cannot validate uncommitted or resolving business work"); }
                validationAttempted = true;
                return transaction.validateCommit();
            }

            private boolean confirmOrRetry() {
                if (terminal || !uncertain) { return true; }
                var actual = Objects.requireNonNull(transaction.resolution());
                var expected = decision == Settlement.COMPLETE ? ReversibleTransaction.Resolution.COMPLETED : ReversibleTransaction.Resolution.ROLLED_BACK;
                if (actual == expected) { terminal = true; uncertain = false; return true; }
                if (actual == ReversibleTransaction.Resolution.UNKNOWN) { return false; }
                if (actual == ReversibleTransaction.Resolution.REVERSIBLE) { return true; }
                throw new IllegalStateException("Business terminal proof contradicts stage settlement: " + actual);
            }

            private void finish(Settlement expected) {
                if (terminal) { return; }
                boolean retryable = confirmOrRetry();
                if (terminal || !retryable) { return; }
                try {
                    if (expected == Settlement.COMPLETE) { transaction.complete(); }
                    else { transaction.rollback(); }
                    terminal = true;
                    uncertain = false;
                } catch (RuntimeException | Error cleanup) {
                    uncertain = true;
                    try { confirmOrRetry(); }
                    catch (RuntimeException | Error query) { if (cleanup != query) { cleanup.addSuppressed(query); } }
                    throw cleanup;
                }
            }
        }

        private final class SourceObservation implements ResourceReservation {
            private final ResourceReservation token;
            private boolean uncertain;
            private boolean terminal;

            private SourceObservation(ResourceReservation token) { this.token = token; }
            @Override public StorageContext context() { return token.context(); }
            @Override public boolean canApply() { synchronousStarted = true; return token.canApply(); }
            @Override public void apply() { token.apply(); }
            @Override public void complete() { finish(Settlement.COMPLETE); }
            @Override public void rollback() { finish(Settlement.ROLLBACK); }

            private boolean confirmOrRetry() {
                if (terminal || !uncertain) { return true; }
                var actual = Objects.requireNonNull(token.resolution());
                var expected = decision == Settlement.COMPLETE ? Resolution.COMPLETED : Resolution.ROLLED_BACK;
                if (actual == expected) { terminal = true; uncertain = false; return true; }
                if (actual == Resolution.UNKNOWN) { return false; }
                if (actual == Resolution.APPLIED || decision == Settlement.ROLLBACK && actual == Resolution.RESERVED) { return true; }
                throw new IllegalStateException("Source terminal proof contradicts stage settlement: " + actual);
            }

            private void finish(Settlement expected) {
                if (decision != expected) { throw new IllegalStateException("Source cannot change stage terminal decision"); }
                if (terminal) { return; }
                boolean retryable = confirmOrRetry();
                if (terminal || !retryable) { return; }
                try {
                    if (expected == Settlement.COMPLETE) { token.complete(); }
                    else { token.rollback(); }
                    terminal = true;
                    uncertain = false;
                } catch (RuntimeException | Error cleanup) {
                    uncertain = true;
                    try { confirmOrRetry(); }
                    catch (RuntimeException | Error query) { if (cleanup != query) { cleanup.addSuppressed(query); } }
                    throw cleanup;
                }
            }
        }
    }

    private enum AcquisitionState { NEW, ACQUIRING, SEALED }
    private enum Settlement { NONE, COMPLETE, ROLLBACK }

    private static Throwable accumulate(@Nullable Throwable primary, Throwable next) {
        if (primary == null) { return next; }
        if (primary != next) { primary.addSuppressed(next); }
        return primary;
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private static void publish(List<StorageContext> contexts, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (var context : contexts) {
            try { context.publishChanges(); }
            catch (RuntimeException | Error notification) { failure = accumulate(failure, notification); }
        }
        if (primary == null) { rethrow(failure); }
    }
}
