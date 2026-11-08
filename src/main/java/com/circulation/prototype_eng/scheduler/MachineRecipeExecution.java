package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.storage.ResourceBatch;

/**
 * Retains machine dispatches, exact lifecycle handles and source ownership through one captured owner tick.
 */
final class MachineRecipeExecution implements PlannedExecution {
    private static final int SCAN_WORK_PER_ADVANCE = 512;
    private final Thread owner = Thread.currentThread();
    private final PlannedTaskExecutor tasks;
    private final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
    private final PlannedDeadline forwardDeadline;
    private final Runnable refresh;
    private final BooleanSupplier tickCurrent;
    private final PlannedCancellationToken cancellation = new PlannedCancellationToken();
    @Nullable private PlannedRecipeCoordinator coordinator;
    private boolean acquired;
    private boolean executing;
    private boolean finished;
    @Nullable private Throwable unreported;

    MachineRecipeExecution(PlannedTaskExecutor tasks, List<Candidate> candidates, PlannedDeadline forwardDeadline,
                           Runnable refresh, BooleanSupplier tickCurrent) {
        this.tasks = Objects.requireNonNull(tasks);
        this.forwardDeadline = Objects.requireNonNull(forwardDeadline);
        this.refresh = Objects.requireNonNull(refresh);
        this.tickCurrent = Objects.requireNonNull(tickCurrent);
        for (var candidate : candidates) { entries.add(new Entry(candidate)); }
    }

    @Override public boolean advance(PlannedDeadline cleanupDeadline) {
        requireOwner();
        Objects.requireNonNull(cleanupDeadline);
        if (finished) { return true; }
        executing = true;
        try {
            if (!acquired) {
                acquired = true;
                if (forwardAllowed()) { acquireMachines(); }
            }
            while (!finished) {
                if (coordinator != null) {
                    if (!settleCoordinator(cleanupDeadline)) { break; }
                    continue;
                }
                if (!settleScans(cleanupDeadline)) break;
                if (!forwardAllowed()) { retireCursors(); break; }
                try {
                    for (var entry : entries) { advanceCursor(entry); }
                    boolean scans = false;
                    for (var entry : entries) {
                        if (entry.handle != null && !entry.handle.finished() && entry.handle.deferredRecipeScan() != null) {
                            scans = true;
                            break;
                        }
                    }
                    if (scans) continue;
                    if (!forwardAllowed()) { continue; }
                    var hosts = new ObjectArrayList<MachineWorkSlots>();
                    for (var entry : entries) {
                        var handle = entry.handle;
                        if (handle != null && !handle.finished()) {
                            var deferred = handle.deferredRecipeWork();
                            if (deferred != null) { hosts.add(deferred); }
                        }
                    }
                    if (hosts.isEmpty()) { releaseMachines(); break; }
                    // The constructor installs all host leases atomically and invokes no callback.
                    coordinator = new PlannedRecipeCoordinator(tasks, hosts, forwardDeadline, cancellation, this::forwardAllowed);
                } catch (RuntimeException | Error failure) { record(failure); }
            }
        } catch (RuntimeException | Error failure) {
            record(failure);
        } finally { executing = false; }
        var observed = unreported;
        unreported = null;
        rethrow(observed);
        return finished;
    }

    private void acquireMachines() {
        for (var entry : entries) {
            if (!forwardAllowed()) { return; }
            // Store the handle before graph capture so an exceptional capture cannot leak RUNNING.
            entry.handle = entry.candidate.lifecycle.beginScheduled();
            var handle = entry.handle;
            if (handle != null) {
                handle.run(() -> entry.dispatch = entry.candidate.machine.components().captureDispatch(entry.candidate.machine));
            }
        }
    }

