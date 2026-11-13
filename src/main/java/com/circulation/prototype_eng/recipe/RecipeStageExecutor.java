package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementAlternatives;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementContinuation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * One compensation/completion boundary across every requirement type and resource/FE reservation.
 */
public final class RecipeStageExecutor {
    private RecipeStageExecutor() {
    }

    public static ProcessResult execute(RecipeProcess process, RecipeStage stage) {
        return execute(process, stage, RequirementTransaction.SUCCESS);
    }

    /**
     * Acquires the real START requirements of an unattached NEW candidate, then compensates every
     * acquisition. A continued START stages its nested effects under one retained fence so later
     * child phases see them, then rolls the entire probe back without completion. Ordinary flat
     * requirements retain the acquisition-only probe. All callbacks must preserve complete undo.
     */
    public static ProcessResult probeStart(RecipeProcess process) {
        return probeStage(process, RecipeStage.START);
    }

    /**
     * Installs the exact cleanup owner before any acquisition. The caller retains this handle,
     * including after advance throws, until finished. Pure calculations and physical source actions
     * are exposed to the caller's existing worker/source lanes and must physically exit before advance.
     */
    public static Probe beginProbe(RecipeProcess process, RecipeStage stage) {
        return new Probe(process, stage);
    }

    /**
     * Probes one isolated stage of an unattached NEW candidate. Earlier stages and component callbacks
     * are not executed; the caller must establish any state that this stage requires. This is not a
     * projection of a complete lifecycle. TICK uses the candidate's first frozen tick context for both
     * acquisition and cleanup. Every acquired transaction is rolled back without completion;
     * nested continuations may stage reversible effects to preserve their ordered input semantics.
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
        if (acquisition.sequential()) {
            return acquisition.settleSequentially(true);
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
        try {
            acquisition.acquire();
        } catch (RuntimeException | Error failure) {
            rollbackAfterFailure(acquisition, failure);
            throw failure;
        }
        return acquisition.settleSynchronously();
    }

    /**
     * Creates a framework-owned handle before any real requirement acquisition can throw.
     */
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

