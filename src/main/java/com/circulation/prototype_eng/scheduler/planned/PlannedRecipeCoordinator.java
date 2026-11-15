package com.circulation.prototype_eng.scheduler.planned;

import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.BudgetResult;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * One captured multi-machine tick, driven through real recipe stages and original source lanes.
 * The caller retains this coordinator until finished, including after a thrown advance. Capture,
 * requirement callbacks and business settlement run on the owner; only physical source actions are
 * dispatched. This does not manufacture a pure prepare kernel from mutable legacy callbacks.
 */
public final class PlannedRecipeCoordinator implements PlannedExecution {
    private final Thread owner = Thread.currentThread();
    private final PlannedTaskExecutor tasks;
    @Nullable
    private final MachineWorkSlots.TickGroup group;
    @Nullable
    private final RecipeProcess process;
    @Nullable
    private final Function<RecipeProcess.Stage, List<? extends ReversibleTransaction>> participants;
    private final PlannedDeadline forwardDeadline;
    private final PlannedCancellationToken cancellation;
    private final BooleanSupplier authorization;
    private final LongSupplier nanoTime;
    private final ObjectOpenHashSet<UUID> rejectedCohorts = new ObjectOpenHashSet<>();
    private ObjectArrayList<Entry> round = new ObjectArrayList<>();
    @Nullable
    private List<RecipeProcess.Stage> roundCapture;
    private int roundCaptureCursor;
    private int cleanupRoundCursor = -2;
    private boolean cleanupRoundSelected;
    private ObjectArrayList<Entry> cleanupTransferable = new ObjectArrayList<>();
    @Nullable
    private ObjectArrayList<Entry> unresolvedEntries;
    private int unresolvedCursor;
    private long workPerAdvance = 16_384;
    private RecipeWorkBudget work = new RecipeWorkBudget(0);
    @Nullable
    private PlannedTaskExecutor.Batch<Boolean> preparing;
    private ReferenceOpenHashSet<RecipePureComputation> preparedComputations = new ReferenceOpenHashSet<>();
    private ObjectArrayList<RecipeWorkBudget> prepareSlices = new ObjectArrayList<>();
    private ObjectArrayList<RecipeWorkBudget> prepareReceipts = new ObjectArrayList<>();
    private ObjectArrayList<RecipeProcess.Stage> prepareOwners = new ObjectArrayList<>();
    private ObjectArrayList<PlannedTaskExecutor.Job<Boolean>> prepareJobs = new ObjectArrayList<>();
    @Nullable
    private List<RecipeProcess.Stage> prepareInput;
    @Nullable
    private RecipePureComputation pendingComputation;
    @Nullable
    private RecipeProcess.Stage pendingPrepareOwner;
    @Nullable
    private RecipeWorkBudget pendingPrepareSlice;
    private RecipeWorkBudget pendingPrepareReceipt = new RecipeWorkBudget(0);
    private int prepareCapture;
    private int prepareReceipt;
    private int prepareReclaimed;
    private boolean prepareCompleted = true;
    private boolean prepareInspectionReady;
    @Nullable
    private RecipeProcess.TickSession processSession;
    private boolean processFinished;
    private int acquisitionCursor;
    private int preparationCursor;
    private boolean begun;
    private boolean executing;
    private boolean workYielded;
    @Nullable
    private Reason stopReason;
    @Nullable
    private Throwable failure;
    @Nullable
    private Throwable unreported;
    @Nullable
    private Settlement settlement;
    @Nullable
    private PreparedCleanup preparedCleanup;
    @Nullable
    private Binding binding;
    @Nullable
    private PlannedDeadline activeCleanupDeadline;
    private ObjectArrayList<RecipePureComputation> prepareRound = new ObjectArrayList<>();
    private boolean prepareOwnerSelected;
    private boolean receivedWorkerExit;

    public PlannedRecipeCoordinator(PlannedTaskExecutor tasks, List<MachineWorkSlots> hosts,
                                    PlannedDeadline forwardDeadline, PlannedCancellationToken cancellation,
                                    BooleanSupplier authorization) {
        this(tasks, hosts, forwardDeadline, cancellation, authorization, System::nanoTime);
    }

    PlannedRecipeCoordinator(PlannedTaskExecutor tasks, List<MachineWorkSlots> hosts,
                             PlannedDeadline forwardDeadline, PlannedCancellationToken cancellation,
                             BooleanSupplier authorization, LongSupplier nanoTime) {
        this.tasks = tasks;
        this.forwardDeadline = forwardDeadline;
        this.cancellation = cancellation;
        this.authorization = authorization;
        this.nanoTime = nanoTime;
        group = MachineWorkSlots.prepareTickGroup(hosts);
        process = null;
        participants = null;
    }

    private PlannedRecipeCoordinator(PlannedTaskExecutor tasks, RecipeProcess process,
                                     PlannedDeadline forwardDeadline, PlannedCancellationToken cancellation, BooleanSupplier authorization,
                                     @NotNull Function<RecipeProcess.Stage, List<? extends ReversibleTransaction>> participants) {
        this.tasks = tasks;
        this.process = process;
        this.forwardDeadline = forwardDeadline;
        this.cancellation = cancellation;
        this.authorization = authorization;
        this.participants = participants;
        nanoTime = System::nanoTime;
        group = null;
        if (!process.isMaterialCycle() || process.host() != null) {
            throw new IllegalArgumentException("Detached coordination requires an unattached material cycle");
        }
        process.requirePersistenceBoundary();
    }

