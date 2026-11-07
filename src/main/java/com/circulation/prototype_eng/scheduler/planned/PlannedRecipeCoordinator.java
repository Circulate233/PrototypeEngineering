package com.circulation.prototype_eng.scheduler.planned;

import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;

/**
 * One captured multi-machine tick, driven through real recipe stages and original source lanes.
 * The caller retains this coordinator until finished, including after a thrown advance. Capture,
 * requirement callbacks and business settlement run on the owner; only physical source actions are
 * dispatched. This does not manufacture a pure prepare kernel from mutable legacy callbacks.
 */
public final class PlannedRecipeCoordinator implements PlannedExecution {
    private final Thread owner = Thread.currentThread();
    private final PlannedTaskExecutor tasks;
    @Nullable private final MachineWorkSlots.TickGroup group;
    @Nullable private final RecipeProcess process;
    @Nullable private final Function<RecipeProcess.Stage, List<? extends ReversibleTransaction>> participants;
    @Nullable private RecipeProcess.TickSession processSession;
    private boolean processFinished;
    private final PlannedDeadline forwardDeadline;
    private final PlannedCancellationToken cancellation;
    private final BooleanSupplier authorization;
    private final LongSupplier nanoTime;
    private final ObjectArrayList<Entry> round = new ObjectArrayList<>();
    private long workPerAdvance = 16_384;
    private RecipeWorkBudget work = new RecipeWorkBudget(0);
    @Nullable
    private PlannedTaskExecutor.Batch<Boolean> preparing;
    private final ReferenceOpenHashSet<RecipePureComputation> preparedComputations = new ReferenceOpenHashSet<>();
    private final ObjectArrayList<RecipeWorkBudget> prepareSlices = new ObjectArrayList<>();
    private final ObjectArrayList<RecipeProcess.Stage> prepareOwners = new ObjectArrayList<>();
    private int acquisitionCursor;
    private int preparationCursor;
    private boolean begun;
    private boolean executing;
    private boolean workYielded;
    @Nullable private Reason stopReason;
    @Nullable private Throwable failure;
    @Nullable private Throwable unreported;
    @Nullable private Settlement settlement;

    public PlannedRecipeCoordinator(PlannedTaskExecutor tasks, List<MachineWorkSlots> hosts,
                                    PlannedDeadline forwardDeadline, PlannedCancellationToken cancellation,
                                    BooleanSupplier authorization) {
        this(tasks, hosts, forwardDeadline, cancellation, authorization, System::nanoTime);
    }