    private static Throwable accumulate(@Nullable Throwable primary, Throwable next) {
        if (primary == null) {
            return next;
        }
        if (primary != next) {
            primary.addSuppressed(next);
        }
        return primary;
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void publish(List<StorageContext> contexts, @Nullable Throwable primary) {
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

    private enum AcquisitionState {NEW, ACQUIRING, SEALED}

    private enum Settlement {NONE, COMPLETE, ROLLBACK}

    /**
     * Incremental acquisition and settlement on the existing transaction/continuation/savepoint
     * implementation. Flat probes claim only for exact rollback; continued probes stage each nested
     * segment under the same logical fence and original source identities.
     */
    public static final class Probe {
        private final Thread owner = Thread.currentThread();
        private final RecipeProcess process;
        private final Acquisition acquisition;
        private final RecipeStage stage;
        private final ReferenceOpenHashSet<RecipePureComputation> prepared = new ReferenceOpenHashSet<>();
        private final ObjectArrayList<ResourceReservation> foreignTokens = new ObjectArrayList<>();
        private final ReferenceOpenHashSet<ResourceReservation> foreignTokenIdentities = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<ResourceBatch.Source> observedSourceIdentities = new ReferenceOpenHashSet<>();
        @Nullable
        private RecipeProcess.Tick tick;
        @Nullable
        private MachineAttributes.ValueCapture speedValue;
        private boolean acquired;
        private boolean cancelling;
        private boolean finished;
        private boolean executing;
        private boolean stageReady;
        private boolean continuationPrepared;
        private boolean acquireCancelled;
        private boolean acquireFailed;
        private boolean cleanupAcquire;
        private boolean cleaningForeign;
        @Nullable
        private List<ResourceReservation> appendInput;
        @Nullable
        private ObjectArrayList<ResourceReservation> appendOwnedTokens;
        private int appendCursor;
        private boolean appendViolation;
        private boolean candidateRewind;
        private int rewindPhase;
        private boolean sourceActionsRequired;
        private boolean sourceResolution;
        private int sourceCursor;
        private boolean sawSourceAction;
        private int cleanupPhase;
        private ReferenceOpenHashSet<ResourceBatch.Source> resolvedSources = new ReferenceOpenHashSet<>();
        private ProcessResult result = ProcessResult.SUCCESS;
        @Nullable
        private ResourceBatch batch;
        @Nullable
        private ResourceBatch.Acquire batchAcquire;
        @Nullable
        private RecipeProcess.ContextCapture contextCapture;

        @Nullable
        private Throwable failure;

        private Probe(RecipeProcess process, RecipeStage stage) {
            requireProbeBoundary(process);
            this.process = process;
            this.stage = Objects.requireNonNull(stage);
            acquisition = prepare(process, stage, RequirementTransaction.SUCCESS);
            process.claimProbe(this);
        }

        public RecipeProcess process() {
            return process;
        }

        /**
         * Only this returned pure object may leave the owner; the probe/process never do.
         */
        public @Nullable RecipePureComputation computation() {
            requireOwner();
            if (!acquired || cancelling || acquisition.flatCleanupPending() || !acquisition.preparationPending())
                return null;
            var computation = acquisition.computation();
            return computation == null || prepared.contains(computation) ? null : computation;
        }

        /**
         * Called only after the assigned worker slice physically exits with a complete result.
         */
        public void finishComputation(RecipePureComputation expected) {
            requireOwner();
            if (acquisition.computation() != Objects.requireNonNull(expected))
                throw new IllegalArgumentException("Different probe computation");
            prepared.add(expected);
        }

        public List<ResourceBatch.Source> sources() {
            requireOwner();
            if (acquisition.flatCleanupPending())
                return Objects.requireNonNull(acquisition.cleanupCandidate()).sources();
            return batch == null || !sourceActionsRequired ? ObjectLists.emptyList() : batch.sources();
        }

        public boolean resolvingSources() {
            requireOwner();
            return acquisition.flatCleanupPending() ? !Objects.requireNonNull(acquisition.cleanupCandidate()).sources().isEmpty() : sourceActionsRequired && sourceResolution;
        }

        /**
         * Consumes the exact source job's returned proof only after its physical exit.
         */
        public void finishSourceResolution(ResourceBatch.Source expected, boolean resolved) {
            requireOwner();
            if (acquisition.flatCleanupPending()) {
                Objects.requireNonNull(acquisition.cleanupCandidate()).finishSourceResolution(expected, resolved);
                return;
            }
            if (activeSourceBatch() == null || !sourceActionsRequired || !sourceResolution || !observedSourceIdentities.contains(expected)) {
                throw new IllegalArgumentException("Different source rollback obligation");
            }
            if (resolved) resolvedSources.add(expected);
        }

        public boolean advance(RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
            requireOwner();
            Objects.requireNonNull(budget);
            Objects.requireNonNull(forwardAllowed);
            if (finished) return true;
            executing = true;
            try {
                return process.withProbeWork(this, budget, tick, () -> advanceBody(budget, forwardAllowed));
            } catch (RuntimeException | Error observed) {
                cancelling = true;
                if (batchAcquire != null) acquireFailed = true;
                process.invalidatePersistenceBoundary();
                if (failure == null) failure = observed;
                else if (failure != observed) failure.addSuppressed(observed);
                throw observed;
            } finally {
                executing = false;
                if (finished) process.finishProbe(this);
            }
        }

        private boolean advanceBody(RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
            while (true) {
                if (!cancelling && !forwardAllowed.getAsBoolean()) {
                    cancelling = true;
                    result = RecipeText.Cancelled.blocked();
                }
                if (cancelling) return cleanup(budget);
                if (!acquisition.advanceCleanupCandidates(budget, false)) return false;
                if (!acquired) {
                    if (stage == RecipeStage.TICK && tick == null) {
                        if (speedValue == null)
                            speedValue = process.attributes().beginValueCapture(StandardMachineAttributes.PROCESS_SPEED, 1);
                        if (!speedValue.advance(budget)) {
                            if (!speedValue.current()) speedValue = null;
                            return false;
                        }
                        var state = process.state();
                        double speed = speedValue.result();
                        tick = new RecipeProcess.Tick(state.progress(), state.progress() + Math.max(0, speed),
                            state.successfulTicks(), Math.incrementExact(state.successfulTicks()));
                        speedValue = null;
                    }
                    acquired = true;
                    if (tick == null) acquisition.acquireWithinBudget(budget);
                    else process.withTickContext(tick, () -> {
                        acquisition.acquireWithinBudget(budget);
                        return null;
                    });
                }
                if (acquisition.preparationPending()) {
                    var computation = acquisition.computation();
                    if (computation != null && !prepared.contains(computation)) return false;
                    if (budget.remaining() == 0) return false;
                    if (batch == null) acquisition.resumePreparedWithinBudget(budget);
                    else if (!batch.accessWithinBudget(() -> acquisition.resumePreparedWithinBudget(budget)))
                        return false;
                    if (acquisition.preparationPending()) return false;
                }
                if (batch != null && batch.acquiringNext()) {
                    if (!appendStep(budget, false)) return false;
                }
                if (!acquisition.sequential() && batch == null) {
                    result = acquisition.result();
                    cancelling = true;
                    return cleanup(budget);
                }
                if (batch == null) {
                    if (batchAcquire == null) {
                        if (contextCapture == null) contextCapture = process.beginStorageContextCapture();
                        if (!contextCapture.advance(budget)) {
                            if (!contextCapture.current()) {
                                result = RecipeText.StorageChanged.blocked();
                                cancelling = true;
                            }
                            return false;
                        }
                        batchAcquire = ResourceBatch.beginAcquireSequential(contextCapture.result(), acquisition.reservations, acquisition.transactions);
                    }
                    if (!batchAcquire.advance(budget)) return false;
                    batch = batchAcquire.result();
                    batchAcquire = null;
                    if (batch == null) {
                        result = RecipeText.StorageChanged.blocked();
                        cancelling = true;
                        return cleanup(budget);
                    }
                    acquisition.attachFlatParent(batch);
                }
                if (acquisition.hasRejectedAlternative()) {
                    if (!acquisition.rejectCleanupCandidatesWithinBudget(budget) || !acquisition.advanceCleanupCandidates(budget, false))
                        return false;
                    if (!candidateRewind) {
                        candidateRewind = true;
                        resolvedSources = new ReferenceOpenHashSet<>();
                        rewindPhase = 1;
                        beginSourceObservation(true);
                    }
                    if (rewindPhase == 1) {
                        if (!batch.rollbackToWithinBudget(acquisition.rejectedSavepoint(), budget)) return false;
                        rewindPhase = 2;
                    }
                    if (rewindPhase == 2) {
                        if (!observeSources(budget, true)) return false;
                        rewindPhase = 3;
                    }
                    if (rewindPhase == 3) {

                        if (!batch.finishRollbackToWithinBudget(acquisition.rejectedSavepoint(), budget)) {
                            if (!batch.workYielded()) {
                                rewindPhase = 1;
                                beginSourceObservation(true);
                            }
                            return false;
                        }
                        acquisition.rollbackTrimPhase = 1;
                        rewindPhase = 4;
                    }
                    if (!acquisition.finishAlternativeRollbackWithinBudget(batch, budget)) return false;
                    candidateRewind = false;

                    stageReady = true;
                }
                if (acquisition.result() != ProcessResult.SUCCESS) {
                    result = acquisition.result();
                    cancelling = true;
                    return cleanup(budget);
                }
                if (!observeSources(budget, false)) return false;
                if (!stageReady) {
                    if (!batch.stageWithinBudget(forwardAllowed, budget)) {
                        if (batch.workYielded()) return false;
                        result = RecipeText.StorageChanged.blocked();
                        cancelling = true;
                        return cleanup(budget);
                    }
                    stageReady = true;
                }
                if (!acquisition.hasContinuation()) {
                    result = ProcessResult.SUCCESS;
                    cancelling = true;
                    return cleanup(budget);
                }
                if (!continuationPrepared) {
                    if (!acquisition.prepareContinuationWithinBudget(batch, budget)) return false;
                    continuationPrepared = true;
                }
                if (budget.remaining() == 0) return false;
                acquisition.attachFlatParent(batch);
                if (!batch.acquireNextWithinBudget(() -> acquisition.acquireNextWithinBudget(budget))) return false;
                continuationPrepared = false;
                stageReady = false;
                beginSourceObservation(false);
            }
        }

        private void beginSourceObservation(boolean resolving) {
            sourceCursor = 0;
            sawSourceAction = false;
            sourceActionsRequired = false;
            sourceResolution = resolving;
        }

        /**
         * Classifies the exact returned inventory without widening the original fence.
         */
        private boolean appendStep(RecipeWorkBudget budget, boolean rollbackOnly) {
            if (appendInput == null) {
                appendInput = acquisition.stepReservations();
                appendOwnedTokens = new ObjectArrayList<>();
                appendCursor = 0;
                appendViolation = false;
            }
            while (appendCursor < appendInput.size()) {
                if (!budget.take()) return false;
                var token = appendInput.get(appendCursor);
                if (Objects.requireNonNull(batch).ownsContext(Objects.requireNonNull(token.context()))) {
                    Objects.requireNonNull(appendOwnedTokens).add(token);
                    if (!rollbackOnly && !token.supportsSequentialUse()) appendViolation = true;
                } else {
                    if (foreignTokenIdentities.add(token)) foreignTokens.add(token);
                    appendViolation = true;
                }
                appendCursor++;
            }
            if (!Objects.requireNonNull(batch).appendWithinBudget(Objects.requireNonNull(appendOwnedTokens),
                acquisition.stepTransactions(), rollbackOnly || appendViolation, budget)) return false;
            boolean rejected = appendViolation;
            appendInput = null;
            appendOwnedTokens = null;
            if (rejected && !rollbackOnly)
                throw new IllegalArgumentException("Continued reservations must use declared domains and support reversible sequential use");
            return true;
        }

        private boolean observeSources(RecipeWorkBudget budget, boolean resolving) {
            if (sourceActionsRequired) beginSourceObservation(resolving);
            var observing = Objects.requireNonNull(activeSourceBatch());
            while (sourceCursor < observing.sources().size()) {
                if (!budget.take()) return false;
                var source = observing.sources().get(sourceCursor++);
                observedSourceIdentities.add(source);
                var state = source.state();
                if (resolving) {
                    sawSourceAction |= state != ResourceBatch.SourceState.ROLLED_BACK && state != ResourceBatch.SourceState.COMPLETED
                        && !resolvedSources.contains(source);
                } else if (state == ResourceBatch.SourceState.RESERVED) {
                    sawSourceAction = true;
                } else if (state != ResourceBatch.SourceState.APPLIED) {
                    result = RecipeText.StorageChanged.blocked();
                    cancelling = true;
                    return false;
                }
            }
            sourceActionsRequired = sawSourceAction;
            sourceResolution = resolving;
            return !sawSourceAction;
        }

        private boolean cleanup(RecipeWorkBudget budget) {
            if (!acquired) {
                finished = true;
                return true;
            }
            if (!acquisition.finishCapturedInventoryWithinBudget(budget)) return false;
            if (!acquisition.advanceCleanupCandidates(budget, true)) return false;
            if (batchAcquire != null && (!cleanupAcquire || acquireFailed)) {
                if (!acquireCancelled) {
                    batchAcquire.cancel();
                    acquireCancelled = true;
                }
                if (!batchAcquire.advance(budget)) return false;
                batchAcquire.result(); // The exact partial claim has now released every pending domain.
                batchAcquire = null;
                acquireCancelled = false;
                acquireFailed = false;
                cleanupAcquire = false;
            }
            if (batch == null) {
                if (batchAcquire == null) {
                    batchAcquire = cleaningForeign ? ResourceBatch.beginAcquire(foreignTokens, ObjectLists.emptyList())
                        : ResourceBatch.beginAcquire(acquisition.originalTokens, acquisition.transactions);
                    cleanupAcquire = true;
                }
                if (!batchAcquire.advance(budget)) return false;
                batch = batchAcquire.result();
                batchAcquire = null;
                cleanupAcquire = false;
                if (batch == null) return false; // BUSY retains the original tokens for exact cleanup retry.
            }
            if (cleanupPhase == 0) {
                if (batch.acquiringNext()) {
                    if (!appendStep(budget, true)) return false;
                }
                cleanupPhase = 1;
            }
            if (cleanupPhase == 1) {
                boolean settled = batch.decision() == ResourceBatch.Decision.OPEN
                    ? batch.rollbackWithinBudget(budget) : batch.resolveBusinessWithinBudget(budget);
                if (!settled) return false;
                resolvedSources = new ReferenceOpenHashSet<>();
                beginSourceObservation(true);
                cleanupPhase = 2;
            }
            if (cleanupPhase == 2) {
                if (!observeSources(budget, true)) return false;
                cleanupPhase = 3;
            }
            if (!batch.readyToFinish()) {
                cleanupPhase = 1;
                return false;
            }
            if (!batch.finishWithinBudget(budget)) return false;
            if (!cleaningForeign && !foreignTokens.isEmpty()) {
                // Foreign-only domains never joined the original fence. After all original source
                // jobs and business undo finish, claim these exact disjoint tokens for rollback.
                cleaningForeign = true;
                candidateRewind = false;

                batch = null;
                cleanupPhase = 0;
                resolvedSources = new ReferenceOpenHashSet<>();
                beginSourceObservation(true);
                return false;
            }
            finished = true;
            return true;
        }

        public void cancel() {
            requireOwner();
            cancelling = true;
            result = RecipeText.Cancelled.blocked();
        }

        private @Nullable ResourceBatch activeSourceBatch() {
            return batch;
        }

        @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
        public Acquisition.FlatCandidate captureFlatCandidate(RequirementTransaction transaction) {
            return acquisition.captureFlatCandidate(transaction);
        }

        @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
        public Acquisition.FlatCandidate captureCleanupCandidate(RequirementTransaction transaction) {
            return acquisition.captureCleanupCandidate(transaction);
        }

        public boolean flatCleanupPending() {
            return acquisition.flatCleanupPending();
        }

        public boolean finished() {
            requireOwner();
            return finished;
        }

        public ProcessResult result() {
            requireOwner();
            if (!finished) throw new IllegalStateException("Probe result still owns rollback obligations");
            return result;
        }

        public @Nullable Throwable failure() {
            requireOwner();
            return failure;
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner || executing)
                throw new IllegalStateException("Probe requires its idle captured owner");
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
        private final ObjectArrayList<RequirementTransaction> transactions = new ObjectArrayList<>();
        private final ObjectArrayList<ResourceReservation> reservations = new ObjectArrayList<>();
        private final ObjectArrayList<ContinuationFrame> continuations = new ObjectArrayList<>();
        private final Reference2ObjectOpenHashMap<ResourceReservation, SourceObservation> sourceIdentities = new Reference2ObjectOpenHashMap<>();
        private final ObjectArrayList<BusinessObservation> business = new ObjectArrayList<>();
        private final ObjectArrayList<SourceObservation> sources = new ObjectArrayList<>();
        private final List<ResourceReservation> originalTokens = new AbstractList<>() {
            @Override
            public ResourceReservation get(int index) {
                return sources.get(index).token;
            }

            @Override
            public int size() {
                return sources.size();
            }
        };
        private final ObjectArrayList<ResourceReservation> synchronousSources = new ObjectArrayList<>();
        private final ReferenceLinkedOpenHashSet<FlatCandidate> cleanupCandidates = new ReferenceLinkedOpenHashSet<>();
        @Nullable
        private ContinuationFrame rejectedAlternative;
        private int requirementCursor;
        private int stepBusinessStart;
        private int stepSourceStart;
        private boolean sequential;
        @Nullable
        private RecipeProcess.Tick acquiredTick;
        private boolean transitionAdded;
        @Nullable
        private ResourceBatch synchronousBatch;
        private ProcessResult result = ProcessResult.SUCCESS;
        @Nullable
        private Throwable failure;
        private AcquisitionState state = AcquisitionState.NEW;
        private Settlement decision = Settlement.NONE;
        private boolean inventoryComplete = true;
        private boolean inventoryUnknown;
        private boolean acquisitionFailed;
        private boolean synchronousStarted;
        private boolean executing;
        @Nullable
        private RecipeWorkBudget acquisitionWork;
        private boolean workPending;
        @Nullable
        private RequirementTransaction capturing;
        @Nullable
        private Iterator<? extends ResourceReservation> capturingTokens;
        @Nullable
        private ContinuationFrame capturedTail;
        private boolean capturedContinuation;
        private boolean capturedRequirement;
        private int rejectedSearch = -2;
        @Nullable
        private List<ResourceReservation> sealedStepReservations;
        @Nullable
        private List<RequirementTransaction> sealedStepTransactions;
        @Nullable
        private ContinuationFrame preparingFrame;
        private int preparingPhase;
        private int rollbackTrimPhase;
        @Nullable
        private FlatCandidate flat;
        private boolean cleanupCancelled;
        @Nullable
        private Iterator<FlatCandidate> rejectingCleanup;
        private boolean cleanupRejectionComplete;
        @Nullable
        private ResourceBatch flatParentBatch;
        @Nullable
        private RequirementPreparation initialPreparation;
        private int activeAlternativeCount;

        private Acquisition(RecipeProcess process, RecipeStage stage, RequirementTransaction transition) {
            this.process = process;
            this.stage = stage;
            this.transition = transition;
        }

        public boolean rejectCleanupCandidatesWithinBudget(RecipeWorkBudget budget) {
            requireOwner();
            if (cleanupRejectionComplete) return true;
            if (rejectingCleanup == null) rejectingCleanup = cleanupCandidates.iterator();
            while (rejectingCleanup.hasNext()) {
                if (!budget.take()) return false;
                rejectingCleanup.next().cancel();
            }
            rejectingCleanup = null;
            cleanupRejectionComplete = true;
            return true;
        }

        public void captureStageTick() {
            requireOwner();
            if (state != AcquisitionState.ACQUIRING)
                throw new IllegalStateException("Stage tick requires its acquiring cursor");
            acquiredTick = process.captureTickContext();
        }

        public void initializePreparation(RequirementPreparation preparation) {
            requireOwner();
            if (state != AcquisitionState.NEW || initialPreparation != null)
                throw new IllegalStateException("Stage preparation was already initialized");
            initialPreparation = Objects.requireNonNull(preparation);
        }

        @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
        public FlatCandidate captureFlatCandidate(RequirementTransaction transaction) {
            requireOwner();
            if (state != AcquisitionState.ACQUIRING)
                throw new IllegalStateException("Prepared child requires its exact acquiring stage");
            flat = new FlatCandidate(Objects.requireNonNull(transaction), false);
            return flat;
        }

        @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
        public FlatCandidate captureCleanupCandidate(RequirementTransaction transaction) {
            requireOwner();
            if (state != AcquisitionState.ACQUIRING || cleanupCancelled)
                throw new IllegalStateException("Cleanup inventory requires its exact acquiring stage");
            var candidate = new FlatCandidate(Objects.requireNonNull(transaction), true);
            cleanupCandidates.add(candidate);
            cleanupRejectionComplete = false;
            return candidate;
        }

        public boolean flatCleanupPending() {
            return cleanupCandidate() != null;
        }

        @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
        public @Nullable FlatCandidate cleanupCandidate() {
            var next = cleanupCandidates.isEmpty() ? null : cleanupCandidates.first();
            if (next != null && (next.rejected || cleanupCancelled) && !next.cleaned) return next;
            return flat != null && (flat.rejected || cleanupCancelled) && !flat.cleaned ? flat : null;
        }

        public boolean advanceCleanupCandidates(RecipeWorkBudget budget, boolean cancel) {
            requireOwner();
            cleanupCancelled |= cancel;
            while (true) {
                if (cleanupCancelled && flat != null && flat.cleaned) flat.releaseCleaned();
                var candidate = cleanupCandidate();
                if (candidate == null) return true;
                if (cleanupCancelled) candidate.cancel();
                if (!candidate.advanceCleanup(budget)) return false;
                if (candidate.auxiliary || cleanupCancelled) candidate.releaseCleaned();
                else return true;
            }
        }

        @SuppressWarnings({"ReturnOfInnerClass", "unused"}) // Public inspection of the original captured child.
        public @Nullable FlatCandidate flatCandidate() {
            return flat;
        }

        public void attachFlatParent(ResourceBatch parent) {
            flatParentBatch = Objects.requireNonNull(parent);
        }

        public void acquire() {
            requireOwner();
            process.requireAcquisitionBoundary();
            if (state != AcquisitionState.NEW || executing) {
                throw new IllegalStateException("Stage acquisition already entered");
            }
            acquiredTick = process.captureTickContext();
            acquireStep();
        }

        public void acquireWithinBudget(RecipeWorkBudget budget) {
            withinBudget(budget, this::acquire);
        }

        public void resumePreparedWithinBudget(RecipeWorkBudget budget) {
            withinBudget(budget, this::resumePrepared);
        }

        public void acquireNextWithinBudget(RecipeWorkBudget budget) {
            withinBudget(budget, this::acquireNext);
        }

        private void withinBudget(RecipeWorkBudget budget, Runnable action) {
            var previous = acquisitionWork;
            acquisitionWork = Objects.requireNonNull(budget);
            try {
                action.run();
            } finally {
                acquisitionWork = previous;
            }
        }

        private boolean takeWork() {
            return acquisitionWork == null || acquisitionWork.take();
        }

        /**
         * Resumes only after the preceding segment's resource and business effects are staged.
         */
        public void acquireNext() {
            requireOwner();
            process.requireAcquisitionBoundary();
            if (state != AcquisitionState.SEALED || !hasContinuation()) {
                throw new IllegalStateException("No staged requirement continuation remains");
            }
            if (acquiredTick == null) {
                acquireStep();
            } else {
                process.withTickContext(acquiredTick, () -> {
                    acquireStep();
                    return null;
                });
            }
        }

        public void resumePrepared() {
            requireOwner();
            process.requireAcquisitionBoundary();
            if (!preparationPending()) {
                throw new IllegalStateException("No transparent acquisition pause remains");
            }
            if (acquiredTick == null) acquireStep(false);
            else process.withTickContext(acquiredTick, () -> {
                acquireStep(false);
                return null;
            });
        }

        /**
         * Enumerates only the original returned handle; cancellation never acquires another step.
         */
        public boolean finishCapturedInventoryWithinBudget(RecipeWorkBudget budget) {
            requireOwner();
            requireSealed();
            if (inventoryUnknown) return false;
            if (capturing == null) return inventoryComplete;
            boolean[] complete = {false};
            withinBudget(budget, () -> complete[0] = finishCapture(false));
            return complete[0];
        }

        private void acquireStep() {
            acquireStep(true);
        }

        private void acquireStep(boolean beginsSegment) {
            state = AcquisitionState.ACQUIRING;
            if (beginsSegment) {
                stepBusinessStart = transactions.size();
                stepSourceStart = reservations.size();
                sealedStepReservations = null;
                sealedStepTransactions = null;
            }
            boolean resumePrepared = !beginsSegment;
            workPending = false;
            try {
                while (result == ProcessResult.SUCCESS) {
                    if (flatCleanupPending()) {
                        workPending = true;
                        break;
                    }
                    if (capturing != null) {
                        if (!finishCapture()) {
                            workPending = true;
                            break;
                        }
                    }
                    if (capturedRequirement) {
                        capturedRequirement = false;
                        if (!continuations.isEmpty()) break;
                    }
                    if (capturedContinuation) {
                        var tail = Objects.requireNonNull(capturedTail);
                        if (tail.preparation && !continuations.isEmpty() && continuations.getLast() == tail) {
                            if (!takeWork()) {
                                workPending = true;
                                break;
                            }
                            tail.continuation.acquireNext();
                            removeContinuation();
                            capturedContinuation = false;
                            capturedTail = null;
                            resumePrepared = false;
                            continue;
                        }
                        capturedContinuation = false;
                        capturedTail = null;
                        break;
                    }
                    if (!continuations.isEmpty()) {
                        var tail = continuations.getLast();
                        // Closing a transparent preparation never advances the next business
                        // segment before the original segment has been appended and staged.
                        if (!beginsSegment && !tail.preparation) {
                            break;
                        }
                        boolean preparation = tail.continuation.preparationPending();
                        if (preparation && !resumePrepared) {
                            break;
                        }
                        if (acquisitionWork != null && acquisitionWork.remaining() == 0) {
                            workPending = true;
                            break;
                        }
                        if (!tail.continuation.ownerWorkMetered() && !takeWork()) {
                            workPending = true;
                            break;
                        }
                        var transaction = tail.continuation.acquireNext();
                        if (transaction == null) {
                            removeContinuation();
                            if (tail.preparation) {
                                continue;
                            }
                            break;
                        }
                        capturedTail = tail;
                        capturedContinuation = true;
                        capture(transaction);
                        if (capturing != null) {
                            workPending = true;
                            break;
                        }
                        resumePrepared = false;
                        if (tail.preparation && continuations.getLast() == tail) {
                            // Close the transparent cursor immediately; its original acquisition is
                            // now owned by this same segment, without any premature source apply.
                            if (!takeWork()) {
                                workPending = true;
                                break;
                            }
                            tail.continuation.acquireNext();
                            removeContinuation();
                            capturedContinuation = false;
                            capturedTail = null;
                            continue;
                        }
                        capturedContinuation = false;
                        capturedTail = null;
                        break;
                    }
                    if (initialPreparation != null) {
                        if (!takeWork()) {
                            workPending = true;
                            break;
                        }
                        var preparation = initialPreparation;
                        initialPreparation = null;
                        capturedRequirement = true;
                        capture(new PreparedRequirement(preparation));
                        if (capturing != null) workPending = true;
                        else capturedRequirement = false;
                        break;
                    }
                    var requirements = process.orderedRequirements();
                    if (requirementCursor == requirements.size()) {
                        break;
                    }
                    if (!takeWork()) {
                        workPending = true;
                        break;
                    }
                    var transaction = requirements.get(requirementCursor++).acquire(process, stage);
                    capturedRequirement = true;
                    capture(Objects.requireNonNull(transaction, "requirement transaction"));
                    if (capturing != null) {
                        workPending = true;
                        break;
                    }
                    capturedRequirement = false;
                    if (!continuations.isEmpty()) {
                        break;
                    }
                }
            } catch (RuntimeException | Error acquisitionFailure) {
                acquisitionFailed = true;
                failure = acquisitionFailure;
                throw acquisitionFailure;
            } finally {
                if (!acquisitionFailed && result != ProcessResult.SUCCESS) {
                    if (rejectedSearch == -2) rejectedSearch = continuations.size() - 1;
                    while (rejectedSearch >= 0 && rejectedAlternative == null) {
                        if (!takeWork()) {
                            workPending = true;
                            break;
                        }
                        var frame = continuations.get(rejectedSearch--);
                        if (frame.savepoint != null) {
                            rejectedAlternative = frame;
                            break;
                        }
                    }
                }
                if (!workPending && (acquisitionFailed || result != ProcessResult.SUCCESS && rejectedAlternative == null
                    || continuations.isEmpty() && requirementCursor == process.orderedRequirements().size())) {
                    addTransition();
                }
                state = AcquisitionState.SEALED;
            }
        }

        private void capture(RequirementTransaction transaction) {
            if (transaction != RequirementTransaction.SUCCESS) {
                transactions.add(transaction);
                business.add(new BusinessObservation(transaction));
            }
            capturing = transaction;
            inventoryComplete = false;
            try {
                capturingTokens = Objects.requireNonNull(transaction.reservations(), "transaction reservations").iterator();
                finishCapture();
            } catch (RuntimeException | Error listingFailure) {
                if (capturing != null) inventoryComplete = false;
                if (capturing != null && acquisitionWork != null) inventoryUnknown = true;
                throw listingFailure;
            }
        }

        private boolean finishCapture() {
            return finishCapture(true);
        }

        private boolean finishCapture(boolean inspectResult) {
            var transaction = Objects.requireNonNull(capturing);
            try {
                while (Objects.requireNonNull(capturingTokens).hasNext()) {
                    if (!takeWork()) return false;
                    var token = capturingTokens.next();
                    reservations.add(Objects.requireNonNull(token));
                    var observation = sourceIdentities.get(token);
                    if (observation == null) {
                        observation = new SourceObservation(token);
                        sourceIdentities.put(token, observation);
                        sources.add(observation);
                    }
                    synchronousSources.add(observation);
                }
            } catch (RuntimeException | Error listingFailure) {
                inventoryComplete = false;
                if (acquisitionWork != null) inventoryUnknown = true;
                throw listingFailure;
            }
            // Complete inventory is a separate proof from optional result/continuation callbacks.
            // An exception there must never make cleanup reread an already exhausted iterator.
            capturing = null;
            capturingTokens = null;
            inventoryComplete = true;
            if (!inspectResult) return true;
            result = Objects.requireNonNull(transaction.result(), "transaction result");
            if (result == ProcessResult.SUCCESS) {
                var tail = transaction.continuation();
                if (tail != null) {
                    continuations.add(new ContinuationFrame(tail));
                    sequential |= !tail.preparationPending();
                }
            }
            return true;
        }

        private void addTransition() {
            if (!transitionAdded) {
                transitionAdded = true;
                transactions.add(transition);
                business.add(new BusinessObservation(transition));
            }
        }

        /**
         * Called at the idle staged boundary before invoking one continued acquisition.
         */
        public void prepareContinuation(ResourceBatch batch) {
            requireOwner();
            if (rejectedAlternative != null) {
                throw new IllegalStateException("Rejected candidate must resolve before advancing");
            }
            if (continuations.isEmpty()) {
                return;
            }
            var frame = continuations.getLast();
            if (!(frame.continuation instanceof RequirementAlternatives alternatives)) {
                return;
            }
            if (alternatives.startsAlternative()) {
                if (frame.savepoint != null) {
                    throw new IllegalStateException("Candidate already owns a savepoint");
                }
                frame.savepoint = batch.savepoint();
                activeAlternativeCount++;
                frame.businessStart = transactions.size();
                frame.sourceStart = reservations.size();
                frame.observationStart = sources.size();
            } else if (frame.savepoint != null) {
                batch.releaseSavepoint(frame.savepoint);
                activeAlternativeCount--;
                frame.savepoint = null;
            }
        }

        /**
         * The metadata callback and each savepoint source share the caller's allowance.
         */
        public boolean prepareContinuationWithinBudget(ResourceBatch batch, RecipeWorkBudget budget) {
            requireOwner();
            if (rejectedAlternative != null)
                throw new IllegalStateException("Rejected candidate must resolve before advancing");
            if (continuations.isEmpty()) return true;
            var frame = continuations.getLast();
            if (!(frame.continuation instanceof RequirementAlternatives alternatives)) return true;
            if (preparingFrame == null) {
                if (!budget.take()) return false;
                preparingFrame = frame;
                preparingPhase = alternatives.startsAlternative() ? 1 : 2;
                if (preparingPhase == 1 && frame.savepoint != null)
                    throw new IllegalStateException("Candidate already owns a savepoint");
            } else if (preparingFrame != frame) {
                throw new IllegalStateException("Continuation metadata must resume the original frame");
            }
            if (preparingPhase == 1) {
                var mark = batch.savepointWithinBudget(budget);
                if (mark == null) return false;
                frame.savepoint = mark;
                activeAlternativeCount++;
                frame.businessStart = transactions.size();
                frame.sourceStart = reservations.size();
                frame.observationStart = sources.size();
            } else if (frame.savepoint != null) {
                if (!budget.take()) return false;
                batch.releaseSavepoint(frame.savepoint);
                activeAlternativeCount--;
                frame.savepoint = null;
            }
            preparingFrame = null;
            preparingPhase = 0;
            return true;
        }

        private void removeContinuation() {
            var frame = continuations.removeLast();
            if (frame.savepoint != null) activeAlternativeCount--;
            if (capturedTail == frame) {
                capturedTail = null;
                capturedContinuation = false;
            }
        }

        public boolean activeAlternative() {
            return activeAlternativeCount != 0;
        }

        public boolean startsAlternative() {
            return !continuations.isEmpty() && continuations.getLast().continuation instanceof RequirementAlternatives alternatives
                && alternatives.startsAlternative();
        }

        public boolean hasRejectedAlternative() {
            return rejectedAlternative != null;
        }

        public ResourceBatch.Savepoint rejectedSavepoint() {
            return Objects.requireNonNull(Objects.requireNonNull(rejectedAlternative, "No rejected candidate").savepoint);
        }

        /**
         * Advances the original cursor only after the batch confirms every suffix participant.
         */
        public boolean finishAlternativeRollback(ResourceBatch batch) {
            requireOwner();
            var frame = Objects.requireNonNull(rejectedAlternative, "No rejected candidate");
            if (!batch.finishRollbackTo(Objects.requireNonNull(frame.savepoint))) {
                return false;
            }
            batch.access(() -> {
                var alternatives = (RequirementAlternatives) frame.continuation;
                alternatives.rejectAlternative(result);
                while (continuations.getLast() != frame) {
                    removeContinuation();
                }
                for (int index = frame.sourceStart; index < reservations.size(); index++) {
                    sourceIdentities.remove(reservations.get(index));
                }
                transactions.size(frame.businessStart);
                business.size(frame.businessStart);
                reservations.size(frame.sourceStart);
                synchronousSources.size(frame.sourceStart);
                sources.size(frame.observationStart);
                if (frame.savepoint != null) activeAlternativeCount--;
                frame.savepoint = null;
                rejectedAlternative = null;
                rejectedSearch = -2;
                result = ProcessResult.SUCCESS;
            });
            return true;
        }

        /**
         * Resumes the same batch trim before touching local suffix identities or the cursor.
         */
        public boolean finishAlternativeRollbackWithinBudget(ResourceBatch batch, RecipeWorkBudget budget) {
            requireOwner();
            var frame = Objects.requireNonNull(rejectedAlternative, "No rejected candidate");
            if (rollbackTrimPhase == 0) {
                if (!batch.finishRollbackToWithinBudget(Objects.requireNonNull(frame.savepoint), budget)) return false;
                rollbackTrimPhase = 1;
            }
            if (rollbackTrimPhase == 1) {
                if (!budget.take()) return false;
                boolean entered = batch.accessWithinBudget(() -> {
                    rollbackTrimPhase = 2; // Attempt exactly once, even if an external callback throws.
                    ((RequirementAlternatives) frame.continuation).rejectAlternative(result);
                });
                if (!entered) return false;
            }
            while (continuations.getLast() != frame) {
                if (!budget.take()) return false;
                removeContinuation();
            }
            while (reservations.size() > frame.sourceStart) {
                if (!budget.take()) return false;
                var token = reservations.removeLast();
                sourceIdentities.remove(token);
                synchronousSources.removeLast();
            }
            while (transactions.size() > frame.businessStart) {
                if (!budget.take()) return false;
                transactions.removeLast();
                business.removeLast();
            }
            while (sources.size() > frame.observationStart) {
                if (!budget.take()) return false;
                sources.removeLast();
            }
            if (frame.savepoint != null) {
                activeAlternativeCount--;
            }
            frame.savepoint = null;
            rejectedAlternative = null;
            rejectedSearch = -2;
            rollbackTrimPhase = 0;
            result = ProcessResult.SUCCESS;
            return true;
        }

        /**
         * Read-only view valid until the next acquire/rewind step; callers must not retain or mutate it.
         */
        public List<ResourceReservation> stepReservations() {
            requireSealed();
            if (sealedStepReservations == null)
                sealedStepReservations = reservations.subList(stepSourceStart, reservations.size());
            return sealedStepReservations;
        }

        /**
         * Read-only view valid until the next acquire/rewind step; callers must not retain or mutate it.
         */
        public List<RequirementTransaction> stepTransactions() {
            requireSealed();
            if (sealedStepTransactions == null)
                sealedStepTransactions = transactions.subList(stepBusinessStart, transactions.size());
            return sealedStepTransactions;
        }

        public boolean sequential() {
            return sequential;
        }

        public boolean ownerWorkMetered() {
            // acquireStep meters each opaque callback and every transparent visit itself.
            return true;
        }

        public boolean captureOnlyPreparation() {
            requireOwner();
            requireSealed();
            if (workPending && (capturing != null || continuations.isEmpty())) return true;
            return !continuations.isEmpty() && continuations.getLast().continuation.captureOnlyPreparation();
        }

        public void retainPureFacts() {
            requireOwner();
            requireSealed();
            if (!continuations.isEmpty()) {
                continuations.getLast().continuation.retainPureFacts();
            }
        }

        public void withdrawPureFacts(RecipePureComputation expected) {
            requireOwner();
            requireSealed();
            if (!continuations.isEmpty()) continuations.getLast().continuation.withdrawPureFacts(expected);
        }

        public boolean preparationPending() {
            requireOwner();
            requireSealed();
            // A failed returned child retains its frames for rollback, not further preparation.
            return workPending || result == ProcessResult.SUCCESS && !continuations.isEmpty() && continuations.getLast().continuation.preparationPending();
        }

        public @Nullable RecipePureComputation computation() {
            requireOwner();
            requireSealed();
            return continuations.isEmpty() ? null : continuations.getLast().continuation.computation();
        }

        public boolean hasContinuation() {
            return !transitionAdded && !acquisitionFailed && result == ProcessResult.SUCCESS;
        }

        public ProcessResult result() {
            requireSealed();
            return result;
        }

        @Nullable
        public Throwable failure() {
            requireSealed();
            return failure;
        }

        public List<RequirementTransaction> transactions() {
            requireSealed();
            return transactions;
        }

        public List<ResourceReservation> reservations() {
            requireSealed();
            return reservations;
        }

        public boolean inventoryComplete() {
            return state == AcquisitionState.SEALED && inventoryComplete;
        }

        public ProcessResult settleSynchronously() {
            requireReady();
            if (synchronousStarted || decision != Settlement.NONE) {
                throw new IllegalStateException("Cannot replay stage settlement");
            }
            if (acquisitionFailed || !inventoryComplete) {
                throw new IllegalStateException("Cannot commit a failed acquisition");
            }
            if (sequential) {
                return settleSequentially(false);
            }
            if (result != ProcessResult.SUCCESS) {
                rollbackSynchronously();
                return result;
            }
            executing = true;
            try {
                boolean accepted = ResourceTransactions.commit(synchronousSources, this::prepareBusiness, this::validateBusiness, this::commitBusiness,
                    () -> settleBusiness(Settlement.COMPLETE, null), () -> settleBusiness(Settlement.ROLLBACK, null));
                return accepted ? result : RecipeText.StorageChanged.blocked();
            } catch (RuntimeException | Error settlementFailure) {
                remember(settlementFailure);
                throw settlementFailure;
            } finally {
                executing = false;
            }
        }

        private ProcessResult settleSequentially(boolean probe) {
            requireReady();
            if (synchronousStarted || decision != Settlement.NONE) {
                throw new IllegalStateException("Cannot replay sequential settlement");
            }
            synchronousBatch = ResourceBatch.tryAcquireSequential(process.declaredStorageContexts(), reservations, transactions);
            if (synchronousBatch == null) {
                rollbackSynchronously();
                return RecipeText.StorageChanged.blocked();
            }
            var batch = synchronousBatch;
            synchronousStarted = true;
            executing = true;
            boolean accepted = false;
            boolean staged = false;
            Throwable primary = null;
            try {
                while (!acquisitionFailed) {
                    if (hasRejectedAlternative()) {
                        batch.rollbackTo(rejectedSavepoint());
                        for (var source : batch.sources()) {
                            source.resolve();
                        }
                        if (!finishAlternativeRollback(batch)) {
                            break;
                        }
                        staged = true;
                    }
                    if (result != ProcessResult.SUCCESS) {
                        break;
                    }
                    boolean applied = true;
                    for (var entry : batch.sources()) {
                        if (entry.state() == ResourceBatch.SourceState.RESERVED && !entry.apply()) {
                            applied = false;
                            break;
                        }
                    }
                    if (!applied) {
                        break;
                    }
                    if (hasContinuation()) {
                        if (!staged && !batch.stage(() -> true)) {
                            break;
                        }
                        prepareContinuation(batch);
                        acquireSequentialStep(batch);
                        staged = false;
                        continue;
                    }
                    accepted = probe ? batch.stage(() -> true) : batch.commit(() -> true);
                    break;
                }
            } catch (RuntimeException | Error fault) {
                primary = fault;
                remember(fault);
            }
            try {
                resolveSequentialBatch();
            } catch (RuntimeException | Error cleanup) {
                primary = accumulate(primary, cleanup);
                remember(cleanup);
            } finally {
                executing = false;
            }
            rethrow(primary);
            if (result != ProcessResult.SUCCESS) {
                return result;
            }
            return accepted ? ProcessResult.SUCCESS : RecipeText.StorageChanged.blocked();
        }

        private void acquireSequentialStep(ResourceBatch batch) {
            Throwable primary = null;
            try {
                batch.acquireNext(() -> process.acquireContinuation(this));
            } catch (RuntimeException | Error acquisitionFailure) {
                primary = acquisitionFailure;
            }
            try {
                appendSequentialStep(batch);
            } catch (RuntimeException | Error adoptionFailure) {
                primary = accumulate(primary, adoptionFailure);
            }
            rethrow(primary);
        }

        private void appendSequentialStep(ResourceBatch batch) {
            if (!inventoryComplete) {
                return;
            }
            var tokens = stepReservations();
            var steps = stepTransactions();
            for (var token : tokens)
                if (!token.supportsSequentialUse()) {
                    batch.appendForRollback(tokens, steps);
                    throw new IllegalArgumentException("Continued storage reservations must support reversible sequential use");
                }
            batch.append(tokens, steps);
        }

        /**
         * UNKNOWN or an unenumerated acquisition retains the exact batch and its original fence.
         */
        private boolean resolveSequentialBatch() {
            var batch = Objects.requireNonNull(synchronousBatch);
            if (!inventoryComplete) {
                return false;
            }
            Throwable primary = null;
            try {
                if (batch.decision() == ResourceBatch.Decision.OPEN) {
                    batch.rollback();
                } else {
                    batch.resolveBusiness();
                }
            } catch (RuntimeException | Error cleanup) {
                primary = cleanup;
            }
            if (batch.decision() != ResourceBatch.Decision.OPEN) {
                decision = batch.decision() == ResourceBatch.Decision.COMPLETE ? Settlement.COMPLETE : Settlement.ROLLBACK;
                for (var entry : batch.sources()) {
                    if (entry.state() == ResourceBatch.SourceState.COMPLETED || entry.state() == ResourceBatch.SourceState.ROLLED_BACK) {
                        continue;
                    }
                    try {
                        entry.resolve();
                    } catch (RuntimeException | Error cleanup) {
                        primary = accumulate(primary, cleanup);
                    }
                }
                if (batch.readyToFinish()) {
                    try {
                        batch.finish();
                    } catch (RuntimeException | Error cleanup) {
                        primary = accumulate(primary, cleanup);
                    }
                }
            }
            rethrow(primary);
            return batch.finished();
        }

        public void rollbackSynchronously() {
            rollbackSynchronously(null);
        }

        private void rollbackSynchronously(@Nullable Throwable primary) {
            requireReady();
            if (decision == Settlement.COMPLETE) {
                throw new IllegalStateException("Completed stage cannot roll back");
            }
            if (synchronousBatch != null) {
                executing = true;
                try {
                    resolveSequentialBatch();
                } finally {
                    executing = false;
                }
                return;
            }
            executing = true;
            try {
                ResourceTransactions.rollback(synchronousSources, () -> settleBusiness(Settlement.ROLLBACK, primary));
            } catch (RuntimeException | Error cleanup) {
                remember(cleanup);
                throw cleanup;
            } finally {
                executing = false;
            }
        }

        /**
         * Refreshes exact proofs and retries only safe terminal cleanup; UNKNOWN returns pending.
         */
        public boolean resolveSynchronously() {
            requireReady();
            if (synchronousBatch != null) {
                executing = true;
                try {
                    return resolveSequentialBatch();
                } finally {
                    executing = false;
                }
            }
            if (decision == Settlement.NONE) {
                throw new IllegalStateException("Stage settlement has not been decided");
            }
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
                    try {
                        retryable &= observation.confirmOrRetry();
                    } catch (RuntimeException | Error query) {
                        queryFailure = accumulate(queryFailure, query);
                    }
                }
                for (var observation : sources) {
                    try {
                        retryable &= observation.confirmOrRetry();
                    } catch (RuntimeException | Error query) {
                        queryFailure = accumulate(queryFailure, query);
                    }
                }
                rethrow(queryFailure);
                if (!retryable) {
                    return false;
                }
                Throwable cleanup = null;
                try {
                    settleBusiness(decision, null);
                } catch (RuntimeException | Error businessFailure) {
                    cleanup = businessFailure;
                }
                for (int offset = 0; offset < sources.size(); offset++) {
                    int index = decision == Settlement.COMPLETE ? offset : sources.size() - 1 - offset;
                    try {
                        sources.get(index).finish(decision);
                    } catch (RuntimeException | Error sourceFailure) {
                        cleanup = accumulate(cleanup, sourceFailure);
                    }
                }
                rethrow(cleanup);
                return inventoryComplete && allTerminal();
            } catch (RuntimeException | Error recoveryFailure) {
                primary = recoveryFailure;
                remember(recoveryFailure);
                throw recoveryFailure;
            } finally {
                if (locked) {
                    for (int index = contexts.size() - 1; index >= 0; index--) {
                        contexts.get(index).unlockDeferred();
                    }
                }
                executing = false;
                if (locked) {
                    publish(contexts, primary);
                }
            }
        }

        /**
         * Queries never guess: resolveSynchronously refreshes proofs for a previously failed callback.
         */
        public boolean resolved() {
            if (synchronousBatch != null) {
                return !executing && inventoryComplete && synchronousBatch.finished();
            }
            return state == AcquisitionState.SEALED && !executing && inventoryComplete
                && decision != Settlement.NONE && allTerminal();
        }

        private boolean prepareBusiness() {
            synchronousStarted = true;
            for (var observation : business) {
                if (!observation.prepareCommit()) {
                    return false;
                }
            }
            return true;
        }

        private boolean validateBusiness() {
            for (var observation : business) {
                if (!observation.validateCommit()) {
                    return false;
                }
            }
            return true;
        }

        private void commitBusiness() {
            synchronousStarted = true;
            for (var observation : business) {
                observation.commit();
            }
        }

        private void settleBusiness(Settlement expected, @Nullable Throwable primary) {
            if (decision != Settlement.NONE && decision != expected) {
                throw new IllegalStateException("Cannot change stage terminal decision");
            }
            synchronousStarted = true;
            decision = expected;
            Throwable cleanup = primary;
            for (int offset = 0; offset < business.size(); offset++) {
                int index = expected == Settlement.COMPLETE ? offset : business.size() - 1 - offset;
                try {
                    business.get(index).finish(expected);
                } catch (RuntimeException | Error businessFailure) {
                    cleanup = accumulate(cleanup, businessFailure);
                }
            }
            if (primary == null) {
                rethrow(cleanup);
            }
        }

        private ObjectArrayList<StorageContext> orderedContexts() {
            var unique = new ReferenceOpenHashSet<StorageContext>(sources.size());
            var contexts = new ObjectArrayList<StorageContext>(sources.size());
            for (var observation : sources) {
                var context = observation.context();
                if (unique.add(context)) {
                    contexts.add(context);
                }
            }
            contexts.sort(Comparator.comparingLong(StorageContext::order));
            return contexts;
        }

        private boolean allTerminal() {
            for (var observation : business) {
                if (!observation.terminal) {
                    return false;
                }
            }
            for (var observation : sources) {
                if (!observation.terminal) {
                    return false;
                }
            }
            return true;
        }

        private void requireOwner() {
            if (Thread.currentThread() != creator) {
                throw new IllegalStateException("Stage acquisition requires its creating thread");
            }
        }

        private void requireSealed() {
            if (state != AcquisitionState.SEALED) {
                throw new IllegalStateException("Stage acquisition has not sealed its participants");
            }
        }

        private void requireReady() {
            requireOwner();
            requireSealed();
            if (executing) {
                throw new IllegalStateException("Stage settlement callback is executing");
            }
        }

        private void remember(Throwable observed) {
            if (failure == null) {
                failure = observed;
            }
        }

        private static final class ContinuationFrame {
            private final RequirementContinuation continuation;
            private final boolean preparation;
            @Nullable
            private ResourceBatch.Savepoint savepoint;
            private int businessStart;
            private int sourceStart;
            private int observationStart;

            private ContinuationFrame(RequirementContinuation continuation) {
                this.continuation = continuation;
                preparation = continuation.preparationPending();
            }
        }

        /**
         * One flat child inventory and its original rollback obligation; it never stages effects.
         */
        public final class FlatCandidate {
            private final boolean auxiliary;
            @Nullable
            private final FlatCandidate parent;
            private final ObjectArrayList<RequirementTransaction> business = new ObjectArrayList<>();
            private final ReferenceOpenHashSet<RequirementTransaction> childIdentities = new ReferenceOpenHashSet<>();
            private final ReferenceOpenHashSet<ResourceReservation> capturedTokenIdentities = new ReferenceOpenHashSet<>();
            private final ObjectArrayList<ResourceReservation> tokens = new ObjectArrayList<>();
            private final ObjectArrayList<ResourceReservation> ownedTokens = new ObjectArrayList<>();
            private final ObjectArrayList<ResourceReservation> outsideTokens = new ObjectArrayList<>();
            private final ReferenceOpenHashSet<ResourceReservation> tokenIdentities = new ReferenceOpenHashSet<>();
            private final ReferenceOpenHashSet<ResourceBatch.Source> observedSources = new ReferenceOpenHashSet<>();
            private RequirementTransaction child;
            private boolean released;
            @Nullable
            private Iterator<? extends ResourceReservation> inventory;
            @Nullable
            private ResourceBatch.Acquire acquire;
            @Nullable
            private ResourceBatch cleanupBatch;
            @Nullable
            private ProcessResult childResult;
            @Nullable
            private RequirementContinuation childContinuation;
            private int capturePhase;
            private int cleanupStep;
            private int partitionCursor;
            private boolean inventoryUnknown;
            private boolean preparing;
            private boolean rejected;
            private boolean cleaned;
            private boolean cancelled;
            private ReferenceOpenHashSet<ResourceBatch.Source> resolvedSources = new ReferenceOpenHashSet<>();
            private int sourceCursor;
            private boolean sourceActionsRequired;
            private boolean sawSourceAction;
            private boolean borrowed;
            private boolean outside;
            private boolean acquireFailure;
            private boolean acquireCancelled;

            private FlatCandidate(RequirementTransaction child, boolean auxiliary) {
                parent = auxiliary ? null : flat;
                this.child = child;
                this.auxiliary = auxiliary;
                business.add(child);
                childIdentities.add(child);
            }

            public boolean capture(RecipeWorkBudget budget) {
                if (inventoryUnknown) return false;
                if (capturePhase == 0) {
                    if (!budget.take()) return false;
                    try {
                        inventory = Objects.requireNonNull(child.reservations()).iterator();
                        capturePhase = 1;
                    } catch (RuntimeException | Error failure) {
                        inventoryUnknown = true;
                        throw failure;
                    }
                }
                while (capturePhase == 1) {
                    boolean more;
                    try {
                        more = Objects.requireNonNull(inventory).hasNext();
                    } catch (RuntimeException | Error failure) {
                        inventoryUnknown = true;
                        throw failure;
                    }
                    if (!more) break;
                    if (!budget.take()) return false;
                    ResourceReservation token;
                    try {
                        token = Objects.requireNonNull(inventory.next());
                    } catch (RuntimeException | Error failure) {
                        inventoryUnknown = true;
                        throw failure;
                    }
                    if (capturedTokenIdentities.add(token)) tokens.add(token);
                }
                if (capturePhase == 1) {
                    inventory = null;
                    capturePhase = 2;
                }
                if (cancelled) return true; // Known inventory suffices; no result callback is replayed.
                if (capturePhase == 2) {
                    if (!budget.take()) return false;
                    capturePhase = 3;
                    childContinuation = child.continuation();
                }
                if (capturePhase == 3) {
                    if (!budget.take()) return false;
                    capturePhase = 4;
                    preparing = childContinuation != null && childContinuation.preparationPending();
                }
                if (capturePhase == 4) {
                    if (!budget.take()) return false;
                    capturePhase = 5;
                    childResult = preparing ? ProcessResult.SUCCESS : Objects.requireNonNull(child.result());
                }
                return true;
            }

            public void cancel() {
                cancelled = true;
                rejected = true;
            }

            public List<ResourceBatch.Source> sources() {
                return cleanupBatch == null || !sourceActionsRequired ? ObjectLists.emptyList() : cleanupBatch.sources();
            }

            public void finishSourceResolution(ResourceBatch.Source source, boolean resolved) {
                if (!sourceActionsRequired || !observedSources.contains(source))
                    throw new IllegalArgumentException("Different prepared cleanup source");
                if (resolved) resolvedSources.add(source);
            }

            private void beginSourceObservation() {
                sourceCursor = 0;
                sourceActionsRequired = false;
                sawSourceAction = false;
            }

            private boolean observeSources(RecipeWorkBudget budget) {
                if (sourceActionsRequired) beginSourceObservation();
                while (sourceCursor < Objects.requireNonNull(cleanupBatch).sources().size()) {
                    if (!budget.take()) return false;
                    var source = cleanupBatch.sources().get(sourceCursor++);
                    observedSources.add(source);
                    var state = source.state();
                    sawSourceAction |= state != ResourceBatch.SourceState.ROLLED_BACK && state != ResourceBatch.SourceState.COMPLETED && !resolvedSources.contains(source);
                }
                sourceActionsRequired = sawSourceAction;
                return !sawSourceAction;
            }

            public ProcessResult result() {
                return Objects.requireNonNull(childResult);
            }

            public List<ResourceReservation> reservations() {
                return tokens;
            }

            public RequirementTransaction transaction() {
                return child;
            }

            public List<RequirementTransaction> transactions() {
                return business;
            }

            public void continueWith(RequirementTransaction transaction) {
                if (auxiliary || rejected || released || !preparing || capturePhase != 5)
                    throw new IllegalStateException("Prepared child continuation has no exact capture owner");
                child = Objects.requireNonNull(transaction);
                if (childIdentities.add(child)) business.add(child);
                childResult = null;
                childContinuation = null;
                preparing = false;
                capturePhase = 0;
            }

            public @Nullable RequirementContinuation continuation() {
                return childContinuation;
            }

            public boolean preparationPending() {
                return preparing;
            }

            public void reject() {
                rejected = true;
            }

            public boolean cleaned() {
                return cleaned;
            }

            public void transfer() {
                if (auxiliary || flat != this || rejected)
                    throw new IllegalStateException("Different flat candidate ownership");
                flat = parent;
                released = true;
            }

            public void releaseCleaned() {
                if (released) return;
                if (!cleaned || (auxiliary ? cleanupCandidates.isEmpty() || cleanupCandidates.first() != this : flat != this))
                    throw new IllegalStateException("Flat cleanup has not completed");
                if (auxiliary) cleanupCandidates.removeFirst();
                else flat = parent;
                released = true;
            }

            public boolean advanceCleanup(RecipeWorkBudget budget) {
                if (cleaned) return true;
                if (!capture(budget)) return false;
                while (partitionCursor < tokens.size()) {
                    if (!budget.take()) return false;
                    var token = tokens.get(partitionCursor);
                    if (!tokenIdentities.contains(token)) {
                        if (flatParentBatch == null || flatParentBatch.ownsContext(Objects.requireNonNull(token.context())))
                            ownedTokens.add(token);
                        else outsideTokens.add(token);
                        tokenIdentities.add(token);
                    }
                    partitionCursor++;
                }
                if (cleanupStep == 0) {
                    if (flatParentBatch != null) {
                        borrowed = true;
                        cleanupBatch = flatParentBatch;
                        if (!flatParentBatch.adoptRejectedWithinBudget(ownedTokens, business, budget)) return false;
                    } else {
                        if (!advanceClaim(ownedTokens, business, budget)) return false;
                    }
                    cleanupStep = 1;
                }
                if (cleanupStep == 4) {
                    if (!advanceClaim(outsideTokens, ObjectLists.emptyList(), budget)) return false;
                    cleanupStep = 1;
                }
                var bound = Objects.requireNonNull(cleanupBatch);
                if (cleanupStep == 1) {
                    boolean resolved = borrowed ? bound.resolveBusinessWithinBudget(budget)
                        : bound.decision() == ResourceBatch.Decision.OPEN ? bound.rollbackWithinBudget(budget)
                        : bound.resolveBusinessWithinBudget(budget);
                    if (!resolved) return false;
                    resolvedSources = new ReferenceOpenHashSet<>();
                    beginSourceObservation();
                    cleanupStep = 2;
                }
                if (cleanupStep == 2) {
                    if (!observeSources(budget)) return false;
                    cleanupStep = 3;
                }
                if (borrowed) {
                    if (!bound.finishRejectedWithinBudget(budget)) {
                        if (!bound.workYielded()) cleanupStep = 1;
                        return false;
                    }
                    borrowed = false;
                } else {
                    if (!bound.readyToFinish()) {
                        cleanupStep = 1;
                        return false;
                    }
                    if (!bound.finishWithinBudget(budget)) return false;
                }
                cleanupBatch = null;
                if (!outside && !outsideTokens.isEmpty()) {
                    outside = true;
                    cleanupStep = 4;
                    return false;
                }
                cleaned = true;
                sourceActionsRequired = false;
                return true;
            }

            private boolean advanceClaim(List<ResourceReservation> input, List<RequirementTransaction> transactions, RecipeWorkBudget budget) {
                if (acquire != null && acquireFailure) {
                    if (!acquireCancelled) {
                        acquire.cancel();
                        acquireCancelled = true;
                    }
                    if (!acquire.advance(budget)) return false;
                    acquire.result();
                    acquire = null;
                    acquireFailure = false;
                    acquireCancelled = false;
                }
                if (acquire == null) acquire = ResourceBatch.beginAcquire(input, transactions);
                try {
                    if (!acquire.advance(budget)) return false;
                    cleanupBatch = acquire.result();
                    acquire = null;
                    return cleanupBatch != null;
                } catch (RuntimeException | Error failure) {
                    acquireFailure = true;
                    throw failure;
                }
            }
        }

        private final class BusinessObservation {
            private final RequirementTransaction transaction;
            private boolean preparationAttempted;
            private boolean preparationAccepted;
            private boolean committed;
            private boolean validationAttempted;
            private boolean uncertain;
            private boolean terminal;

            private BusinessObservation(RequirementTransaction transaction) {
                this.transaction = transaction;
            }

            private boolean prepareCommit() {
                if (preparationAttempted || committed || terminal) {
                    throw new IllegalStateException("Cannot replay business preparation");
                }
                preparationAttempted = true;
                preparationAccepted = transaction.prepareCommit();
                return preparationAccepted;
            }

            private void commit() {
                if (committed || terminal || !preparationAccepted) {
                    throw new IllegalStateException("Cannot replay or commit unprepared business work");
                }
                committed = true;
                transaction.commit();
            }

            private boolean validateCommit() {
                if (!committed || validationAttempted || terminal) {
                    throw new IllegalStateException("Cannot validate uncommitted or resolving business work");
                }
                validationAttempted = true;
                return transaction.validateCommit();
            }

            private boolean confirmOrRetry() {
                if (terminal || !uncertain) {
                    return true;
                }
                var actual = Objects.requireNonNull(transaction.resolution());
                var expected = decision == Settlement.COMPLETE ? ReversibleTransaction.Resolution.COMPLETED : ReversibleTransaction.Resolution.ROLLED_BACK;
                if (actual == expected) {
                    terminal = true;
                    uncertain = false;
                    return true;
                }
                if (actual == ReversibleTransaction.Resolution.UNKNOWN) {
                    return false;
                }
                if (actual == ReversibleTransaction.Resolution.REVERSIBLE) {
                    return true;
                }
                throw new IllegalStateException("Business terminal proof contradicts stage settlement: " + actual);
            }

            private void finish(Settlement expected) {
                if (terminal) {
                    return;
                }
                boolean retryable = confirmOrRetry();
                if (terminal || !retryable) {
                    return;
                }
                try {
                    if (expected == Settlement.COMPLETE) {
                        transaction.complete();
                    } else {
                        transaction.rollback();
                    }
                    terminal = true;
                    uncertain = false;
                } catch (RuntimeException | Error cleanup) {
                    uncertain = true;
                    try {
                        confirmOrRetry();
                    } catch (RuntimeException | Error query) {
                        if (cleanup != query) {
                            cleanup.addSuppressed(query);
                        }
                    }
                    throw cleanup;
                }
            }
        }

        private final class SourceObservation implements ResourceReservation {
            private final ResourceReservation token;
            private boolean uncertain;
            private boolean terminal;

            private SourceObservation(ResourceReservation token) {
                this.token = token;
            }

            @Override
            public StorageContext context() {
                return token.context();
            }

            @Override
            public boolean canApply() {
                synchronousStarted = true;
                return token.canApply();
            }

            @Override
            public @Nullable Boolean canApplyWithinBudget(RecipeWorkBudget budget) {
                synchronousStarted = true;
                return token.canApplyWithinBudget(budget);
            }

            @Override
            public boolean applyWithinBudget(RecipeWorkBudget budget) {
                return token.applyWithinBudget(budget);
            }

            @Override
            public boolean exposeAppliedWithinBudget(RecipeWorkBudget budget) {
                return token.exposeAppliedWithinBudget(budget);
            }

            @Override
            public boolean completeWithinBudget(RecipeWorkBudget budget) {
                return finishWithinBudget(Settlement.COMPLETE, budget);
            }

            @Override
            public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
                return finishWithinBudget(Settlement.ROLLBACK, budget);
            }

            @Override
            public Resolution resolution() {
                return token.resolution();
            }

            @Override
            public @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
                return token.resolutionWithinBudget(budget);
            }