    private void advanceCursor(Entry entry) {
        var handle = entry.handle;
        var dispatch = entry.dispatch;
        if (handle == null || dispatch == null || entry.cursorDone || handle.deferredRecipeWork() != null || handle.deferredRecipeScan() != null) { return; }
        if (!entry.begun) {
            if (!forwardAllowed()) { return; }
            refresh.run();
            if (!forwardAllowed()) {
                return;
            }
            entry.begun = true;
            if (!handle.run(dispatch::begin)) { return; }
        }
        while (forwardAllowed()) {
            refresh.run();
            if (!forwardAllowed()) {
                return;
            }
            entry.advanced = false;
            if (!handle.runComponent(() -> entry.advanced = dispatch.advance())) { return; }
            if (handle.deferredRecipeWork() != null || handle.deferredRecipeScan() != null) { return; }
            if (!entry.advanced) {
                if (!forwardAllowed()) { return; }
                refresh.run();
                if (!forwardAllowed()) {
                    return;
                }
                entry.cursorDone = true;
                if (!handle.run(dispatch::finish)) {
                    handle.cleanup(dispatch::abort);
                }
                return;
            }
        }
    }

    /** No successor normal/work-slot callback runs while any exact scan handle still owns work. */
    private boolean settleScans(PlannedDeadline cleanupDeadline) {
        boolean settled = true;
        for (var entry : entries) {
            var handle = entry.handle;
            if (handle == null || handle.finished()) continue;
            var request = handle.deferredRecipeScan();
            if (request == null) continue;
            if (entry.scan == null) {
                if (!forwardAllowed()) {
                    handle.finishDeferredRecipeScan(request);
                    continue;
                }
                entry.scan = new ScanWork(request);
                // Capture before any callback can throw. The consumed onTick is never replayed.
                var originalScan = Objects.requireNonNull(entry.scan);
                handle.run(() -> originalScan.attempt = request.scanner().beginScan(request.limits()));
                if (entry.scan.attempt == null) {
                    handle.finishDeferredRecipeScan(request);
                    entry.scan = null;
                    continue;
                }
            }
            if (!advanceScan(entry, cleanupDeadline)) settled = false;
        }
        return settled;
    }