    PlannedRecipeCoordinator(PlannedTaskExecutor tasks, List<MachineWorkSlots> hosts,
                             PlannedDeadline forwardDeadline, PlannedCancellationToken cancellation,
                             BooleanSupplier authorization, LongSupplier nanoTime) {
        this.tasks = Objects.requireNonNull(tasks);
        this.forwardDeadline = Objects.requireNonNull(forwardDeadline);
        this.cancellation = Objects.requireNonNull(cancellation);
        this.authorization = Objects.requireNonNull(authorization);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        group = MachineWorkSlots.prepareTickGroup(hosts);
        process = null;
        participants = null;
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

    private PlannedRecipeCoordinator(PlannedTaskExecutor tasks, RecipeProcess process,
            PlannedDeadline forwardDeadline, PlannedCancellationToken cancellation, BooleanSupplier authorization,
            Function<RecipeProcess.Stage, List<? extends ReversibleTransaction>> participants) {
        this.tasks = Objects.requireNonNull(tasks);
        this.process = Objects.requireNonNull(process);
        this.forwardDeadline = Objects.requireNonNull(forwardDeadline);
        this.cancellation = Objects.requireNonNull(cancellation);
        this.authorization = Objects.requireNonNull(authorization);
        this.participants = Objects.requireNonNull(participants);
        nanoTime = System::nanoTime;
        group = null;
        if (!process.isMaterialCycle() || process.host() != null) {
            throw new IllegalArgumentException("Detached coordination requires an unattached material cycle");
        }
        process.requirePersistenceBoundary();
    }

    /**
     * Runs until complete or accurately pending. A fresh cleanup deadline permits later confirmation,
     * never additional forward work after the original deadline/cancellation. New faults are thrown
     * after the available cleanup work; false is an ordinary pending state, not an exception signal.
     */
    @Override public boolean advance(PlannedDeadline cleanupDeadline) {
        requireOwner();
        Objects.requireNonNull(cleanupDeadline);
        if (finished()) { return true; }
        if (preparing == null && (settlement == null || settlement.barrier == null)) {
            work = new RecipeWorkBudget(workPerAdvance);
        }
        executing = true;
        workYielded = false;
        try {
            while (!finished()) {
                if (preparing != null) {
                    if (!finishPrepare()) {
                        break;
                    }
                }
                if (settlement != null) {
                    if (!settleActive(cleanupDeadline)) { break; }
                    continue;
                }
                if (!forwardAllowed()) {
                    if (!cleanRound(cleanupDeadline)) { break; }
                    if (settlement != null) { continue; }
                    finishGroup();
                    break;
                }
                if (!begun) {
                    begun = true;
                    try { beginProcesses(); }
                    catch (RuntimeException | Error beginFailure) { record(beginFailure); }
                    continue;
                }
                if (round.isEmpty()) {
                    try {
                        for (var stage : nextStages()) { round.add(new Entry(stage)); }
                    } catch (RuntimeException | Error captureFailure) {
                        record(captureFailure);
                        // nextRound can throw after creating earlier prepared leases.
                        for (var stage : currentStages()) { round.add(new Entry(stage)); }
                        continue;
                    }
                    acquisitionCursor = 0;
                    if (round.isEmpty()) { finishGroup(); break; }
                }
                if (acquisitionCursor < round.size()) {
                    if (!takeOwnerWork()) {
                        break;
                    }
                    var entry = round.get(acquisitionCursor++);
                    entry.acquired = true;
                    try { entry.stage.acquirePreparedWithinBudget(work); }
                    catch (RuntimeException | Error acquisitionFailure) { record(acquisitionFailure); continue; }
                    continue;
                }
                if (preparationCursor < round.size()) {
                    var entry = round.get(preparationCursor);
                    if (entry.stage.preparationPending()) {
                        var stages = new ObjectArrayList<RecipeProcess.Stage>();
                        for (int index = preparationCursor; index < round.size(); index++) {
                            stages.add(round.get(index).stage);
                        }
                        if (!prepareContinuations(stages) || !entry.stage.ownerWorkMetered() && !takeOwnerWork()) {
                            break;
                        }
                        try {
                            entry.stage.resumePreparedWithinBudget(work);
                            if (work.remaining() == 0 && entry.stage.preparationPending()) {
                                workYielded = true;
                                break;
                            }
                        } catch (RuntimeException | Error acquisitionFailure) {
                            record(acquisitionFailure);
                        }
                        continue;
                    }
                    preparationCursor++;
                    boolean accepted = entry.stage.result() == ProcessResult.SUCCESS;
                    if (accepted && participants != null) {
                        try { accepted = captureParticipants(entry); }
                        catch (RuntimeException | Error participantFailure) { record(participantFailure); continue; }
                    }
                    if (!accepted && !bind(List.of(entry), true)) { break;
                    }
                    continue;
                }
                var accepted = unresolved();
                if (!accepted.isEmpty()) {
                    if (!bind(accepted, false)) { break; }
                } else { clearRound(); }
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

    @Override public boolean finished() { return group != null ? group.finished() : processFinished; }

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

    private void beginProcesses() {
        if (group != null) { group.begin(); }
        else { processSession = Objects.requireNonNull(process).beginTick(); }
    }

    private List<RecipeProcess.Stage> nextStages() {
        if (group != null) { return group.nextRound(); }
        var session = processSession;
        var stage = session == null ? null : session.nextStage();
        return stage == null ? List.of() : List.of(stage);
    }

    private List<RecipeProcess.Stage> currentStages() {
        if (group != null) { return group.currentStages(); }
        var session = processSession;
        var stage = session == null ? null : session.currentStage();
        return stage == null ? List.of() : List.of(stage);
    }

    private boolean captureParticipants(Entry entry) {
        var captured = Objects.requireNonNull(participants).apply(entry.stage);
        if (captured == null) { return false; }
        var unique = new ReferenceOpenHashSet<ReversibleTransaction>();
        for (var transaction : captured) {
            Objects.requireNonNull(transaction, "Additional business acquisition");
            if (!unique.add(transaction)) { throw new IllegalArgumentException("Repeated additional business acquisition"); }
            entry.participants.add(transaction);
        }
        return true;
    }

    /** Accepted source actions require physical exit before another owner settlement attempt. */
    public boolean hasActiveSources() {
        requireOwner();
        return preparing != null || settlement != null && settlement.barrier != null;
    }

    @Override public void cancel(Reason reason) {
        requireOwner();
        cancellation.cancel(Objects.requireNonNull(reason));
    }
    @Nullable public Reason stopReason() { return stopReason; }
    @Nullable public Throwable failure() { return failure; }

    /**
     * Retirement waits for actual source action exit, not just timeout results. Ownership remains
     * captured afterwards; call advance with a cleanup budget to confirm resources and run post.
     */
    @Override public void awaitExit() {
        requireOwner();
        var current = settlement;
        if (preparing == null && (current == null || current.barrier == null)) { return; }
        executing = true;
        try {
            if (preparing != null) {
                preparing.awaitQuiescence();
            }
            if (current != null && current.barrier != null) {
                current.barrier.awaitQuiescence();
            }
        }
        catch (RuntimeException | Error interruption) {
            stop(Reason.INTERRUPTED);
            record(interruption);
            unreported = null;
            throw interruption;
        }
        finally { executing = false; }
    }

    private boolean forwardAllowed() {
        if (stopReason != null) { return false; }
        var cancelled = cancellation.reason();
        if (cancelled != null) { stop(cancelled); return false; }
        if (Thread.currentThread().isInterrupted()) { stop(Reason.INTERRUPTED); return false; }
        if (forwardDeadline.expired(nanoTime.getAsLong())) { stop(Reason.WORKER_TIMEOUT); return false; }
        if (!authorization.getAsBoolean()) { stop(Reason.OWNER_CHANGED); return false; }
        return true;
    }

    private boolean bind(List<Entry> entries, boolean rollback) {
        var stages = new ObjectArrayList<RecipeProcess.Stage>(entries.size());
        var additional = new ObjectArrayList<ReversibleTransaction>();
        for (var entry : entries) { stages.add(entry.stage); additional.addAll(entry.participants); }
        try {
            var resources = RecipeProcess.Stage.tryAcquireBatch(stages, additional);
            if (resources == null) { stop(Reason.RESERVATION_CONFLICT); return false; }
            settlement = new Settlement(resources, new ObjectArrayList<>(entries), rollback);
            return true;
        } catch (RuntimeException | Error bindingFailure) {
            // No successful transfer: exact acquisitions still belong to their stage handles.
            for (var entry : entries) { entry.localCleanup = true; }
            record(bindingFailure);
            return true;
        }
    }

    private boolean cleanRound(PlannedDeadline cleanupDeadline) {
        boolean pending = false;
        var transferable = new ObjectArrayList<Entry>();
        for (int index = round.size() - 1; index >= 0; index--) {
            var entry = round.get(index);
            if (entry.stage.settled()) { continue; }
            if (!entry.acquired) { entry.stage.discard(); continue; }
            if (!entry.localCleanup && entry.stage.transferable()) { transferable.add(entry); continue; }
            if (cleanupDeadline.expired(nanoTime.getAsLong())) { pending = true; continue; }
            entry.localCleanup = true;
            if (!cleanParticipants(entry)) { pending = true; continue; }
            try {
                if (!entry.cleanupStarted) { entry.cleanupStarted = true; entry.stage.rollbackSynchronously(); }
                else { entry.stage.resolveSynchronously(); }
            } catch (RuntimeException | Error cleanup) { record(cleanup); }
            pending |= !entry.stage.settled();
        }
        if (!transferable.isEmpty()) { return bind(transferable.reversed(), true); }
        if (!pending) { clearRound(); }
        return !pending;
    }

    /** Only a failed transfer takes this local path; successful binds retain the combined batch. */
    private boolean cleanParticipants(Entry entry) {
        if (entry.participants.isEmpty()) { return true; }
        if (entry.participantCleanup == null) {
            entry.participantCleanup = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), entry.participants));
        }
        var cleanup = entry.participantCleanup;
        if (cleanup.finished()) { return true; }
        try {
            if (cleanup.decision() == ResourceBatch.Decision.OPEN) { cleanup.rollback(); }
            else { cleanup.resolveBusiness(); }
            if (cleanup.readyToFinish()) { cleanup.finish(); }
        } catch (RuntimeException | Error failure) { record(failure); }
        return cleanup.finished();
    }

    private ObjectArrayList<Entry> unresolved() {
        var entries = new ObjectArrayList<Entry>();
        for (var entry : round) { if (!entry.stage.settled()) { entries.add(entry); } }
        return entries;
    }

    private void clearRound() { round.clear(); acquisitionCursor = 0;
        preparationCursor = 0;
        preparedComputations.clear();
    }

    private boolean settleActive(PlannedDeadline cleanupDeadline) {
        var active = Objects.requireNonNull(settlement);
        var resources = active.resources;
        if (resources.finished()) { return finishSettlement(active); }
        if (active.barrier != null) {
            if (!active.barrier.quiescent()) { return false; }
            boolean successful = true;
            for (var result : active.barrier.results()) {
                if (result instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                    record(cause);
                    successful = false;
                } else if (result instanceof PlannedTaskExecutor.Stopped<Boolean> stopped) {
                    stop(stopped.reason());
                    successful = false;
                } else if (result instanceof PlannedTaskExecutor.Success<Boolean>(var accepted) && !accepted) {
                    successful = false;
                    if (!active.resolving) { stop(Reason.RESERVATION_CONFLICT); }
                }
            }
            for (var slice : active.slices) {
                work.reclaim(slice);
            }
            active.slices.clear();
            active.barrier = null;
            if (active.resolving) {
                if (active.rewinding != null && resources.decision() == ResourceBatch.Decision.OPEN) {
                    if (!forwardAllowed()) { active.rollback = true; }
                    else {
                        if (active.rewinding.finishAlternativeRollback(resources)) {
                            active.rewinding = null;
                            active.staged = true;
                            active.applied = true;
                            return true;
                        }
                        return false;
                    }
                } else {
                    if (resources.readyToFinish()) { return finishSettlement(active); }
                    return false;
                }
            }
            active.applied = successful && resources.sources().stream()
                                                    .allMatch(source -> source.state() == ResourceBatch.SourceState.APPLIED);
            if (!successful) { active.rollback = true;
            }
        }

        if (resources.acquiringNext()) {
            var stages = new ObjectArrayList<RecipeProcess.Stage>();
            for (var entry : active.entries) {
                stages.add(entry.stage);
            }
            if (active.rollback || !forwardAllowed()) {
                if (!RecipeProcess.Stage.adoptNextBatch(stages, resources, true)) {
                    return false;
                }
                active.rollback = true;
            } else {
                if (!prepareContinuations(stages) || stages.stream().anyMatch(stage -> !stage.ownerWorkMetered()) && !takeOwnerWork()) {
                    return false;
                }
                try {
                    active.rollback = !RecipeProcess.Stage.acquireNextBatchWithinBudget(stages, resources, work);
                    if (work.remaining() == 0 && stages.stream().anyMatch(RecipeProcess.Stage::preparationPending)) {
                        workYielded = true;
                    }
                } catch (RuntimeException | Error acquisitionFailure) {
                    record(acquisitionFailure);
                    active.rollback = true;
                }
                active.applied = false;
                active.staged = false;
                return true; }
        }

        if (resources.decision() == ResourceBatch.Decision.OPEN) {
            if (active.rollback || !forwardAllowed()) {
                if (cleanupDeadline.expired(nanoTime.getAsLong())) { return false; }
                try { resources.rollback(); }
                catch (RuntimeException | Error rollback) { record(rollback); }
            } else if (active.rewinding != null || active.entries.stream().anyMatch(entry -> entry.stage.hasRejectedAlternative())) {
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
                    boolean more = active.entries.stream().anyMatch(entry -> entry.stage.hasContinuation());
                    if (more) {
                        if (active.staged || resources.stage(this::forwardAllowed)) {
                            active.staged = true;
                            var stages = new ObjectArrayList<RecipeProcess.Stage>(active.entries.size());
                            for (var entry : active.entries) { stages.add(entry.stage); }
                            if (!prepareContinuations(stages)) {
                                return false;
                            }
                            if (!takeOwnerWork()) {
                                return false;
                            }
                            active.rollback = !RecipeProcess.Stage.acquireNextBatchWithinBudget(stages, resources, work);
                            preparedComputations.clear();
                            active.applied = false;
                            active.staged = false;
                            return true;
                        }
                    } else { resources.commit(this::forwardAllowed); }
                } catch (RuntimeException | Error commit) { record(commit); }
                if (resources.decision() == ResourceBatch.Decision.ROLLBACK) {
                    if (cleanupDeadline.expired(nanoTime.getAsLong())) { return false; }
                    try { resources.rollback(); }
                    catch (RuntimeException | Error rollback) { record(rollback); }
                }
            }
        } else {
            if (resources.readyToFinish()) { return finishSettlement(active); }
            if (cleanupDeadline.expired(nanoTime.getAsLong())) { return false; }
            try { resources.resolveBusiness(); }
            catch (RuntimeException | Error confirmation) { record(confirmation); }
        }
        if (resources.readyToFinish()) { return finishSettlement(active); }
        if (cleanupDeadline.expired(nanoTime.getAsLong())) { return false; }
        var pendingSources = resources.sources().stream().anyMatch(source ->
            source.state() != ResourceBatch.SourceState.COMPLETED && source.state() != ResourceBatch.SourceState.ROLLED_BACK);
        if (!pendingSources) { return false; }
        dispatch(active, true, cleanupDeadline);
        return true;
    }