            private boolean finishWithinBudget(Settlement expected, RecipeWorkBudget budget) {
                if (decision != expected)
                    throw new IllegalStateException("Source cannot change stage terminal decision");
                if (terminal) return true;
                if (uncertain) {
                    var actual = token.resolutionWithinBudget(budget);
                    if (actual == null || actual == Resolution.UNKNOWN) return false;
                    var intended = expected == Settlement.COMPLETE ? Resolution.COMPLETED : Resolution.ROLLED_BACK;
                    if (actual == intended) {
                        terminal = true;
                        uncertain = false;
                        return true;
                    }
                    if (actual != Resolution.APPLIED && actual != Resolution.REVERSIBLE && !(expected == Settlement.ROLLBACK && actual == Resolution.RESERVED)) {
                        throw new IllegalStateException("Source terminal proof contradicts stage settlement: " + actual);
                    }
                }
                try {
                    boolean complete = expected == Settlement.COMPLETE ? token.completeWithinBudget(budget) : token.rollbackWithinBudget(budget);
                    if (complete) {
                        terminal = true;
                        uncertain = false;
                    }
                    return complete;
                } catch (RuntimeException | Error failure) {
                    uncertain = true;
                    throw failure;
                }
            }

            @Override
            public void apply() {
                token.apply();
            }