    private boolean advanceScan(Entry entry, PlannedDeadline cleanupDeadline) {
        var scan = Objects.requireNonNull(entry.scan);
        var handle = Objects.requireNonNull(entry.handle);
        var attempt = Objects.requireNonNull(scan.attempt);
        if (scan.barrier == null && !forwardAllowed()) scan.work = new RecipeWorkBudget(SCAN_WORK_PER_ADVANCE);
        while (true) {
            if (scan.barrier != null) {
                // A terminal result may precede physical worker exit. Join the original batch
                // before consuming receipts; its normal exit does not revoke this captured tick.
                scan.barrier.awaitQuiescence();
                if (scan.receiptCursor == -1) scan.receiptCursor = scan.barrier.results().size() - 1;
                while (scan.receiptCursor >= 0) {
                    if (!scan.work.take()) return false;
                    int index = scan.receiptCursor;
                    var outcome = scan.barrier.results().get(index);
                    try {
                        if (outcome instanceof PlannedTaskExecutor.Failure<Boolean>(var failure)) {
                            record(failure);
                        } else if (outcome instanceof PlannedTaskExecutor.Stopped<Boolean> stopped) {
                            cancellation.cancel(stopped.reason());
                        } else if (outcome instanceof PlannedTaskExecutor.Success<Boolean>(var completed)) {
                            if (scan.computation != null) {
                                if (completed) attempt.finishComputation(scan.computation);
                            } else {
                                var source = scan.sources.get(index);
                                if (scan.resolving) {
                                    attempt.finishSourceResolution(source, completed);
                                    scan.sourceUnknown |= !completed && !source.workYielded();
                                } else if (!completed && !source.workYielded()) {
                                    cancellation.cancel(Reason.RESERVATION_CONFLICT);
                                }
                            }
                        }
                    } finally {
                        scan.work.reclaim(scan.slices.removeLast());
                        if (scan.computation == null) scan.sources.removeLast();
                        scan.receiptCursor--;
                        // This quiescent receipt is consumed even when record immediately throws fatal.
                        // Clearing the last barrier prevents -1 from selecting the same receipts again.
                        if (scan.receiptCursor < 0) {
                            scan.computation = null;
                            scan.barrier = null;
                        }
                    }
                }
                scan.computation = null;
                scan.barrier = null;
                if (scan.sourceUnknown) {
                    scan.sourceUnknown = false;
                    return false;
                }
            }
            if (scan.work.remaining() == 0) scan.work = new RecipeWorkBudget(SCAN_WORK_PER_ADVANCE);
            boolean allowed = forwardAllowed();
            if (allowed) {
                refresh.run();
                allowed = forwardAllowed(); // The 2A refresh/current proof remains mandatory.
            }
            boolean forward = allowed;
            long ownerAllowance = scan.work.remaining();
            Runnable step = () -> attempt.advance(scan.work, () -> forward && forwardAllowed());
            try {
                if (!forward || !handle.run(step)) handle.cleanup(() -> attempt.advance(scan.work, () -> false));
            } catch (RuntimeException | Error failure) {
                record(failure);
            }
            if (attempt.finished()) {
                handle.finishDeferredRecipeScan(scan.request);
                entry.scan = null;
                return true;
            }
            if (!attempt.hasObligations() && (scan.work.remaining() != 0 || !forwardAllowed())) {
                attempt.releasePureYield();
                handle.finishDeferredRecipeScan(scan.request);
                entry.scan = null;
                return true;
            }
            var computation = attempt.computation();
            if (computation != null && forwardAllowed() && scan.work.remaining() > 1) {
                var slice = scan.work.allocate(scan.work.remaining() - 1);
                scan.slices.add(slice);
                scan.computation = computation;
                try {
                    tasks.runPrepare(List.of(new PlannedTaskExecutor.Job<>(cancellation, () -> computation.advance(slice))),
                        forwardDeadline, barrier -> scan.barrier = barrier);
                } catch (RuntimeException | Error failure) {
                    record(failure);
                }
                continue;
            }
            var sources = attempt.sources();
            boolean resolving = attempt.resolvingSources();
            if (!sources.isEmpty() && (resolving || forwardAllowed()) && scan.work.remaining() > 1) {
                var deadline = resolving ? cleanupDeadline : forwardDeadline;
                if (deadline.expired(System.nanoTime())) return false;
                var jobs = new ObjectArrayList<PlannedTaskExecutor.SourceJob<Boolean>>();
                scan.resolving = resolving;
                for (int index = 0; index < sources.size() && scan.work.remaining() > scan.sources.size() + 2; index++) {
                    scan.work.take();
                    var source = sources.get(index);
                    var state = source.state();
                    if (resolving && (state == ResourceBatch.SourceState.ROLLED_BACK || state == ResourceBatch.SourceState.COMPLETED)
                        || !resolving && state != ResourceBatch.SourceState.RESERVED) continue;
                    var slice = scan.work.allocate(1);
                    scan.sources.add(source);
                    scan.slices.add(slice);
                    jobs.add(new PlannedTaskExecutor.SourceJob<>(source.affinity(), new PlannedTaskExecutor.Job<>(
                        resolving ? null : cancellation, () -> resolving ? source.resolveWithinBudget(slice) : source.applyWithinBudget(slice))));
                }
                if (!jobs.isEmpty()) {
                    try {
                        tasks.runOnSourceLanes(jobs, deadline, barrier -> scan.barrier = barrier);
                    } catch (RuntimeException | Error failure) {
                        record(failure);
                    }
                    continue;
                }
            }
            if (scan.work.remaining() <= 1 && forwardAllowed()) {
                scan.work = new RecipeWorkBudget(SCAN_WORK_PER_ADVANCE);
                continue;
            }
            if (forwardAllowed() && scan.work.remaining() < ownerAllowance) continue;
            return false; // UNKNOWN or old-tick cleanup retains the exact original handle.
        }
    }

    private boolean settleCoordinator(PlannedDeadline cleanupDeadline) {
        var current = Objects.requireNonNull(coordinator);
        // A source barrier can finish within the original forward budget. UNKNOWN business/source
        // states have no active barrier and return pending rather than renewing or busy retrying.
        while (!current.finished()) {
            try { current.advance(cleanupDeadline); }
            catch (RuntimeException | Error failure) { record(failure); }
            if (current.finished()) { break; }
            if (!current.hasActiveSources()) {
                if (current.workYielded() && forwardAllowed()) {
                    continue;
                }
                return false; }
            current.awaitExit();
        }
        if (current.failure() != null) {
            for (var entry : entries) {
                var handle = entry.handle;
                if (handle != null && !handle.finished()) { handle.recordFailure(current.failure()); }
            }
        }
        if (current.stopReason() != null) { cancellation.cancel(current.stopReason()); }
        for (var entry : entries) {
            var handle = entry.handle;
            if (handle == null || handle.finished()) { continue; }
            var deferred = handle.deferredRecipeWork();
            if (deferred != null) { handle.finishDeferredRecipeWork(deferred); }
        }
        coordinator = null;
        return true;
    }