    private boolean prepareContinuations(List<RecipeProcess.Stage> stages) {
        var computations = new ObjectArrayList<RecipePureComputation>();
        for (var stage : stages) {
            var computation = stage.computation();
            if (computation != null && !preparedComputations.contains(computation)) {
                computations.add(computation);
                prepareOwners.add(stage);
            }
        }
        if (computations.isEmpty()) {
            return true;
        }
        if (work.remaining() < computations.size()) {
            workYielded = true;
            prepareOwners.clear();
            return false;
        }
        long portion = work.remaining() / computations.size();
        var jobs = new ObjectArrayList<PlannedTaskExecutor.Job<Boolean>>(computations.size());
        for (int index = 0; index < computations.size(); index++) {
            var computation = computations.get(index);
            prepareOwners.get(index).withdrawPreparedFacts(computation);
            var slice = work.allocate(portion);
            prepareSlices.add(slice);
            jobs.add(new PlannedTaskExecutor.Job<>(cancellation, () -> computation.advance(slice)));
        }
        try {
            tasks.runPrepare(jobs, forwardDeadline, batch -> preparing = batch);
        } catch (RuntimeException | Error dispatchFailure) {
            record(dispatchFailure);
        }
        return finishPrepare(computations);
    }

    private final ObjectArrayList<RecipePureComputation> prepareRound = new ObjectArrayList<>();