            @Override
            public boolean supportsSequentialUse() {
                return token.supportsSequentialUse();
            }

            @Override
            public void exposeApplied() {
                token.exposeApplied();
            }

            @Override
            public void complete() {
                finish(Settlement.COMPLETE);
            }

            @Override
            public void rollback() {
                finish(Settlement.ROLLBACK);
            }

            private boolean confirmOrRetry() {
                if (terminal || !uncertain) {
                    return true;
                }
                var actual = Objects.requireNonNull(token.resolution());
                var expected = decision == Settlement.COMPLETE ? Resolution.COMPLETED : Resolution.ROLLED_BACK;
                if (actual == expected) {
                    terminal = true;
                    uncertain = false;
                    return true;
                }
                if (actual == Resolution.UNKNOWN) {
                    return false;
                }
                if (actual == Resolution.APPLIED || decision == Settlement.ROLLBACK && actual == Resolution.RESERVED) {
                    return true;
                }
                throw new IllegalStateException("Source terminal proof contradicts stage settlement: " + actual);
            }

            private void finish(Settlement expected) {
                if (decision != expected) {
                    throw new IllegalStateException("Source cannot change stage terminal decision");
                }
                if (terminal) {
                    return;
                }
                boolean retryable = confirmOrRetry();
                if (terminal || !retryable) {
                    return;
                }
                try {
                    if (expected == Settlement.COMPLETE) {
                        token.complete();
                    } else {
                        token.rollback();
                    }
                    terminal = true;
                    uncertain = false;
                } catch (RuntimeException | Error cleanup) {
                    uncertain = true;
                    try {
                        confirmOrRetry();
                    } catch (RuntimeException | Error query) {
                        if (cleanup != query) {
                            cleanup.addSuppressed(query);
                        }
                    }
                    throw cleanup;
                }
            }
        }
    }
}