    private boolean forwardAllowed() {
        if (cancellation.cancelled()) { return false; }
        Reason reason = null;
        if (Thread.currentThread().isInterrupted()) { reason = Reason.INTERRUPTED; }
        else if (forwardDeadline.expired(System.nanoTime())) { reason = Reason.WORKER_TIMEOUT; }
        else if (!tickCurrent.getAsBoolean()) { reason = Reason.EPOCH_CHANGED; }
        else {
            for (var entry : entries) {
                if (!entry.candidate.current.getAsBoolean() || !entry.candidate.lifecycle.schedulingAllowed()) {
                    reason = Reason.OWNER_CHANGED;
                    break;
                }
            }
        }
        if (reason != null) { cancellation.cancel(reason); return false; }
        return true;
    }

    private void retireCursors() {
        for (var entry : entries) {
            var handle = entry.handle;
            if (handle == null || handle.finished()) { continue; }
            if (entry.dispatch != null && !entry.cursorDone) {
                entry.cursorDone = true;
                handle.cleanup(entry.dispatch::abort);
            }
            var deferred = handle.deferredRecipeWork();
            if (deferred != null) { handle.finishDeferredRecipeWork(deferred); }
        }
        releaseMachines();
    }

    private void releaseMachines() {
        boolean released = true;
        for (var entry : entries) {
            var handle = entry.handle;
            if (handle != null && !handle.finished()) { released &= handle.tryFinish(); }
        }
        finished = released;
    }

    private void record(Throwable failure) {
        if (unreported == null) { unreported = failure; }
        else if (unreported != failure) { unreported.addSuppressed(failure); }
        cancellation.cancel(Reason.WORKER_FAILURE);
        if (failure instanceof VirtualMachineError fatal) throw fatal;
    }

    @Override public void awaitExit() {
        requireOwner();
        if (coordinator != null) { coordinator.awaitExit(); }
        for (var entry : entries) {
            if (entry.scan != null && entry.scan.barrier != null) entry.scan.barrier.awaitQuiescence();
        }
    }

    @Override public boolean finished() { requireOwner(); return finished; }

    @Override public void cancel(Reason reason) { requireOwner(); cancellation.cancel(Objects.requireNonNull(reason)); }

    private void requireOwner() {
        if (Thread.currentThread() != owner || executing) { throw new IllegalStateException("Network machine execution requires its idle owner"); }
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    record Candidate(MachineInstance machine, MachineLifecycle lifecycle, BooleanSupplier current) {}

    private static final class Entry {
        private final Candidate candidate;
        @Nullable private MachineLifecycle.ScheduledExecution handle;
        @Nullable private ComponentMap.TickDispatch<MachineInstance> dispatch;
        private boolean begun;
        private boolean advanced;
        private boolean cursorDone;
        @Nullable private ScanWork scan;
        private Entry(Candidate candidate) { this.candidate = candidate; }
    }

    private static final class ScanWork {
        private final MachineLifecycle.ScheduledExecution.DeferredScan request;
        @Nullable private RecipeScanner.ScanAttempt attempt;
        private RecipeWorkBudget work = new RecipeWorkBudget(SCAN_WORK_PER_ADVANCE);
        @Nullable private PlannedTaskExecutor.Batch<Boolean> barrier;
        @Nullable private RecipePureComputation computation;
        private final ObjectArrayList<ResourceBatch.Source> sources = new ObjectArrayList<>();
        private final ObjectArrayList<RecipeWorkBudget> slices = new ObjectArrayList<>();
        private boolean resolving;
        private int receiptCursor = -1;
        private boolean sourceUnknown;
        private ScanWork(MachineLifecycle.ScheduledExecution.DeferredScan request) { this.request = request; }
    }
}