    /**
     * Drives one unattached material cycle on its real owner and captured hatches. The caller retains
     * this exact coordinator across busy domains, UNKNOWN cleanup and cancellation. Capture invokes
     * no callbacks. After each successful requirement acquisition, participants is called once:
     * null blocks and rolls back that stage; an empty list adds nothing. Returned acquisitions must
     * be distinct, newly owned business transactions, with no stage-owned transaction repeated.
     */
    public static PlannedRecipeCoordinator forProcess(PlannedTaskExecutor tasks, RecipeProcess process,
                                                      PlannedDeadline forwardDeadline, PlannedCancellationToken cancellation, BooleanSupplier authorization,
                                                      Function<RecipeProcess.Stage, List<? extends ReversibleTransaction>> participants) {
        return new PlannedRecipeCoordinator(tasks, process, forwardDeadline, cancellation, authorization, participants);
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Planned source action failed", failure);
        }
    }

    private static List<RecipeProcess.Stage> stageView(List<Entry> entries, int offset) {
        return new AbstractList<>() {
            @Override
            public RecipeProcess.Stage get(int index) {
                return entries.get(Math.addExact(offset, index)).stage;
            }

            @Override
            public int size() {
                return entries.size() - offset;
            }
        };
    }

    /**
     * Cooperative internal work allowance; may be set only before the first owner advance.
     */
    public void workPerAdvance(long units) {
        requireOwner();
        if (begun || units < 1) {
            throw new IllegalStateException("Recipe work allowance must be positive and set before execution");
        }
        workPerAdvance = units;
    }

    /**
     * Runs until complete or accurately pending. A fresh cleanup deadline permits later confirmation,
     * never additional forward work after the original deadline/cancellation. New faults are thrown
     * after the available cleanup work; false is an ordinary pending state, not an exception signal.
     */
    @Override
    public boolean advance(PlannedDeadline cleanupDeadline) {
        requireOwner();
        activeCleanupDeadline = cleanupDeadline;
        if (finished()) {
            return true;
        }
        if (preparing == null && (settlement == null || settlement.barrier == null) && (preparedCleanup == null || preparedCleanup.barrier == null)) {
            work = new RecipeWorkBudget(workPerAdvance);
        }
        executing = true;
        workYielded = false;
        try {
            while (!finished()) {
                receivedWorkerExit = false;
                if (preparedCleanup != null && !drivePreparedCleanup(preparedCleanup.stage, !forwardAllowed(), cleanupDeadline))
                    break;
                if (binding != null) {
                    if (!bind(binding.entries, binding.rollback || !forwardAllowed())) break;
                    continue;
                }
                if (preparing != null) {
                    if (!finishPrepare()) {
                        break;
                    }
                }
                if (settlement != null) {
                    long allowance = work.remaining();
                    if (!settleActive(cleanupDeadline)) {
                        if (settlement != null && RecipeProcess.Stage.nextBatchPending(settlement.stages)
                            && (forwardAllowed() || (settlement.rollback || stopReason != null) && !cleanupDeadline.expired(nanoTime.getAsLong()))
                            && work.remaining() > 0 && (work.remaining() < allowance || receivedWorkerExit)) continue;
                        break;
                    }
                    continue;
                }
                if (roundCapture != null && !captureRound()) break;
                if (!forwardAllowed()) {
                    if (!cleanRound(cleanupDeadline)) {
                        break;
                    }
                    if (settlement != null) {
                        continue;
                    }
                    finishGroup();
                    break;
                }
                if (!begun) {
                    begun = true;
                    try {
                        beginProcesses();
                    } catch (RuntimeException | Error beginFailure) {
                        record(beginFailure);
                    }
                    continue;
                }
                if (round.isEmpty()) {
                    try {
                        roundCapture = nextStages();
                    } catch (RuntimeException | Error captureFailure) {
                        record(captureFailure);
                        // nextRound can throw after creating earlier prepared leases.
                        roundCapture = currentStages();
                        continue;
                    }
                    if (!captureRound()) break;
                    acquisitionCursor = 0;
                    if (round.isEmpty()) {
                        finishGroup();
                        break;
                    }
                }
                if (acquisitionCursor < round.size()) {
                    if (!takeOwnerWork()) {
                        break;
                    }
                    var entry = round.get(acquisitionCursor++);
                    entry.acquired = true;
                    try {
                        entry.stage.acquirePreparedWithinBudget(work);
                    } catch (RuntimeException | Error acquisitionFailure) {
                        record(acquisitionFailure);
                        continue;
                    }
                    continue;
                }
                if (preparationCursor < round.size()) {
                    var entry = round.get(preparationCursor);
                    if (entry.stage.preparedCleanupPending() && !drivePreparedCleanup(entry.stage, false, cleanupDeadline))
                        break;
                    if (entry.stage.preparationPending()) {
                        var stages = stageView(round, preparationCursor);
                        if (!prepareContinuations(stages) || !entry.stage.ownerWorkMetered() && !takeOwnerWork()) {
                            break;
                        }
                        if (!forwardAllowed()) continue;
                        if (!entry.stage.preparationPending()) continue;
                        if (work.remaining() == 0) {
                            workYielded = true;
                            break;
                        }
                        try {
                            entry.stage.resumePreparedWithinBudget(work);
                            prepareInspectionReady = false;
                            if (work.remaining() == 0 && entry.stage.preparationPending()) {
                                workYielded = true;
                                break;
                            }
                        } catch (RuntimeException | Error acquisitionFailure) {
                            record(acquisitionFailure);
                        }
                        continue;
                    }
                    boolean accepted = entry.stage.result() == ProcessResult.SUCCESS;
                    if (accepted && participants != null) {
                        try {
                            var result = captureParticipants(entry);
                            if (result == BudgetResult.YIELDED) break;
                            accepted = result == BudgetResult.ACCEPTED;
                        } catch (RuntimeException | Error participantFailure) {
                            record(participantFailure);
                            continue;
                        }
                    }
                    preparationCursor++;
                    if (!accepted) {
                        var timing = entry.stage.process().crossRecipeTiming();
                        if (timing != null) rejectedCohorts.add(timing.batch());
                    }
                    if (!accepted && !bind(List.of(entry), true)) {
                        break;
                    }
                    continue;
                }
                var accepted = unresolved();
                if (accepted == null) break;
                Entry cohortRejected = null;
                for (int index1 = 0; index1 < accepted.size(); index1++) {
                    var entry = accepted.get(index1);
                    var timing = entry.stage.process().crossRecipeTiming();
                    if (timing != null && rejectedCohorts.contains(timing.batch())) {
                        cohortRejected = entry;
                        break;
                    }
                }
                if (cohortRejected != null) {
                    cohortRejected.stage.deferBatchRound();
                    if (!bind(List.of(cohortRejected), true)) break;
                    continue;
                }
                if (!accepted.isEmpty()) {
                    if (!bind(accepted, false)) {
                        break;
                    }
                } else {
                    clearRound();
                }
            }
        } catch (RuntimeException | Error unexpected) {
            record(unexpected);
        } finally {
            executing = false;
        }
        var observed = unreported;
        unreported = null;
        rethrow(observed);
        return finished();
    }

    @Override
    public boolean finished() {
        return group != null ? group.finished() : processFinished;
    }

    private boolean takeOwnerWork() {
        if (work.take()) {
            return true;
        }
        workYielded = true;
        return false;
    }

    /**
     * Only ordinary bounded work may continue; UNKNOWN confirmation never enters this path.
     */
    public boolean workYielded() {
        requireOwner();
        return workYielded && stopReason == null && failure == null && !finished();
    }

    @SuppressWarnings("DataFlowIssue")
    private void beginProcesses() {
        if (group != null) {
            group.begin();
        } else {
            processSession = process.beginTick();
        }
    }

    private List<RecipeProcess.Stage> nextStages() {
        if (group != null) {
            return group.nextRound();
        }
        var session = processSession;
        var stage = session == null ? null : session.nextStage();
        return stage == null ? List.of() : List.of(stage);
    }

    private List<RecipeProcess.Stage> currentStages() {
        if (group != null) {
            return group.currentStages();
        }
        var session = processSession;
        var stage = session == null ? null : session.currentStage();
        return stage == null ? List.of() : List.of(stage);
    }

    @SuppressWarnings("DataFlowIssue")
    private boolean captureRound() {
        var captured = roundCapture;
        while (roundCaptureCursor < captured.size()) {
            if (!takeOwnerWork()) return false;
            round.add(new Entry(captured.get(roundCaptureCursor++)));
        }
        roundCapture = null;
        roundCaptureCursor = 0;
        acquisitionCursor = preparationCursor = 0;
        return true;
    }

    @SuppressWarnings("DataFlowIssue")
    private BudgetResult captureParticipants(Entry entry) {
        if (entry.participantUnknown) return BudgetResult.YIELDED;
        if (entry.participantAccepted != BudgetResult.YIELDED) return entry.participantAccepted;
        if (!entry.participantStarted) {
            if (!takeOwnerWork()) return BudgetResult.YIELDED;
            entry.participantStarted = true;
            try {
                var captured = participants.apply(entry.stage);
                if (captured == null) {
                    entry.participantAccepted = BudgetResult.REJECTED;
                    return BudgetResult.REJECTED;
                }
                entry.participantInput = captured.iterator();
            } catch (RuntimeException | Error failure) {
                entry.participantUnknown = true;
                throw failure;
            }
        }
        while (entry.participantInput.hasNext()) {
            if (!takeOwnerWork()) return BudgetResult.YIELDED;
            var transaction = entry.participantInput.next();
            if (!entry.participantIdentities.add(transaction))
                throw new IllegalArgumentException("Repeated additional business acquisition");
            entry.participants.add(transaction);
        }
        entry.participantAccepted = BudgetResult.ACCEPTED;
        entry.participantInput = null;
        return BudgetResult.ACCEPTED;
    }

    /**
     * Accepted source actions require physical exit before another owner settlement attempt.
     */
    public boolean hasActiveSources() {
        requireOwner();
        return preparing != null || settlement != null && settlement.barrier != null || preparedCleanup != null && preparedCleanup.barrier != null;
    }

    @Override
    public void cancel(Reason reason) {
        requireOwner();
        cancellation.cancel(reason);
    }

    @Nullable
    public Reason stopReason() {
        return stopReason;
    }

    @Nullable
    public Throwable failure() {
        return failure;
    }

    /**
     * Retirement waits for actual source action exit, not just timeout results. Ownership remains
     * captured afterwards; call advance with a cleanup budget to confirm resources and run post.
     */
    @Override
    public void awaitExit() {
        requireOwner();
        var current = settlement;
        if (preparing == null && (current == null || current.barrier == null) && (preparedCleanup == null || preparedCleanup.barrier == null)) {
            return;
        }
        executing = true;
        try {
            awaitCapturedWorkers();
        } catch (RuntimeException | Error interruption) {
            stop(Reason.INTERRUPTED);
            record(interruption);
            unreported = null;
            throw interruption;
        } finally {
            executing = false;
        }
    }

    /**
     * Join-only handoff after the coordinator action exited; receipts remain owned by its original thread.
     */
    public void awaitWorkerExit() {
        if (executing) throw new IllegalStateException("Coordinator action has not exited");
        awaitCapturedWorkers();
    }

    private void awaitCapturedWorkers() {
        var barriers = new ObjectArrayList<PlannedTaskExecutor.Batch<Boolean>>(3);
        if (preparing != null) barriers.add(preparing);
        if (settlement != null && settlement.barrier != null) barriers.add(settlement.barrier);
        if (preparedCleanup != null && preparedCleanup.barrier != null) barriers.add(preparedCleanup.barrier);
        Throwable observed = null;
        for (int index2 = 0; index2 < barriers.size(); index2++) {
            var barrier = barriers.get(index2);
            try {
                barrier.awaitQuiescence();
            } catch (RuntimeException | Error failure) {
                if (observed == null) observed = failure;
                else if (observed != failure) observed.addSuppressed(failure);
            }
        }
        rethrow(observed);
    }

    private boolean forwardAllowed() {
        if (stopReason != null) {
            return false;
        }
        var cancelled = cancellation.reason();
        if (cancelled != null) {
            stop(cancelled);
            return false;
        }
        if (Thread.currentThread().isInterrupted()) {
            stop(Reason.INTERRUPTED);
            return false;
        }
        if (forwardDeadline.expired(nanoTime.getAsLong())) {
            stop(Reason.WORKER_TIMEOUT);
            return false;
        }
        if (!authorization.getAsBoolean()) {
            stop(Reason.OWNER_CHANGED);
            return false;
        }
        return true;
    }

    private boolean bind(List<Entry> entries, boolean rollback) {
        var active = binding;
        if (active == null) {
            active = new Binding(entries, rollback);
            binding = active;
        }
        active.rollback |= rollback;
        try {
            if (active.failedForwardClaim) {
                if (active.capture != null && !active.capture.releaseCancelledClaimWithinBudget(work)) return false;
                while (active.cleanupParticipantEntry < active.entries.size()) {
                    if (!cleanParticipants(active.entries.get(active.cleanupParticipantEntry))) return false;
                    active.cleanupParticipantEntry++;
                }
                active.capture = null;
                active.failedForwardClaim = false;
                active.omitAdditional = true;
            }
            while (active.entry < active.entries.size()) {
                var entry = active.entries.get(active.entry);
                if (active.rollback && !drivePreparedCleanup(entry.stage, true, activeCleanupDeadline))
                    return false;
                if (!active.selected) {
                    if (!takeOwnerWork()) return false;
                    active.stages.add(entry.stage);
                    active.selected = true;
                }
                while (active.participant < entry.participants.size()) {
                    if (!takeOwnerWork()) return false;
                    active.additional.add(entry.participants.get(active.participant++));
                }
                active.entry++;
                active.participant = 0;
                active.selected = false;
            }
            if (active.capture == null)
                active.capture = RecipeProcess.Stage.beginBatchCapture(active.stages, active.omitAdditional ? List.of() : active.additional);
            if (active.rollback) active.capture.cancel();
            if (!active.capture.advance(work)) {
                workYielded |= work.remaining() == 0;
                return false;
            }
            var resources = active.capture.result();
            if (resources == null) {
                stop(Reason.RESERVATION_CONFLICT);
                binding = null;
                return false;
            }
            settlement = new Settlement(resources, active.entries, active.rollback);
            binding = null;
            return true;
        } catch (RuntimeException | Error bindingFailure) {
            boolean beginCleanup = !active.rollback;
            active.failedForwardClaim |= beginCleanup;
            active.rollback = true;
            if (active.capture != null) active.capture.cancel();
            record(bindingFailure);
            return beginCleanup;
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private boolean cleanParticipants(Entry entry) {
        if (entry.participants.isEmpty()) return true;
        if (entry.participantCleanup == null) {
            if (entry.participantCleanupCapture == null)
                entry.participantCleanupCapture = ResourceBatch.beginAcquire(List.of(), entry.participants);
            if (!entry.participantCleanupCapture.advance(work)) return false;
            entry.participantCleanup = entry.participantCleanupCapture.result();
            entry.participantCleanupCapture = null;
        }
        var cleanup = entry.participantCleanup;
        try {
            boolean resolved = cleanup.decision() == ResourceBatch.Decision.OPEN ? cleanup.rollbackWithinBudget(work) : cleanup.resolveBusinessWithinBudget(work);
            if (!resolved || !cleanup.readyToFinish()) {
                workYielded |= cleanup.workYielded();
                return false;
            }
            if (!cleanup.finishWithinBudget(work)) {
                workYielded |= cleanup.workYielded();
                return false;
            }
        } catch (RuntimeException | Error failure) {
            record(failure);
            return false;
        }
        return cleanup.finished();
    }

    private boolean cleanRound(PlannedDeadline cleanupDeadline) {
        if (cleanupRoundCursor == -2) cleanupRoundCursor = round.size() - 1;
        while (cleanupRoundCursor >= 0) {
            if (!cleanupRoundSelected) {
                if (!takeOwnerWork()) return false;
                cleanupRoundSelected = true;
            }
            var entry = round.get(cleanupRoundCursor);
            if (!entry.stage.settled()) {
                if (!entry.acquired) entry.stage.discard();
                else {
                    if (!drivePreparedCleanup(entry.stage, true, cleanupDeadline)) return false;
                    if (!entry.stage.finishCapturedInventoryWithinBudget(work)) {
                        workYielded |= work.remaining() == 0;
                        return false;
                    }
                    if (entry.participantStarted && captureParticipants(entry) == BudgetResult.YIELDED) return false;
                    if (!entry.stage.transferable()) return false;
                    cleanupTransferable.add(entry);
                }
            }
            cleanupRoundCursor--;
            cleanupRoundSelected = false;
        }
        var transferred = cleanupTransferable.reversed();
        cleanupTransferable = new ObjectArrayList<>();
        cleanupRoundCursor = -2;
        if (!transferred.isEmpty()) return bind(transferred, true);
        clearRound();
        return true;
    }

    private @Nullable ObjectArrayList<Entry> unresolved() {
        if (unresolvedEntries == null) unresolvedEntries = new ObjectArrayList<>();
        while (unresolvedCursor < round.size()) {
            if (!takeOwnerWork()) return null;
            var entry = round.get(unresolvedCursor++);
            if (!entry.stage.settled()) unresolvedEntries.add(entry);
        }
        return unresolvedEntries;
    }

    private void clearRound() {
        rejectedCohorts.clear();
        round = new ObjectArrayList<>();
        acquisitionCursor = preparationCursor = 0;
        unresolvedEntries = null;
        unresolvedCursor = 0;
        cleanupRoundCursor = -2;
        cleanupRoundSelected = false;
        cleanupTransferable = new ObjectArrayList<>();
        preparedComputations = new ReferenceOpenHashSet<>();
        prepareInspectionReady = false;
        if (preparing == null) resetPrepareCapture();
    }

    @SuppressWarnings("DataFlowIssue")
    private boolean settleActive(PlannedDeadline cleanupDeadline) {
        var active = settlement;
        var resources = active.resources;
        if (resources.finished()) {
            return finishSettlement(active);
        }
        if (active.barrier != null || active.afterSources) {
            if (active.barrier != null && !active.barrier.quiescent()) {
                if (active.barrier.stopReason() != null) return false;
                active.barrier.awaitQuiescence();
            }
            if (!active.afterSources) {
                var results = active.barrier.results();
                while (active.receipt < results.size()) {
                    int index = active.receipt++;
                    if (!active.receipts.get(index).take())
                        throw new IllegalStateException("Source receipt credit was spent before physical exit");
                    var result = results.get(index);
                    receivedWorkerExit = true;
                    if (result instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                        active.successful = false;
                        record(cause);
                    } else if (result instanceof PlannedTaskExecutor.Stopped<Boolean> stopped) {
                        stop(stopped.reason());
                        active.successful = false;
                    } else if (result instanceof PlannedTaskExecutor.Success<Boolean>(var accepted) && !accepted) {
                        if (active.submitted.get(index).workYielded()) workYielded = true;
                        else {
                            active.successful = false;
                            if (!active.resolving) {
                                stop(Reason.RESERVATION_CONFLICT);
                            }
                        }
                    }
                }
                while (active.reclaimed < active.slices.size()) {
                    int index = active.reclaimed++;
                    if (!active.receipts.get(index).take())
                        throw new IllegalStateException("Source reclaim credit was spent before physical exit");
                    work.reclaim(active.slices.get(index));
                    work.reclaim(active.receipts.get(index));
                }
                active.resetDispatch();
                active.barrier = null;
                active.afterSources = true;
            }
            while (active.applyCheck < resources.sources().size()) {
                if (!takeOwnerWork()) return false;
                active.allApplied &= resources.sources().get(active.applyCheck++).state() == ResourceBatch.SourceState.APPLIED;
            }
            active.afterSources = false;
            active.applyCheck = 0;
            if (active.resolving) {
                if (active.rewinding != null && resources.decision() == ResourceBatch.Decision.OPEN) {
                    if (!forwardAllowed()) {
                        active.rollback = true;
                    } else {
                        if (active.rewinding.finishAlternativeRollbackWithinBudget(resources, work)) {
                            active.rewinding = null;
                            active.rewindSearch = 0;
                            active.inspected = false;
                            active.staged = true;
                            active.applied = true;
                            return true;
                        }
                        return false;
                    }
                } else {
                    if (resources.readyToFinish()) {
                        return finishSettlement(active);
                    }
                    return false;
                }
            }
            active.applied = active.successful && active.allApplied;
            active.allApplied = true;
            if (!active.successful) {
                active.rollback = true;
            }
            active.successful = true;
        }

        if (resources.acquiringNext()) {
            var stages = active.stages;
            while (!active.cleanupVerified && active.cleanupEntry < active.entries.size()) {
                if (!active.cleanupSelected) {
                    if (!takeOwnerWork()) return false;
                    active.cleanupSelected = true;
                }
                var entry = active.entries.get(active.cleanupEntry);
                if (!drivePreparedCleanup(entry.stage, active.rollback || !forwardAllowed(), cleanupDeadline))
                    return false;
                active.cleanupSelected = false;
                active.cleanupEntry++;
            }
            active.cleanupVerified = true;
            if (active.rollback || !forwardAllowed()) {
                if (!RecipeProcess.Stage.adoptNextBatchWithinBudget(stages, resources, true, work)) {
                    workYielded |= work.remaining() == 0;
                    return false;
                }
                active.rollback = true;
            } else {
                if (!prepareContinuations(stages)) {
                    return false;
                }
                if (!forwardAllowed()) {
                    active.rollback = true;
                    return true;
                }
                if (work.remaining() == 0) {
                    workYielded = true;
                    return false;
                }
                try {
                    active.rollback = !RecipeProcess.Stage.acquireNextBatchWithinBudget(stages, resources, work);
                    prepareInspectionReady = false;
                    active.cleanupEntry = 0;
                    active.cleanupVerified = false;
                    if (RecipeProcess.Stage.nextBatchPending(stages)) {
                        workYielded |= work.remaining() == 0;
                        return false;
                    }
                    workYielded |= work.remaining() == 0;
                } catch (RuntimeException | Error acquisitionFailure) {
                    prepareInspectionReady = false;
                    active.cleanupEntry = 0;
                    active.cleanupVerified = false;
                    active.rollback = true;
                    active.applied = false;
                    active.staged = false;
                    active.inspected = false;
                    record(acquisitionFailure);
                }
                active.applied = false;
                active.staged = false;
                active.inspected = false;
                return true;
            }
        }

        if (resources.decision() == ResourceBatch.Decision.OPEN) {
            if (active.rollback || !forwardAllowed()) {
                if (cleanupDeadline.expired(nanoTime.getAsLong())) {
                    return false;
                }
                try {
                    if (!resources.rollbackWithinBudget(work)) {
                        workYielded |= resources.workYielded();
                        return false;
                    }
                } catch (RuntimeException | Error rollback) {
                    record(rollback);
                }
            } else if (!inspectStages(active)) {
                return false;
            } else if (active.rewinding != null || active.rejected) {
                return rewindAlternative(active, cleanupDeadline);
            } else if (!active.applied) {
                if (work.remaining() == 0) {
                    workYielded = true;
                    return false;
                }
                dispatch(active, false, forwardDeadline);
                return true;
            } else {
                try {
                    boolean more = active.more;
                    if (more) {
                        if (active.staged || resources.stageWithinBudget(this::forwardAllowed, work)) {
                            active.staged = true;
                            var stages = active.stages;
                            if (!prepareContinuations(stages)) {
                                return false;
                            }
                            if (!forwardAllowed()) {
                                active.rollback = true;
                                return true;
                            }
                            if (work.remaining() == 0) {
                                workYielded = true;
                                return false;
                            }
                            active.rollback = !RecipeProcess.Stage.acquireNextBatchWithinBudget(stages, resources, work);
                            prepareInspectionReady = false;
                            active.cleanupEntry = 0;
                            active.cleanupVerified = false;
                            if (RecipeProcess.Stage.nextBatchPending(stages)) {
                                if (resources.acquiringNext()) {
                                    active.applied = false;
                                    active.staged = false;
                                }
                                active.inspected = false;
                                workYielded |= work.remaining() == 0;
                                return false;
                            }
                            preparedComputations = new ReferenceOpenHashSet<>();
                            active.applied = false;
                            active.staged = false;
                            active.inspected = false;
                            return true;
                        }
                        if (resources.workYielded()) {
                            workYielded = true;
                            return false;
                        }
                    } else {
                        if (!resources.commitWithinBudget(this::forwardAllowed, work)
                            && resources.decision() != ResourceBatch.Decision.ROLLBACK) {
                            workYielded |= resources.workYielded();
                            return false;
                        }
                    }
                } catch (RuntimeException | Error commit) {
                    prepareInspectionReady = false;
                    active.cleanupEntry = 0;
                    active.cleanupVerified = false;
                    record(commit);
                }
                if (resources.decision() == ResourceBatch.Decision.ROLLBACK) {
                    if (cleanupDeadline.expired(nanoTime.getAsLong())) {
                        return false;
                    }
                    try {
                        if (!resources.rollbackWithinBudget(work)) {
                            workYielded |= resources.workYielded();
                            return false;
                        }
                    } catch (RuntimeException | Error rollback) {
                        record(rollback);
                    }
                }
            }
        } else {
            if (resources.readyToFinish()) {
                return finishSettlement(active);
            }
            if (cleanupDeadline.expired(nanoTime.getAsLong())) {
                return false;
            }
            try {
                if (!resources.resolveBusinessWithinBudget(work)) {
                    workYielded |= resources.workYielded();
                    return false;
                }
            } catch (RuntimeException | Error confirmation) {
                record(confirmation);
            }
        }
        if (resources.readyToFinish()) {
            return finishSettlement(active);
        }
        if (cleanupDeadline.expired(nanoTime.getAsLong())) {
            return false;
        }
        if (work.remaining() == 0) {
            workYielded = true;
            return false;
        }
        dispatch(active, true, cleanupDeadline);
        return true;
    }

    private boolean inspectStages(Settlement active) {
        if (active.inspected) return true;
        if (active.inspect == 0) {
            active.more = false;
            active.rejected = false;
        }
        while (active.inspect < active.entries.size()) {
            if (!takeOwnerWork()) return false;
            var stage = active.entries.get(active.inspect++).stage;
            active.more |= stage.hasContinuation();
            active.rejected |= stage.hasRejectedAlternative();
        }
        active.inspected = true;
        active.inspect = 0;
        return true;
    }

    /**
     * Original prepared acquisition cleanup, using the same source lanes and physical receipts.
     */
    @SuppressWarnings("DataFlowIssue")
    private boolean drivePreparedCleanup(RecipeProcess.Stage stage, boolean cancel, PlannedDeadline deadline) {
        var active = preparedCleanup;
        if (active != null && active.stage != stage) return false;
        if (active == null) {
            if (stage.advancePreparedCleanup(work, cancel)) return true;
            active = new PreparedCleanup(stage, cancel);
            preparedCleanup = active;
        }
        active.cancel |= cancel;
        while (true) {
            if (active.barrier != null) {
                if (!active.barrier.quiescent()) {
                    if (active.barrier.stopReason() != null) return false;
                    active.barrier.awaitQuiescence();
                }
                var results = active.barrier.results();
                boolean unknown = false;
                while (active.receipt < results.size()) {
                    int index = active.receipt++;
                    if (!active.receipts.get(index).take())
                        throw new IllegalStateException("Prepared source receipt credit was spent before physical exit");
                    var result = results.get(index);
                    receivedWorkerExit = true;
                    boolean resolved = false;
                    if (result instanceof PlannedTaskExecutor.Success<Boolean>(var accepted)) {
                        resolved = accepted;
                        unknown |= !accepted && !active.submitted.get(index).workYielded();
                    } else if (result instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                        unknown = true;
                        record(cause);
                    } else if (result instanceof PlannedTaskExecutor.Stopped<Boolean> stopped) {
                        unknown = true;
                        stop(stopped.reason());
                    }
                    stage.finishPreparedCleanupSource(active.submitted.get(index), resolved);
                }
                while (active.reclaimed < active.slices.size()) {
                    int index = active.reclaimed++;
                    if (!active.receipts.get(index).take())
                        throw new IllegalStateException("Prepared source reclaim credit was spent before physical exit");
                    work.reclaim(active.slices.get(index));
                    work.reclaim(active.receipts.get(index));
                }
                active.barrier = null;
                active.sources = null;
                active.jobs = new ObjectArrayList<>();
                active.submitted = new ObjectArrayList<>();
                active.slices = new ObjectArrayList<>();
                active.receipts = new ObjectArrayList<>();
                active.source = active.receipt = active.reclaimed = 0;
                if (unknown) return false;
            }
            if (active.sources == null) {
                long allowance = work.remaining();
                if (stage.advancePreparedCleanup(work, active.cancel)) {
                    preparedCleanup = null;
                    return true;
                }
                active.sources = stage.preparedCleanupSources();
                if (active.sources.isEmpty()) {
                    active.sources = null;
                    if (work.remaining() > 0 && work.remaining() < allowance && !deadline.expired(nanoTime.getAsLong()))
                        continue;
                    workYielded |= work.remaining() == 0;
                    return false;
                }
            }
            while (active.source < active.sources.size() || active.pendingSource != null) {
                if (active.pendingSource == null) {
                    if (!takeOwnerWork()) break;
                    var source = active.sources.get(active.source++);
                    if (source.state() == ResourceBatch.SourceState.COMPLETED || source.state() == ResourceBatch.SourceState.ROLLED_BACK)
                        continue;
                    active.pendingSource = source;
                }
                active.pendingReceipt.reclaim(work.allocate(2 - active.pendingReceipt.remaining()));
                if (active.pendingReceipt.remaining() < 2) break;
                if (active.pendingSlice == null) {
                    if (work.remaining() == 0) break;
                    active.pendingSlice = work.allocate(Math.max(1, work.remaining() / Math.max(1, active.sources.size() - active.source + 1)));
                }
                var source = active.pendingSource;
                var slice = active.pendingSlice;
                active.slices.add(slice);
                active.receipts.add(active.pendingReceipt);
                active.submitted.add(source);
                active.jobs.add(new PlannedTaskExecutor.SourceJob<>(source.affinity(), new PlannedTaskExecutor.Job<>(null, () -> source.resolveWithinBudget(slice))));
                active.pendingSource = null;
                active.pendingSlice = null;
                active.pendingReceipt = new RecipeWorkBudget(0);
            }
            if (active.jobs.isEmpty()) {
                if (active.pendingSource == null && active.source == active.sources.size()) {
                    active.sources = null;
                    active.source = 0;
                    if (work.remaining() > 0 && !deadline.expired(nanoTime.getAsLong())) continue;
                }
                workYielded |= work.remaining() == 0;
                return false;
            }
            if (active.pendingSource != null) {
                workYielded |= work.remaining() == 0;
                return false;
            }
            if (deadline.expired(nanoTime.getAsLong())) return false;
            var captured = active;
            boolean interrupted = Thread.interrupted();
            try {
                tasks.runOnSourceLanes(active.jobs, deadline, barrier -> captured.barrier = barrier);
            } catch (RuntimeException | Error failure) {
                record(failure);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (active.barrier == null || unreported != null) return false;
        }
    }

    private boolean prepareContinuations(List<RecipeProcess.Stage> stages) {
        if (preparing != null) return finishPrepare();
        if (prepareInspectionReady) return true;
        if (prepareInput == null) prepareInput = stages;
        while (prepareCapture < prepareInput.size() || pendingComputation != null) {
            if (pendingComputation == null) {
                if (!prepareOwnerSelected) {
                    if (!takeOwnerWork()) break;
                    prepareOwnerSelected = true;
                }
                var stage = prepareInput.get(prepareCapture);
                if (stage.preparedCleanupPending() && !drivePreparedCleanup(stage, false, activeCleanupDeadline))
                    return false;
                var computation = stage.computation();
                if (computation == null && stage.preparationPending()) {
                    if (prepareCapture > 0 && !stage.captureOnlyPreparation()) {
                        prepareCapture++;
                        prepareOwnerSelected = false;
                        continue;
                    }
                    if (!forwardAllowed() || work.remaining() == 0) {
                        workYielded |= work.remaining() == 0;
                        return false;
                    }
                    long allowance = work.remaining();
                    try {
                        if (settlement == null) stage.resumePreparedWithinBudget(work);
                        else if (!settlement.resources.accessWithinBudget(() -> stage.resumePreparedWithinBudget(work))) {
                            workYielded |= settlement.resources.workYielded();
                            return false;
                        }
                    } catch (RuntimeException | Error failure) {
                        record(failure);
                        return true;
                    }
                    if (stage.preparationPending() && stage.computation() == null && work.remaining() == allowance)
                        return false;
                    continue;
                }
                prepareCapture++;
                prepareOwnerSelected = false;
                if (computation == null || preparedComputations.contains(computation)) continue;
                pendingComputation = computation;
                pendingPrepareOwner = stage;
                stage.withdrawPreparedFacts(computation);
            }
            pendingPrepareReceipt.reclaim(work.allocate(2 - pendingPrepareReceipt.remaining()));
            if (pendingPrepareReceipt.remaining() < 2) break;
            if (pendingPrepareSlice == null) {
                if (work.remaining() == 0) break;
                pendingPrepareSlice = work.allocate(Math.max(1, work.remaining() / Math.max(1, prepareInput.size() - prepareCapture + 1)));
            }
            var computation = pendingComputation;
            var slice = pendingPrepareSlice;
            prepareRound.add(computation);
            prepareOwners.add(pendingPrepareOwner);
            prepareSlices.add(slice);
            prepareReceipts.add(pendingPrepareReceipt);
            prepareJobs.add(new PlannedTaskExecutor.Job<>(cancellation, () -> computation.advance(slice)));
            pendingComputation = null;
            pendingPrepareOwner = null;
            pendingPrepareSlice = null;
            pendingPrepareReceipt = new RecipeWorkBudget(0);
        }
        if (prepareJobs.isEmpty()) {
            if (pendingComputation == null && prepareCapture == prepareInput.size()) {
                resetPrepareCapture();
                prepareInspectionReady = true;
                return true;
            }
            workYielded |= work.remaining() == 0;
            return false;
        }
        if (pendingComputation != null || prepareCapture < prepareInput.size()) {
            workYielded |= work.remaining() == 0;
            return false;
        }
        try {
            tasks.runPrepare(prepareJobs, forwardDeadline, batch -> preparing = batch);
        } catch (RuntimeException | Error failure) {
            record(failure);
        }
        return finishPrepare();
    }

    private void resetPrepareCapture() {
        prepareInput = null;
        pendingComputation = null;
        pendingPrepareOwner = null;
        pendingPrepareSlice = null;
        pendingPrepareReceipt = new RecipeWorkBudget(0);
        prepareCapture = prepareReceipt = prepareReclaimed = 0;
        prepareOwnerSelected = false;
        prepareSlices = new ObjectArrayList<>();
        prepareReceipts = new ObjectArrayList<>();
        prepareOwners = new ObjectArrayList<>();
        prepareJobs = new ObjectArrayList<>();
        prepareRound = new ObjectArrayList<>();
        prepareCompleted = true;
    }

    @SuppressWarnings("DataFlowIssue")
    private boolean finishPrepare() {
        var barrier = preparing;
        if (barrier == null) return stopReason == null;
        if (!barrier.quiescent()) {
            if (barrier.stopReason() != null) return false;
            barrier.awaitQuiescence();
        }
        var results = barrier.results();
        while (prepareReceipt < results.size()) {
            int index = prepareReceipt++;
            if (!prepareReceipts.get(index).take())
                throw new IllegalStateException("Pure prepare receipt credit was spent before physical exit");
            var result = results.get(index);
            receivedWorkerExit = true;
            if (result instanceof PlannedTaskExecutor.Success<Boolean>(var ready)) {
                try {
                    prepareOwners.get(index).retainPreparedFacts(prepareRound.get(index));
                } catch (RuntimeException | Error failure) {
                    prepareCompleted = false;
                    record(failure);
                }
                prepareCompleted &= ready;
                if (ready) preparedComputations.add(prepareRound.get(index));
            } else if (result instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                prepareCompleted = false;
                record(cause);
            } else if (result instanceof PlannedTaskExecutor.Stopped<Boolean> stopped) {
                stop(stopped.reason());
                prepareCompleted = false;
            }
        }
        while (prepareReclaimed < prepareSlices.size()) {
            int index = prepareReclaimed++;
            if (!prepareReceipts.get(index).take())
                throw new IllegalStateException("Pure prepare reclaim credit was spent before physical exit");
            work.reclaim(prepareSlices.get(index));
            work.reclaim(prepareReceipts.get(index));
        }
        boolean completed = prepareCompleted;
        preparing = null;
        resetPrepareCapture();
        prepareInspectionReady = completed;
        if (!completed && stopReason == null && failure == null) workYielded = true;
        return completed || stopReason != null;
    }

    @SuppressWarnings("DataFlowIssue")
    private boolean rewindAlternative(Settlement active, PlannedDeadline deadline) {
        if (deadline.expired(nanoTime.getAsLong())) {
            return false;
        }
        var resources = active.resources;
        if (active.rewinding == null) {
            while (active.rewindSearch < active.entries.size()) {
                if (!takeOwnerWork()) return false;
                var entry = active.entries.get(active.rewindSearch++);
                if (entry.stage.hasRejectedAlternative()) {
                    active.rewinding = entry.stage;
                    break;
                }
            }
        }
        var stage = active.rewinding;
        try {
            if (!resources.rewinding()) {
                if (!resources.rollbackToWithinBudget(stage.rejectedSavepoint(), work)) {
                    workYielded |= resources.workYielded();
                    return false;
                }
            } else {
                if (!resources.resolveBusinessWithinBudget(work)) {
                    workYielded |= resources.workYielded();
                    return false;
                }
            }
            if (stage.finishAlternativeRollbackWithinBudget(resources, work)) {
                active.rewinding = null;
                active.rewindSearch = 0;
                active.inspected = false;
                active.staged = true;
                active.applied = true;
                return true;
            }
        } catch (RuntimeException | Error failure) {
            active.rollback = true;
            record(failure);
            return true;
        }
        dispatch(active, true, deadline);
        return true;
    }

    private void dispatch(Settlement active, boolean resolving, PlannedDeadline deadline) {
        if (active.collecting && active.resolving != resolving) {
            while (active.reclaimed < active.slices.size()) {
                int index = active.reclaimed++;
                if (!active.receipts.get(index).take())
                    throw new IllegalStateException("Unsubmitted source reclaim credit was spent");
                work.reclaim(active.slices.get(index));
                work.reclaim(active.receipts.get(index));
            }
            if (active.pendingSlice != null) work.reclaim(active.pendingSlice);
            work.reclaim(active.pendingReceipt);
            active.pendingSource = null;
            active.pendingSlice = null;
            active.pendingReceipt = new RecipeWorkBudget(0);
            active.resetDispatch();
        }
        active.resolving = resolving;
        active.collecting = true;
        var sources = active.resources.sources();
        while (active.source < sources.size() || active.pendingSource != null) {
            if (active.pendingSource == null) {
                if (!takeOwnerWork()) break;
                var source = sources.get(active.source++);
                if (!resolving && source.state() != ResourceBatch.SourceState.RESERVED || resolving && (source.state() == ResourceBatch.SourceState.COMPLETED || source.state() == ResourceBatch.SourceState.ROLLED_BACK)
                    && (active.resources.rewinding() || source.fullySettled())) continue;
                active.pendingSource = source;
            }
            active.pendingReceipt.reclaim(work.allocate(2 - active.pendingReceipt.remaining()));
            if (active.pendingReceipt.remaining() < 2) break;
            if (active.pendingSlice == null) {
                if (work.remaining() == 0) break;
                active.pendingSlice = work.allocate(Math.max(1, work.remaining() / Math.max(1, sources.size() - active.source + 1)));
            }
            var source = active.pendingSource;
            var slice = active.pendingSlice;
            active.slices.add(slice);
            active.receipts.add(active.pendingReceipt);
            active.submitted.add(source);
            active.jobs.add(new PlannedTaskExecutor.SourceJob<>(source.affinity(), new PlannedTaskExecutor.Job<>(
                resolving ? null : cancellation, resolving ? () -> source.resolveWithinBudget(slice) : () -> source.applyWithinBudget(slice))));
            active.pendingSource = null;
            active.pendingSlice = null;
            active.pendingReceipt = new RecipeWorkBudget(0);
        }
        if (active.jobs.isEmpty()) {
            if (active.pendingSource == null && active.source == sources.size()) {
                // Fully classified empty work still needs the original charged source-state proof.
                active.resetDispatch();
                active.afterSources = true;
            } else workYielded |= work.remaining() == 0;
            return;
        }
        if (active.pendingSource != null) {
            workYielded |= work.remaining() == 0;
            return;
        }
        if (deadline.expired(nanoTime.getAsLong())) return;
        boolean interrupted = resolving && Thread.interrupted();
        try {
            tasks.runOnSourceLanes(active.jobs, deadline, barrier -> active.barrier = barrier);
            active.collecting = false;
        } catch (RuntimeException | Error dispatchFailure) {
            active.rollback |= !resolving;
            record(dispatchFailure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean finishSettlement(Settlement active) {
        if (!active.resources.finished()) {
            try {
                if (!active.resources.finishWithinBudget(work)) {
                    workYielded |= active.resources.workYielded();
                    return false;
                }
            } catch (RuntimeException | Error notification) {
                record(notification);
            }
        }
        if (!active.resources.finished()) {
            return false;
        }
        while (active.acknowledge < active.entries.size()) {
            if (!active.acknowledgeSelected) {
                if (!takeOwnerWork()) return false;
                active.acknowledgeSelected = true;
            }
            var entry = active.entries.get(active.acknowledge);
            if (entry.stage.settled()) {
                active.acknowledge++;
                active.acknowledgeSelected = false;
                continue;
            }
            try {
                entry.stage.settleBatch(failure);
            } catch (RuntimeException | Error acknowledgement) {
                record(acknowledgement);
            }
            if (!entry.stage.settled()) return false;
            active.acknowledge++;
            active.acknowledgeSelected = false;
        }
        settlement = null;
        unresolvedEntries = null;
        unresolvedCursor = 0;
        return true;
    }

    @SuppressWarnings("DataFlowIssue")
    private void finishGroup() {
        if (group != null) {
            try {
                group.tryFinish();
            } catch (RuntimeException | Error post) {
                record(post);
            }
            return;
        }
        var session = processSession;
        if (session == null) {
            processFinished = true;
            return;
        }
        if (session.currentStage() != null) {
            return;
        }
        try {
            session.finish();
        } catch (RuntimeException | Error post) {
            record(post);
        } finally {
            processFinished = process.quiescent();
        }
    }

    private void stop(Reason reason) {
        if (stopReason == null) {
            stopReason = reason;
        }
        if (group != null && !group.finished()) {
            group.stop();
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void record(Throwable observed) {
        if (failure == null) {
            failure = observed;
        } else if (failure != observed) {
            failure.addSuppressed(observed);
        }
        if (unreported == null) {
            unreported = observed;
        } else if (unreported != observed && unreported != failure) {
            unreported.addSuppressed(observed);
        }
        stop(Reason.WORKER_FAILURE);
        if (group != null && !group.finished()) {
            group.recordFailure(observed);
        } else if (processSession != null && process.pendingTick() == processSession) {
            processSession.recordFailure(observed);
        }
        if (observed instanceof VirtualMachineError fatal) throw fatal;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner || executing) {
            throw new IllegalStateException("Recipe coordinator requires its idle owner thread");
        }
    }

    private static final class Entry {
        private final RecipeProcess.Stage stage;
        private final ObjectArrayList<ReversibleTransaction> participants = new ObjectArrayList<>();
        private final ReferenceOpenHashSet<ReversibleTransaction> participantIdentities = new ReferenceOpenHashSet<>();
        @Nullable
        private ResourceBatch.Acquire participantCleanupCapture;
        @Nullable
        private ResourceBatch participantCleanup;
        private boolean acquired;
        private boolean participantStarted;
        private boolean participantUnknown;
        private BudgetResult participantAccepted = BudgetResult.YIELDED;
        @Nullable
        private Iterator<? extends ReversibleTransaction> participantInput;

        private Entry(RecipeProcess.Stage stage) {
            this.stage = stage;
        }
    }

    private static final class Binding {
        private final List<Entry> entries;
        private final ObjectArrayList<RecipeProcess.Stage> stages = new ObjectArrayList<>();
        private final ObjectArrayList<ReversibleTransaction> additional = new ObjectArrayList<>();
        @Nullable
        private RecipeProcess.Stage.BatchCapture capture;
        private boolean rollback;
        private boolean failedForwardClaim;
        private boolean omitAdditional;
        private int cleanupParticipantEntry;
        private int entry;
        private int participant;
        private boolean selected;

        private Binding(List<Entry> entries, boolean rollback) {
            this.entries = entries;
            this.rollback = rollback;
        }
    }

    private static final class PreparedCleanup {
        private final RecipeProcess.Stage stage;
        private boolean cancel;
        @Nullable
        private List<ResourceBatch.Source> sources;
        private ObjectArrayList<ResourceBatch.Source> submitted = new ObjectArrayList<>();
        private ObjectArrayList<RecipeWorkBudget> slices = new ObjectArrayList<>();
        private ObjectArrayList<RecipeWorkBudget> receipts = new ObjectArrayList<>();
        @Nullable
        private ResourceBatch.Source pendingSource;
        @Nullable
        private RecipeWorkBudget pendingSlice;
        private RecipeWorkBudget pendingReceipt = new RecipeWorkBudget(0);
        private ObjectArrayList<PlannedTaskExecutor.SourceJob<Boolean>> jobs = new ObjectArrayList<>();
        @Nullable
        private PlannedTaskExecutor.Batch<Boolean> barrier;
        private int source;
        private int receipt;
        private int reclaimed;

        private PreparedCleanup(RecipeProcess.Stage stage, boolean cancel) {
            this.stage = stage;
            this.cancel = cancel;
        }
    }

    private static final class Settlement {
        private final ResourceBatch resources;
        private final List<Entry> entries;
        private final List<RecipeProcess.Stage> stages;
        private int cleanupEntry;
        private boolean cleanupSelected;
        private boolean cleanupVerified;
        private int rewindSearch;
        private int acknowledge;
        private boolean acknowledgeSelected;
        private int inspect;
        private boolean inspected;
        private boolean more;
        private boolean rejected;
        private ObjectArrayList<RecipeWorkBudget> slices = new ObjectArrayList<>();
        private ObjectArrayList<RecipeWorkBudget> receipts = new ObjectArrayList<>();
        private ObjectArrayList<ResourceBatch.Source> submitted = new ObjectArrayList<>();
        private ObjectArrayList<PlannedTaskExecutor.SourceJob<Boolean>> jobs = new ObjectArrayList<>();
        @Nullable
        private ResourceBatch.Source pendingSource;
        @Nullable
        private RecipeWorkBudget pendingSlice;
        private RecipeWorkBudget pendingReceipt = new RecipeWorkBudget(0);
        private int source;
        private int receipt;
        private int reclaimed;
        private int applyCheck;
        private boolean collecting;
        private boolean afterSources;
        private boolean allApplied = true;
        private boolean successful = true;
        @Nullable
        private PlannedTaskExecutor.Batch<Boolean> barrier;
        private boolean rollback;
        private boolean applied;
        private boolean staged;
        @Nullable
        private RecipeProcess.Stage rewinding;
        private boolean resolving;

        private Settlement(ResourceBatch resources, List<Entry> entries, boolean rollback) {
            this.resources = resources;
            this.entries = entries;
            stages = stageView(entries, 0);
            this.rollback = rollback;
        }

        private void resetDispatch() {
            slices = new ObjectArrayList<>();
            receipts = new ObjectArrayList<>();
            submitted = new ObjectArrayList<>();
            jobs = new ObjectArrayList<>();
            source = receipt = reclaimed = 0;
            collecting = false;
        }
    }
}
