package com.circulation.prototype_eng.scheduler.planned;

import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
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
    private final MachineWorkSlots.TickGroup group;
    private final PlannedDeadline forwardDeadline;
    private final PlannedCancellationToken cancellation;
    private final BooleanSupplier authorization;
    private final LongSupplier nanoTime;
    private final ObjectArrayList<Entry> round = new ObjectArrayList<>();
    private int acquisitionCursor;
    private boolean begun;
    private boolean executing;
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
    }

    /**
     * Runs until complete or accurately pending. A fresh cleanup deadline permits later confirmation,
     * never additional forward work after the original deadline/cancellation. New faults are thrown
     * after the available cleanup work; false is an ordinary pending state, not an exception signal.
     */
    @Override public boolean advance(PlannedDeadline cleanupDeadline) {
        requireOwner();
        Objects.requireNonNull(cleanupDeadline);
        if (group.finished()) { return true; }
        executing = true;
        try {
            while (!group.finished()) {
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
                    try { group.begin(); }
                    catch (RuntimeException | Error beginFailure) { record(beginFailure); }
                    continue;
                }
                if (round.isEmpty()) {
                    try {
                        for (var stage : group.nextRound()) { round.add(new Entry(stage)); }
                    } catch (RuntimeException | Error captureFailure) {
                        record(captureFailure);
                        // nextRound can throw after creating earlier prepared leases.
                        for (var stage : group.currentStages()) { round.add(new Entry(stage)); }
                        continue;
                    }
                    acquisitionCursor = 0;
                    if (round.isEmpty()) { finishGroup(); break; }
                }
                if (acquisitionCursor < round.size()) {
                    var entry = round.get(acquisitionCursor++);
                    entry.acquired = true;
                    try { entry.stage.acquire(); }
                    catch (RuntimeException | Error acquisitionFailure) { record(acquisitionFailure); continue; }
                    if (entry.stage.result() != ProcessResult.SUCCESS) {
                        // Release rejected holds before the next slot acquires, preserving PM ordering.
                        if (!bind(List.of(entry), true)) { break; }
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
        return group.finished();
    }

    @Override public boolean finished() { return group.finished(); }

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
        if (current == null || current.barrier == null) { return; }
        executing = true;
        try { current.barrier.awaitQuiescence(); }
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
        for (var entry : entries) { stages.add(entry.stage); }
        try {
            var resources = RecipeProcess.Stage.tryAcquireBatch(stages);
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

    private ObjectArrayList<Entry> unresolved() {
        var entries = new ObjectArrayList<Entry>();
        for (var entry : round) { if (!entry.stage.settled()) { entries.add(entry); } }
        return entries;
    }

    private void clearRound() { round.clear(); acquisitionCursor = 0; }

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
            active.barrier = null;
            if (active.resolving) {
                if (resources.readyToFinish()) { return finishSettlement(active); }
                // One confirmation attempt per advance; UNKNOWN or queued cleanup is retained.
                return false;
            }
            active.applied = successful;
            if (!successful) { active.rollback = true; }
        }

        if (resources.decision() == ResourceBatch.Decision.OPEN) {
            if (active.rollback || !forwardAllowed()) {
                if (cleanupDeadline.expired(nanoTime.getAsLong())) { return false; }
                try { resources.rollback(); }
                catch (RuntimeException | Error rollback) { record(rollback); }
            } else if (!active.applied) {
                dispatch(active, false, forwardDeadline);
                return true;
            } else {
                try { resources.commit(this::forwardAllowed); }
                catch (RuntimeException | Error commit) { record(commit); }
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

    private void dispatch(Settlement active, boolean resolving, PlannedDeadline deadline) {
        active.resolving = resolving;
        var jobs = new ObjectArrayList<PlannedTaskExecutor.SourceJob<Boolean>>();
        for (var source : active.resources.sources()) {
            if (resolving && (source.state() == ResourceBatch.SourceState.COMPLETED
                || source.state() == ResourceBatch.SourceState.ROLLED_BACK)) { continue; }
            jobs.add(new PlannedTaskExecutor.SourceJob<>(source.affinity(), new PlannedTaskExecutor.Job<>(
                resolving ? null : cancellation, resolving ? source::resolve : source::apply)));
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
        try { group.tryFinish(); }
        catch (RuntimeException | Error post) { record(post); }
    }

    private void stop(Reason reason) {
        if (stopReason == null) { stopReason = reason; }
        if (!group.finished()) { group.stop(); }
    }

    private void record(Throwable observed) {
        if (failure == null) { failure = observed; }
        else if (failure != observed) { failure.addSuppressed(observed); }
        if (unreported == null) { unreported = observed; }
        else if (unreported != observed && unreported != failure) { unreported.addSuppressed(observed); }
        stop(Reason.WORKER_FAILURE);
        if (!group.finished()) { group.recordFailure(observed); }
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
        private Entry(RecipeProcess.Stage stage) { this.stage = stage; }
    }

    private static final class Settlement {
        private final ResourceBatch resources;
        private final List<Entry> entries;
        @Nullable private PlannedTaskExecutor.Batch<Boolean> barrier;
        private boolean rollback;
        private boolean applied;
        private boolean resolving;
        private Settlement(ResourceBatch resources, List<Entry> entries, boolean rollback) {
            this.resources = resources;
            this.entries = entries;
            this.rollback = rollback;
        }
    }
}
