package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Retains machine dispatches, exact lifecycle handles and source ownership through one captured owner tick.
 */
final class MachineRecipeExecution implements PlannedExecution {
    private static final int SCAN_WORK_PER_ADVANCE = 512;
    private final Thread owner = Thread.currentThread();
    private final PlannedTaskExecutor tasks;
    private final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
    private final ObjectArrayList<ObjectArrayList<Entry>> callbackGroups = new ObjectArrayList<>();
    private final PlannedDeadline forwardDeadline;
    private final Runnable refresh;
    private final BooleanSupplier tickCurrent;
    private final PlannedCancellationToken cancellation = new PlannedCancellationToken();
    private final ObjectArrayList<Entry> workerClaims = new ObjectArrayList<>();
    @Nullable
    private PlannedRecipeCoordinator coordinator;
    private boolean acquired;
    private boolean executing;
    private boolean finished;
    @Nullable
    private Throwable unreported;
    @Nullable
    private PlannedTaskExecutor.Batch<Boolean> workerBatch;

    MachineRecipeExecution(PlannedTaskExecutor tasks, List<Candidate> candidates, PlannedDeadline forwardDeadline,
                           Runnable refresh, BooleanSupplier tickCurrent) {
        this.tasks = Objects.requireNonNull(tasks);
        this.forwardDeadline = Objects.requireNonNull(forwardDeadline);
        this.refresh = Objects.requireNonNull(refresh);
        this.tickCurrent = Objects.requireNonNull(tickCurrent);
        boolean needsGroups = false;
        for (int index21 = 0; index21 < candidates.size(); index21++) {
            var candidate = candidates.get(index21);
            entries.add(new Entry(candidate));
            needsGroups |= candidate.policy != null && candidate.policy.workAsync();
        }
        if (!needsGroups) return;
        var union = new TaskScheduler.UnionFind(entries.size());
        var owners = new Object2IntOpenHashMap<>();
        owners.defaultReturnValue(-1);
        for (int index = 0; index < entries.size(); index++)
            for (var key : entries.get(index).candidate.affinities) {
                int previous = owners.putIfAbsent(Objects.requireNonNull(key), index);
                if (previous >= 0) union.union(index, previous);
            }
        var groups = new Int2ObjectLinkedOpenHashMap<ObjectArrayList<Entry>>();
        for (int index = 0; index < entries.size(); index++)
            groups.computeIfAbsent(union.find(index), _ -> new ObjectArrayList<>()).add(entries.get(index));
        callbackGroups.addAll(groups.values());
        for (int index4 = 0; index4 < callbackGroups.size(); index4++) {
            var group = callbackGroups.get(index4);
            String affinity = group.getFirst().affinity;
            for (int index1 = 0; index1 < group.size(); index1++) {
                var entry = group.get(index1);
                entry.affinity = affinity;
            }
        }
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable appendFailure(@Nullable Throwable first, Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        if (next instanceof VirtualMachineError && !(first instanceof VirtualMachineError)) {
            next.addSuppressed(first);
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    @Override
    public boolean advance(PlannedDeadline cleanupDeadline) {
        requireOwner();
        Objects.requireNonNull(cleanupDeadline);
        if (finished) {
            return true;
        }
        executing = true;
        try {
            if (workerBatch != null || !workerClaims.isEmpty()) {
                var receiptFailure = reclaimWorkers(workerClaims, null);
                if (receiptFailure != null) record(receiptFailure);
                if (!workerClaims.isEmpty()) return false;
            }
            if (!acquired) {
                acquired = true;
                if (forwardAllowed()) {
                    acquireMachines();
                }
            }
            while (!finished) {
                if (coordinator != null) {
                    if (!settleCoordinator(cleanupDeadline)) {
                        break;
                    }
                    continue;
                }
                if (!settlePolicyWork(cleanupDeadline)) break;
                if (!settleScans(cleanupDeadline)) break;
                if (!forwardAllowed()) {
                    retireCursors();
                    break;
                }
                try {
                    advanceCursors();
                    boolean scans = false;
                    for (int index9 = 0; index9 < entries.size(); index9++) {
                        var entry = entries.get(index9);
                        if (entry.handle != null && !entry.handle.finished() && entry.handle.deferredRecipeScan() != null) {
                            scans = true;
                            break;
                        }
                    }
                    if (scans) continue;
                    if (!forwardAllowed()) {
                        continue;
                    }
                    var hosts = new ObjectArrayList<MachineWorkSlots>(entries.size());
                    boolean resumedWork = false;
                    for (int index19 = 0; index19 < entries.size(); index19++) {
                        var entry = entries.get(index19);
                        var handle = entry.handle;
                        if (handle != null && !handle.finished()) {
                            var deferred = handle.deferredRecipeWork();
                            if (deferred != null) {
                                if (entry.workedHosts.contains(deferred)) {
                                    handle.finishDeferredRecipeWork(deferred);
                                    resumedWork = true;
                                } else if (entry.candidate.policy == null) {
                                    if (entry.candidate.machine instanceof CrossRecipeController) {
                                        hosts.addAll(entry.candidate.machine.recipeHosts());
                                        entry.workedHosts.addAll(entry.candidate.machine.recipeHosts());
                                    } else hosts.add(deferred);
                                } else if (entry.policyWork == null) entry.policyWork = new PolicyWork(entry, deferred);
                            }
                        }
                    }
                    if (hosts.isEmpty()) {
                        boolean policyPending = false;
                        for (int index6 = 0; index6 < entries.size(); index6++) {
                            var entry = entries.get(index6);
                            if (entry.policyWork != null) {
                                policyPending = true;
                                break;
                            }
                        }
                        if (policyPending || resumedWork) continue;
                        releaseMachines();
                        break;
                    }
                    // The constructor installs all host leases atomically and invokes no callback.
                    coordinator = new PlannedRecipeCoordinator(tasks, hosts, forwardDeadline, cancellation, this::forwardAllowed);
                } catch (RuntimeException | Error failure) {
                    record(failure);
                }
            }
        } catch (RuntimeException | Error failure) {
            record(failure);
        } finally {
            executing = false;
        }
        var observed = unreported;
        unreported = null;
        rethrow(observed);
        return finished;
    }

    private void acquireMachines() {
        for (int index12 = 0; index12 < entries.size(); index12++) {
            var entry = entries.get(index12);
            if (!forwardAllowed()) {
                return;
            }
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
        if (handle == null || dispatch == null || entry.cursorDone || handle.deferredRecipeWork() != null || handle.deferredRecipeScan() != null) {
            return;
        }
        if (!entry.begun) {
            if (!forwardAllowed()) {
                return;
            }
            refresh.run();
            if (!forwardAllowed()) {
                return;
            }
            entry.begun = true;
            if (!handle.run(dispatch::begin)) {
                return;
            }
        }
        while (forwardAllowed()) {
            refresh.run();
            if (!forwardAllowed()) {
                return;
            }
            entry.advanced = false;
            if (!handle.runComponent(() -> entry.advanced = dispatch.advance())) {
                return;
            }
            if (handle.deferredRecipeWork() != null || handle.deferredRecipeScan() != null) {
                return;
            }
            if (!entry.advanced) {
                if (!forwardAllowed()) {
                    return;
                }
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

    private void advanceCursors() {
        var delegated = workerClaims;
        var jobs = new ObjectArrayList<PlannedTaskExecutor.Job<Boolean>>(entries.size());
        Throwable failure = null;
        try {
            for (int index18 = 0; index18 < entries.size(); index18++) {
                var entry = entries.get(index18);
                var policy = entry.candidate.policy;
                if (policy == null || !policy.workAsync()) {
                    advanceCursor(entry);
                    continue;
                }
                var handle = entry.handle;
                if (handle == null || entry.dispatch == null || entry.cursorDone || handle.finished()
                    || handle.deferredRecipeWork() != null || handle.deferredRecipeScan() != null) continue;
                refresh.run();
                if (!forwardAllowed()) break;
                // Register before either claim: a later capture/refresh failure must reclaim both.
                delegated.add(entry);
                var dispatchAction = entry.dispatch.delegateWorker(() -> advanceWorkerCursor(entry));
                entry.dispatchWorker = dispatchAction;
                entry.worker = handle.delegateWorker(dispatchAction);
            }
            for (int index14 = 0; index14 < callbackGroups.size(); index14++) {
                var group = callbackGroups.get(index14);
                boolean ready = false;
                for (int index3 = 0; index3 < group.size(); index3++) {
                    var entry = group.get(index3);
                    if (entry.worker != null) {
                        ready = true;
                        break;
                    }
                }
                if (ready) jobs.add(new PlannedTaskExecutor.Job<>(cancellation, () -> {
                    for (int index2 = 0; index2 < group.size(); index2++) {
                        var entry = group.get(index2);
                        if (entry.worker != null && workerAllowed(entry)) entry.worker.run();
                    }
                    return true;
                }));
            }
            if (!jobs.isEmpty()) tasks.runPrepare(jobs, forwardDeadline, batch -> workerBatch = batch);
        } catch (RuntimeException | Error observed) {
            failure = observed;
        } finally {
            failure = reclaimWorkers(delegated, failure);
        }
        if (failure != null) record(failure);
    }

    @Nullable
    private Throwable reclaimWorkers(List<Entry> delegated, @Nullable Throwable failure) {
        var batch = workerBatch;
        if (batch != null) {
            try {
                batch.awaitQuiescence();
            } catch (RuntimeException | Error observed) {
                failure = appendFailure(failure, observed);
            }
            if (!batch.quiescent())
                return appendFailure(failure, new IllegalStateException("Machine callbacks have not exited"));
        }
        for (int index = 0; index < delegated.size(); index++) {
            var entry = delegated.get(index);
            try {
                if (entry.worker != null) Objects.requireNonNull(entry.handle).finishWorker(entry.worker);
                entry.worker = null;
            } catch (RuntimeException | Error observed) {
                failure = appendFailure(failure, observed);
            }
            try {
                if (entry.dispatchWorker != null) entry.dispatchWorker.reclaim();
                entry.dispatchWorker = null;
            } catch (RuntimeException | Error observed) {
                failure = appendFailure(failure, observed);
            }
        }
        if (batch != null) {
            for (int index = 0; index < batch.results().size(); index++) {
                try {
                    var outcome = batch.results().get(index);
                    if (outcome instanceof PlannedTaskExecutor.Failure<Boolean>(var observed))
                        failure = appendFailure(failure, observed);
                    else if (outcome instanceof PlannedTaskExecutor.Stopped<Boolean>(var reason, _))
                        cancellation.cancel(reason);
                } catch (RuntimeException | Error observed) {
                    failure = appendFailure(failure, observed);
                }
            }
        }
        workerBatch = null;
        boolean reclaimed = true;
        for (int index10 = 0; index10 < delegated.size(); index10++) {
            var entry = delegated.get(index10);
            reclaimed &= entry.worker == null && entry.dispatchWorker == null;
        }
        if (reclaimed) delegated.clear();
        return failure;
    }

    private boolean workerAllowed(Entry entry) {
        return !cancellation.cancelled() && !forwardDeadline.expired(System.nanoTime())
            && entry.candidate.lifecycle.schedulingAllowed();
    }

    private void advanceWorkerCursor(Entry entry) {
        var dispatch = Objects.requireNonNull(entry.dispatch);
        var handle = Objects.requireNonNull(entry.handle);
        if (!entry.begun) {
            if (!workerAllowed(entry)) return;
            entry.begun = true;
            dispatch.begin();
        }
        while (workerAllowed(entry)) {
            entry.advanced = dispatch.advance();
            if (handle.deferredRecipeScan() != null || handle.deferredRecipeWork() != null) return;
            if (!entry.advanced) {
                entry.cursorDone = true;
                dispatch.finish();
                return;
            }
        }
    }

    private boolean settlePolicyWork(PlannedDeadline cleanupDeadline) {
        var delegated = workerClaims;
        var jobs = new ObjectArrayList<PlannedTaskExecutor.SourceJob<Boolean>>(entries.size());
        Throwable failure = null;
        try {
            for (int index17 = 0; index17 < entries.size(); index17++) {
                var entry = entries.get(index17);
                var work = entry.policyWork;
                if (work == null) continue;
                var handle = Objects.requireNonNull(entry.handle);
                if (work.asynchronous) {
                    delegated.add(entry);
                    var action = handle.delegateWorker(() -> work.advance(cleanupDeadline));
                    entry.worker = action;
                    jobs.add(new PlannedTaskExecutor.SourceJob<>(entry.affinity, new PlannedTaskExecutor.Job<>(null, () -> {
                        action.run();
                        return work.complete;
                    })));
                } else handle.cleanup(() -> work.advance(cleanupDeadline));
            }
            if (!jobs.isEmpty()) tasks.runOnSourceLanes(jobs, cleanupDeadline, batch -> workerBatch = batch);
        } catch (RuntimeException | Error observed) {
            failure = observed;
        } finally {
            failure = reclaimWorkers(delegated, failure);
        }
        if (failure != null) record(failure);
        boolean complete = true;
        for (int index15 = 0; index15 < entries.size(); index15++) {
            var entry = entries.get(index15);
            var work = entry.policyWork;
            if (work == null) continue;
            var handle = Objects.requireNonNull(entry.handle);
            if (work.complete) {
                entry.workedHosts.addAll(work.hosts);
                if (work.failure != null) handle.recordFailure(work.failure);
                if (work.stopReason != null) cancellation.cancel(work.stopReason);
                handle.finishDeferredRecipeWork(work.host);
                entry.policyWork = null;
            } else complete = false;
        }
        return complete;
    }

    /**
     * No successor normal/work-slot callback runs while any exact scan handle still owns work.
     */
    private boolean settleScans(PlannedDeadline cleanupDeadline) {
        var discoveries = new ObjectArrayList<ScanWork>(entries.size());
        var jobs = new ObjectArrayList<PlannedTaskExecutor.Job<Boolean>>(entries.size());
        while (true) {
            boolean settled = true;
            for (int index20 = 0; index20 < entries.size(); index20++) {
                var entry = entries.get(index20);
                var handle = entry.handle;
                if (handle == null || handle.finished()) continue;
                var request = handle.deferredRecipeScan();
                if (request == null) continue;
                if (entry.scan == null) {
                    if (!forwardAllowed()) {
                        handle.finishDeferredRecipeScan(request);
                        continue;
                    }
                    var policy = entry.candidate.policy;
                    if (policy != null && !policy.scanAsync()) {
                        handle.run(() -> request.scanner().scan(request.limits()));
                        handle.finishDeferredRecipeScan(request);
                        continue;
                    }
                    entry.scan = new ScanWork(request);
                    // Capture before any callback can throw. The consumed onTick is never replayed.
                    var originalScan = Objects.requireNonNull(entry.scan);
                    handle.run(() -> originalScan.attempt = request.scanner().beginScan(request.limits(), policy != null && policy.scanAsync()));
                    if (entry.scan.attempt == null) {
                        handle.finishDeferredRecipeScan(request);
                        entry.scan = null;
                        continue;
                    }
                }
                if (!advanceScan(entry, cleanupDeadline, discoveries, jobs)) settled = false;
            }
            if (jobs.isEmpty()) return settled;
            try {
                // Capture every owner's discovery first; waiting per machine serializes idle scans.
                tasks.runPrepare(jobs, forwardDeadline, barrier -> {
                    for (int index = 0; index < discoveries.size(); index++) {
                        var scan = discoveries.get(index);
                        scan.barrier = barrier;
                        scan.receiptCursor = index;
                    }
                });
            } catch (RuntimeException | Error failure) {
                record(failure);
            }
            discoveries.clear();
            jobs.clear();
        }
    }

    private boolean advanceScan(Entry entry, PlannedDeadline cleanupDeadline, ObjectArrayList<ScanWork> discoveries,
                                ObjectArrayList<PlannedTaskExecutor.Job<Boolean>> discoveryJobs) {
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
                            if (scan.discovery != null) {
                                attempt.finishDiscovery(scan.discovery, completed);
                            } else if (scan.computation != null) {
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
                        if (scan.computation == null && scan.discovery == null) scan.sources.removeLast();
                        scan.receiptCursor = scan.discovery != null ? -1 : scan.receiptCursor - 1;
                        // This quiescent receipt is consumed even when record immediately throws fatal.
                        // Clearing the last barrier prevents -1 from selecting the same receipts again.
                        if (scan.receiptCursor < 0) {
                            scan.computation = null;
                            scan.discovery = null;
                            scan.barrier = null;
                        }
                    }
                }
                scan.computation = null;
                scan.discovery = null;
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
            var discovery = attempt.discoveryWork();
            if (discovery != null && forwardAllowed() && scan.work.remaining() > 1) {
                var slice = scan.work.allocate(scan.work.remaining() - 1);
                scan.slices.add(slice);
                scan.discovery = discovery;
                discoveries.add(scan);
                discoveryJobs.add(new PlannedTaskExecutor.Job<>(cancellation, () -> discovery.advance(slice)));
                return false;
            }
            if (discovery == null && !attempt.hasObligations() && (scan.work.remaining() != 0 || !forwardAllowed())) {
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
            try {
                current.advance(cleanupDeadline);
            } catch (RuntimeException | Error failure) {
                record(failure);
            }
            if (current.finished()) {
                break;
            }
            if (!current.hasActiveSources()) {
                if (current.workYielded() && forwardAllowed()) {
                    continue;
                }
                return false;
            }
            current.awaitExit();
        }
        if (current.failure() != null) {
            for (int index7 = 0; index7 < entries.size(); index7++) {
                var entry = entries.get(index7);
                var handle = entry.handle;
                if (handle != null && !handle.finished()) {
                    handle.recordFailure(current.failure());
                }
            }
        }
        if (current.stopReason() != null) {
            cancellation.cancel(current.stopReason());
        }
        for (int index11 = 0; index11 < entries.size(); index11++) {
            var entry = entries.get(index11);
            var handle = entry.handle;
            if (handle == null || handle.finished()) {
                continue;
            }
            var deferred = handle.deferredRecipeWork();
            if (deferred != null) {
                handle.finishDeferredRecipeWork(deferred);
            }
        }
        coordinator = null;
        return true;
    }

    private boolean forwardAllowed() {
        if (cancellation.cancelled()) {
            return false;
        }
        Reason reason = null;
        if (Thread.currentThread().isInterrupted()) {
            reason = Reason.INTERRUPTED;
        } else if (forwardDeadline.expired(System.nanoTime())) {
            reason = Reason.WORKER_TIMEOUT;
        } else if (!tickCurrent.getAsBoolean()) {
            reason = Reason.EPOCH_CHANGED;
        } else {
            for (int index8 = 0; index8 < entries.size(); index8++) {
                var entry = entries.get(index8);
                if (!entry.candidate.current.getAsBoolean() || !entry.candidate.lifecycle.schedulingAllowed()) {
                    reason = Reason.OWNER_CHANGED;
                    break;
                }
            }
        }
        if (reason != null) {
            cancellation.cancel(reason);
            return false;
        }
        return true;
    }

    private void retireCursors() {
        for (int index13 = 0; index13 < entries.size(); index13++) {
            var entry = entries.get(index13);
            var handle = entry.handle;
            if (handle == null || handle.finished()) {
                continue;
            }
            if (entry.dispatch != null && !entry.cursorDone) {
                entry.cursorDone = true;
                handle.cleanup(entry.dispatch::abort);
            }
            var deferred = handle.deferredRecipeWork();
            if (deferred != null) {
                handle.finishDeferredRecipeWork(deferred);
            }
        }
        releaseMachines();
    }

    private void releaseMachines() {
        boolean released = true;
        for (int index5 = 0; index5 < entries.size(); index5++) {
            var entry = entries.get(index5);
            var handle = entry.handle;
            if (handle != null && !handle.finished()) {
                released &= handle.tryFinish();
            }
        }
        finished = released;
    }

    private void record(Throwable failure) {
        if (unreported == null) {
            unreported = failure;
        } else if (unreported != failure) {
            unreported.addSuppressed(failure);
        }
        cancellation.cancel(Reason.WORKER_FAILURE);
        if (failure instanceof VirtualMachineError fatal) throw fatal;
    }

    @Override
    public void awaitExit() {
        requireOwner();
        Throwable failure = null;
        try {
            if (workerBatch != null) workerBatch.awaitQuiescence();
        } catch (RuntimeException | Error observed) {
            failure = appendFailure(failure, observed);
        }
        try {
            if (coordinator != null) coordinator.awaitExit();
        } catch (RuntimeException | Error observed) {
            failure = appendFailure(failure, observed);
        }
        for (int index16 = 0; index16 < entries.size(); index16++) {
            var entry = entries.get(index16);
            try {
                if (entry.scan != null && entry.scan.barrier != null) entry.scan.barrier.awaitQuiescence();
            } catch (RuntimeException | Error observed) {
                failure = appendFailure(failure, observed);
            }
            try {
                if (entry.policyWork != null && entry.policyWork.original != null)
                    entry.policyWork.original.awaitWorkerExit();
            } catch (RuntimeException | Error observed) {
                failure = appendFailure(failure, observed);
            }
        }
        rethrow(failure);
    }

    @Override
    public boolean finished() {
        requireOwner();
        return finished;
    }

    @Override
    public void cancel(Reason reason) {
        requireOwner();
        cancellation.cancel(Objects.requireNonNull(reason));
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner || executing) {
            throw new IllegalStateException("Network machine execution requires its idle owner");
        }
    }

    record Candidate(MachineInstance machine, MachineLifecycle lifecycle, BooleanSupplier current,
                     @Nullable ControllerExecutionPolicy policy, Set<?> affinities) {
        Candidate(MachineInstance machine, MachineLifecycle lifecycle, BooleanSupplier current) {
            this(machine, lifecycle, current, null, Set.of());
        }
    }

    private static final class Entry {
        private final Candidate candidate;
        private final ReferenceOpenHashSet<MachineWorkSlots> workedHosts = new ReferenceOpenHashSet<>();
        private String affinity;
        @Nullable
        private MachineLifecycle.ScheduledExecution handle;
        @Nullable
        private ComponentMap.TickDispatch<MachineInstance> dispatch;
        private boolean begun;
        private boolean advanced;
        private boolean cursorDone;
        @Nullable
        private ScanWork scan;
        @Nullable
        private PolicyWork policyWork;
        @Nullable
        private MachineLifecycle.ScheduledExecution.WorkerAction worker;
        @Nullable
        private ComponentMap.TickDispatch<MachineInstance>.WorkerDispatch dispatchWorker;

        private Entry(Candidate candidate) {
            this.candidate = candidate;
            affinity = candidate.policy != null && candidate.policy.workAsync()
                ? "pe/controller/" + Integer.toUnsignedString(System.identityHashCode(candidate.machine)) : "";
        }
    }

    private static final class ScanWork {
        private final MachineLifecycle.ScheduledExecution.DeferredScan request;
        private final ObjectArrayList<ResourceBatch.Source> sources = new ObjectArrayList<>();
        private final ObjectArrayList<RecipeWorkBudget> slices = new ObjectArrayList<>();
        @Nullable
        private RecipeScanner.ScanAttempt attempt;
        private RecipeWorkBudget work = new RecipeWorkBudget(SCAN_WORK_PER_ADVANCE);
        @Nullable
        private PlannedTaskExecutor.Batch<Boolean> barrier;
        @Nullable
        private RecipePureComputation computation;
        @Nullable
        private RecipeCatalog.CandidatePass discovery;
        private boolean resolving;
        private int receiptCursor = -1;
        private boolean sourceUnknown;

        private ScanWork(MachineLifecycle.ScheduledExecution.DeferredScan request) {
            this.request = request;
        }
    }

    private final class PolicyWork {
        private final Entry entry;
        private final MachineWorkSlots host;
        private final List<MachineWorkSlots> hosts;
        private final boolean asynchronous;
        private final PlannedTaskExecutor runner;
        @Nullable
        private PlannedRecipeCoordinator original;
        private boolean complete;
        @Nullable
        private Throwable failure;
        @Nullable
        private Reason stopReason;

        private static boolean requiresOwnerThread(List<MachineWorkSlots> hosts) {
            for (int hostIndex = 0; hostIndex < hosts.size(); hostIndex++) {
                var slots = hosts.get(hostIndex).slots();
                for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                    var process = slots.get(slotIndex).process();
                    if (process != null && process.requiresOwnerThread()) return true;
                }
            }
            return false;
        }

        private PolicyWork(Entry entry, MachineWorkSlots host) {
            this.entry = entry;
            this.host = host;
            hosts = entry.candidate.machine instanceof CrossRecipeController ? entry.candidate.machine.recipeHosts() : List.of(host);
            boolean workAsync = Objects.requireNonNull(entry.candidate.policy).workAsync();
            boolean ownerRequired = workAsync && requiresOwnerThread(hosts);
            asynchronous = workAsync && !ownerRequired;
            runner = !workAsync ? tasks.synchronous() : ownerRequired ? tasks : tasks.synchronousSources();
        }

        private void advance(PlannedDeadline cleanupDeadline) {
            if (original == null) original = new PlannedRecipeCoordinator(runner, hosts, forwardDeadline, cancellation,
                () -> !cancellation.cancelled() && entry.candidate.lifecycle.schedulingAllowed());
            try {
                do {
                    original.advance(cleanupDeadline);
                } while (!original.finished() && original.workYielded() && workerAllowed(entry));
            } finally {
                complete = original.finished();
                failure = original.failure();
                stopReason = original.stopReason();
            }
        }
    }
}
