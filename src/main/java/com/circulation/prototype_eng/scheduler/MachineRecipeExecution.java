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

/**
 * Retains machine dispatches, exact lifecycle handles and source ownership through one captured owner tick.
 */
final class MachineRecipeExecution implements PlannedExecution {
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
                if (!forwardAllowed()) { retireCursors(); break; }
                try {
                    for (var entry : entries) { advanceCursor(entry); }
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
        if (handle == null || dispatch == null || entry.cursorDone || handle.deferredRecipeWork() != null) { return; }
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
            if (handle.deferredRecipeWork() != null) { return; }
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
    }

    @Override public void awaitExit() {
        requireOwner();
        if (coordinator != null) { coordinator.awaitExit(); }
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
        private Entry(Candidate candidate) { this.candidate = candidate; }
    }
}