    private boolean finishPrepare(List<RecipePureComputation> computations) {
        prepareRound.clear();
        prepareRound.addAll(computations);
        return finishPrepare();
    }

    private boolean finishPrepare() {
        var barrier = preparing;
        if (barrier == null) {
            return stopReason == null;
        }
        if (!barrier.quiescent()) {
            return false;
        }
        boolean completed = true;
        var results = barrier.results();
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            if (result instanceof PlannedTaskExecutor.Success<Boolean>(var ready)) {
                try {
                    prepareOwners.get(index).retainPreparedFacts(prepareRound.get(index));
                } catch (RuntimeException | Error publicationFailure) {
                    record(publicationFailure);
                    completed = false;
                }
                completed &= ready;
                if (ready) {
                    preparedComputations.add(prepareRound.get(index));
                }
            } else if (result instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                record(cause);
                completed = false;
            } else if (result instanceof PlannedTaskExecutor.Stopped<Boolean> stopped) {
                stop(stopped.reason());
                completed = false;
            }
        }
        for (var slice : prepareSlices) {
            work.reclaim(slice);
        }
        prepareSlices.clear();
        prepareOwners.clear();
        preparing = null;
        prepareRound.clear();
        if (!completed && stopReason == null && failure == null) {
            workYielded = true;
        }
        return completed || stopReason != null;
    }

    private boolean rewindAlternative(Settlement active, PlannedDeadline deadline) {
        if (deadline.expired(nanoTime.getAsLong())) { return false; }
        var resources = active.resources;
        if (active.rewinding == null) {
            for (var entry : active.entries) {
                if (entry.stage.hasRejectedAlternative()) { active.rewinding = entry.stage; break; }
            }
        }
        var stage = Objects.requireNonNull(active.rewinding, "No rejected candidate");
        try {
            if (!resources.rewinding()) { resources.rollbackTo(stage.rejectedSavepoint()); }
            else { resources.resolveBusiness(); }
            if (stage.finishAlternativeRollback(resources)) {
                active.rewinding = null;
                active.staged = true;
                active.applied = true;
                return true;
            }
        } catch (RuntimeException | Error failure) {
            record(failure);
            active.rollback = true;
            return true;
        }
        dispatch(active, true, deadline);
        return true;
    }

    private void dispatch(Settlement active, boolean resolving, PlannedDeadline deadline) {
        active.resolving = resolving;
        var jobs = new ObjectArrayList<PlannedTaskExecutor.SourceJob<Boolean>>();
        long portion = resolving ? Long.MAX_VALUE : Math.max(1, work.remaining() / Math.max(1, active.resources.sources().size()));
        for (var source : active.resources.sources()) {
            if (!resolving && source.state() != ResourceBatch.SourceState.RESERVED) { continue; }
            if (resolving && (source.state() == ResourceBatch.SourceState.COMPLETED
                || source.state() == ResourceBatch.SourceState.ROLLED_BACK)) { continue; }
            var slice = resolving ? null : work.allocate(portion);
            if (slice != null) {
                active.slices.add(slice);
            }
            jobs.add(new PlannedTaskExecutor.SourceJob<>(source.affinity(), new PlannedTaskExecutor.Job<>(
                resolving ? null : cancellation, resolving ? source::resolve : () -> source.applyWithinBudget(Objects.requireNonNull(slice)))));
        }
        // Mandatory cleanup still runs after owner interruption; restore the flag on return.
        boolean interrupted = resolving && Thread.interrupted();
        try {
            tasks.runOnSourceLanes(jobs, deadline, barrier -> active.barrier = barrier);
        } catch (RuntimeException | Error dispatchFailure) {
            record(dispatchFailure);
            active.rollback |= !resolving;
        } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    private boolean finishSettlement(Settlement active) {
        if (!active.resources.finished()) {
            try { active.resources.finish(); }
            catch (RuntimeException | Error notification) { record(notification); }
        }
        if (!active.resources.finished()) { return false; }
        for (var entry : active.entries) {
            if (entry.stage.settled()) { continue; }
            try { entry.stage.settleBatch(failure); }
            catch (RuntimeException | Error acknowledgement) { record(acknowledgement); }
        }
        if (active.entries.stream().anyMatch(entry -> !entry.stage.settled())) { return false; }
        settlement = null;
        return true;
    }

    private void finishGroup() {
        if (group != null) {
            try { group.tryFinish(); }
            catch (RuntimeException | Error post) { record(post); }
            return;
        }
        var session = processSession;
        if (session == null) { processFinished = true; return; }
        if (session.currentStage() != null) { return; }
        try { session.finish(); }
        catch (RuntimeException | Error post) { record(post); }
        finally { processFinished = Objects.requireNonNull(process).quiescent(); }
    }

    private void stop(Reason reason) {
        if (stopReason == null) { stopReason = reason; }
        if (group != null && !group.finished()) { group.stop(); }
    }

    private void record(Throwable observed) {
        if (failure == null) { failure = observed; }
        else if (failure != observed) { failure.addSuppressed(observed); }
        if (unreported == null) { unreported = observed; }
        else if (unreported != observed && unreported != failure) { unreported.addSuppressed(observed); }
        stop(Reason.WORKER_FAILURE);
        if (group != null && !group.finished()) { group.recordFailure(observed); }
        else if (processSession != null && Objects.requireNonNull(process).pendingTick() == processSession) {
            processSession.recordFailure(observed);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner || executing) { throw new IllegalStateException("Recipe coordinator requires its idle owner thread"); }
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        if (failure != null) { throw new IllegalStateException("Planned source action failed", failure); }
    }

    private static final class Entry {
        private final RecipeProcess.Stage stage;
        private boolean acquired;
        private boolean localCleanup;
        private boolean cleanupStarted;
        private final ObjectArrayList<ReversibleTransaction> participants = new ObjectArrayList<>();
        @Nullable private ResourceBatch participantCleanup;
        private Entry(RecipeProcess.Stage stage) { this.stage = stage; }
    }

    private static final class Settlement {
        private final ResourceBatch resources;
        private final List<Entry> entries;
        private final ObjectArrayList<RecipeWorkBudget> slices = new ObjectArrayList<>();
        @Nullable private PlannedTaskExecutor.Batch<Boolean> barrier;
        private boolean rollback;
        private boolean applied;
        private boolean staged;
        @Nullable private RecipeProcess.Stage rewinding;
        private boolean resolving;
        private Settlement(ResourceBatch resources, List<Entry> entries, boolean rollback) {
            this.resources = resources;
            this.entries = entries;
            this.rollback = rollback;
        }
    }
}
