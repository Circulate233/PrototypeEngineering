package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.api.scheduler.SchedulerReport;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedMachineTask;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * Owned ordinary worker pool and serialized affinity lanes. Every tick joins all accepted tasks
 * before returning or throwing. No Future escapes and no accepted Future is cancelled. Failures
 * permanently revoke execution; unregister and close remain available for cleanup. Main callbacks
 * precede concurrent callbacks. Owner-thread main callbacks may synchronously register/unregister:
 * new main tasks wait until the next tick, while concurrent tasks are captured after the main phase.
 * Submissions made during a tick belong to a later tick.
 */
public final class TaskScheduler implements AutoCloseable {
    private final Thread owner;
    private final SchedulerMetrics metrics;
    private final PlannedFactory plannedFactory;
    private final boolean defaultMachineFactory;
    @Nullable
    private final PlannedFactory afterOrdinary;
    private final Reference2ObjectLinkedOpenHashMap<ScheduledTask, Registration> registered = new Reference2ObjectLinkedOpenHashMap<>();
    private final Object submissionLock = new Object();
    private final ObjectArrayFIFOQueue<Runnable> boundaryActions = new ObjectArrayFIFOQueue<>();
    private final ObjectArrayList<PlannedRegistration> plannedCandidates = new ObjectArrayList<>();
    private int workerThreads;
    private int laneCount;
    private SchedulerConfig.Settings effectiveSettings;
    @Nullable
    private SchedulerConfig.Settings pendingSettings;
    @Nullable
    private TickMeasurement measurement;
    private long plannedTimeoutNanos;
    private long tickGeneration;
    private boolean tickOpen;
    @Nullable
    private Runnable tickPreparation;
    private ObjectArrayList<Submission> pending = new ObjectArrayList<>();
    private boolean accepting = true;
    private boolean acceptingBoundary = true;
    // A failed close may leave owned executors alive even though ordinary admission is revoked.
    private boolean boundaryReady = true;
    private boolean closingRequested;
    private boolean faulted;
    private State state = State.IDLE;
    private ExecutorService workers;
    private ExecutorService[] lanes;
    @Nullable
    private PlannedTaskExecutor plannedTasks;
    @Nullable
    private PlannedExecution pendingExecution;
    private boolean pendingExitProven = true;
    @Nullable
    private Reason pendingStopReason;
    private boolean pendingStopConfirmed;

    public TaskScheduler(Thread owner, SchedulerConfig.Settings settings) {
        this(owner, settings, null, null);
    }

    public TaskScheduler(Thread owner, int workerThreads, int laneCount) {
        this(owner, workerThreads, laneCount, null, Duration.ofSeconds(5));
    }

    /**
     * Explicit owner-cycle factories remain supported; null installs the built-in machine entry.
     */
    TaskScheduler(Thread owner, int workerThreads, int laneCount, @Nullable PlannedFactory plannedFactory,
                  Duration plannedTimeout) {
        this(owner, workerThreads, laneCount, plannedFactory, null, plannedTimeout);
    }

    /**
     * Additional work executed after ordinary tasks settle.
     */
    TaskScheduler(Thread owner, int workerThreads, int laneCount, @Nullable PlannedFactory plannedFactory,
                  @Nullable PlannedFactory afterOrdinary, Duration plannedTimeout) {
        this(owner, new SchedulerConfig.Settings(workerThreads, laneCount, plannedTimeout, false, 200, 400),
            plannedFactory, afterOrdinary);
    }

    TaskScheduler(Thread owner, SchedulerConfig.Settings settings, @Nullable PlannedFactory plannedFactory,
                  @Nullable PlannedFactory afterOrdinary) {
        this.owner = Objects.requireNonNull(owner);
        effectiveSettings = Objects.requireNonNull(settings);
        workerThreads = settings.workerThreads();
        laneCount = settings.laneCount();
        metrics = new SchedulerMetrics(settings.metricsWindowTicks());
        defaultMachineFactory = plannedFactory == null;
        this.plannedFactory = defaultMachineFactory
            ? new MachineRecipeExecutionFactory(this::plannedCandidates, this::capturePreparation,
            () -> tickGeneration, () -> tickOpen, _ -> false) : Objects.requireNonNull(plannedFactory);
        this.afterOrdinary = afterOrdinary;
        plannedTimeoutNanos = settings.plannedTimeout().toNanos();
    }

    /**
     * Injected executors transfer ownership to this scheduler and must not cancel accepted work.
     */
    TaskScheduler(Thread owner, int workerThreads, int laneCount, ExecutorService workers, ExecutorService[] lanes) {
        this(owner, workerThreads, laneCount, workers, lanes, null, Duration.ofSeconds(5));
    }

    TaskScheduler(Thread owner, int workerThreads, int laneCount, ExecutorService workers, ExecutorService[] lanes,
                  @Nullable PlannedFactory plannedFactory, Duration plannedTimeout) {
        this(owner, workerThreads, laneCount, workers, lanes, plannedFactory, null, plannedTimeout);
    }

    TaskScheduler(Thread owner, int workerThreads, int laneCount, ExecutorService workers, ExecutorService[] lanes,
                  @Nullable PlannedFactory plannedFactory, @Nullable PlannedFactory afterOrdinary, Duration plannedTimeout) {
        this(owner, workerThreads, laneCount, plannedFactory, afterOrdinary, plannedTimeout);
        Objects.requireNonNull(workers);
        Objects.requireNonNull(lanes);
        if (lanes.length != laneCount) {
            throw new IllegalArgumentException("Injected lane count differs from configuration");
        }
        for (ExecutorService lane : lanes) {
            Objects.requireNonNull(lane);
        }
        this.workers = workers;
        this.lanes = lanes;
    }

    private static void dispatch(ExecutorService executor, Runnable action, ObjectArrayList<FutureTask<Void>> futures) {
        var future = new FutureTask<Void>(action, null);
        executor.execute(future);
        futures.add(future);
    }

    private static void runGroup(Group group) {
        for (int index1 = 0; index1 < group.tasks.size(); index1++) {
            ScheduledTask task = group.tasks.get(index1);
            task.onSchedule();
        }
    }

    private static void requireOrdinary(ExecutionMode mode) {
        Objects.requireNonNull(mode);
        if (mode == ExecutionMode.PLANNED_PARALLEL) {
            throw new IllegalArgumentException("PLANNED_PARALLEL does not accept Runnable tasks");
        }
    }

    private static ExecutionMode requireScheduledMode(ScheduledTask task) {
        var mode = Objects.requireNonNull(task.executionMode());
        if (mode == ExecutionMode.PLANNED_PARALLEL) {
            if (!(task instanceof PlannedMachineTask host)) {
                throw new IllegalArgumentException("PLANNED_PARALLEL requires PlannedMachineTask");
            }
            var machine = host.plannedMachine();
            if (machine == null || machine.lifecycle() == null) {
                throw new IllegalArgumentException("PLANNED_PARALLEL requires a real lifecycle-bound machine");
            }
        }
        return mode;
    }

    private static boolean containsOwner(List<StructureBoundary> entries, Object ownerIdentity) {
        for (int index13 = 0; index13 < entries.size(); index13++) {
            var entry = entries.get(index13);
            if (entry.ownerIdentity == ownerIdentity) return true;
        }
        return false;
    }

    /**
     * Registration is by task identity; a duplicate registration keeps its original position.
     */
    public void register(ScheduledTask task) {
        State previous = enterMutation(true);
        try {
            Objects.requireNonNull(task);
            requireScheduledMode(task);
            if (!registered.containsKey(task)) {
                registered.put(task, new Registration(task));
            }
        } finally {
            state = previous;
        }
    }

    public void unregister(ScheduledTask task) {
        State previous = enterMutation(false);
        try {
            registered.remove(Objects.requireNonNull(task));
        } finally {
            state = previous;
        }
    }

    public int registeredCount() {
        requireOwner();
        return registered.size();
    }

    /**
     * Exact joined owner window, for publishing prepared definitions without starting another scheduler.
     */
    public void requireOwnerBoundary() {
        requireOwner();
        if (state != State.BOUNDARY || !boundaryReady) {
            throw new IllegalStateException("Scheduler requires its ready owner boundary");
        }
    }

    public SchedulerConfig.Settings effectiveSettings() {
        requireOwner();
        return effectiveSettings;
    }

    @Nullable
    public SchedulerConfig.Settings pendingSettings() {
        requireOwner();
        return pendingSettings;
    }

    public String policyLabel() {
        requireOwner();
        return effectiveSettings.workerStrategy().policyLabel();
    }

    /**
     * Real admission/cleanup state; a queued settings request is reported separately.
     */
    public Status status() {
        requireOwner();
        return new Status(policyLabel(), registered.size(), pendingExecution != null, boundaryReady,
            closingRequested, faulted, state == State.CLOSED);
    }

    public SchedulerReport snapshotReport() {
        requireOwner();
        return metrics.snapshotReport(policyLabel());
    }

    /**
     * Owner requests only enqueue. Even an idle request waits for a later joined tick entry.
     */
    public void requestSettings(SchedulerConfig.Settings settings) {
        requireOwner();
        if (state != State.IDLE && state != State.MAIN && state != State.BOUNDARY && state != State.PLANNED)
            throw new IllegalStateException("Settings cannot be requested from owner metadata capture");
        requireHealthy();
        pendingSettings = Objects.requireNonNull(settings);
    }

    private void applySettings(SchedulerConfig.Settings eligible, Failures failures) {
        if (pendingSettings != eligible || pendingExecution != null || !boundaryReady || closingRequested || faulted)
            return;
        if (workerThreads != eligible.workerThreads() || laneCount != eligible.laneCount()
            || effectiveSettings.workerStrategy() != eligible.workerStrategy()) {
            // This is retirement, not closure: registrations, submissions, boundaries and admission survive.
            boundaryReady = false;
            boundaryReady = stopExecutors(failures);
            failures.throwIfPresent();
            if (!boundaryReady) throw new IllegalStateException("Previous scheduler executors did not terminate");
            workers = null;
            lanes = null;
            plannedTasks = null;
        }
        workerThreads = eligible.workerThreads();
        laneCount = eligible.laneCount();
        plannedTimeoutNanos = eligible.plannedTimeout().toNanos();
        metrics.resizeIfNeeded(eligible.metricsWindowTicks());
        effectiveSettings = eligible;
        metrics.beginTick(policyLabel());
        pendingSettings = null;
    }

    /**
     * Thread-safe Runnable enqueue; PLANNED_PARALLEL requires a typed scheduled host instead.
     */
    public void submit(Runnable task, ExecutionMode mode) {
        Objects.requireNonNull(task);
        requireOrdinary(mode);
        synchronized (submissionLock) {
            if (!accepting || faulted) {
                throw new IllegalStateException("Scheduler is closed, closing or faulted");
            }
            pending.add(new Submission(task, mode));
        }
    }

    /**
     * Accepts an owner action for the next boundary after accepted work has physically exited.
     * Foreign threads only enqueue and never wait or execute the action. An idle owner drains
     * immediately; a main callback drains after it returns, and worker requests drain after all
     * accepted workers are joined. Nested boundary requests enqueue without recursive execution.
     * Accepted actions are attempted once, including after ordinary execution has faulted. Close
     * drains all accepted actions after executor termination and atomically closes admission;
     * requests after that point throw. A failed close without proof of termination retains the
     * actions until close can be retried. Owner metadata callbacks cannot request a boundary.
     */
    public void runAtBoundary(Runnable action) {
        Objects.requireNonNull(action);
        boolean ownerRequest = Thread.currentThread() == owner;
        if (ownerRequest && state != State.IDLE && state != State.MAIN && state != State.PLANNED && state != State.BOUNDARY) {
            throw new IllegalStateException("Scheduler boundary cannot be requested from this owner phase");
        }
        synchronized (submissionLock) {
            if (!acceptingBoundary) {
                throw new IllegalStateException("Scheduler owner boundary is closed");
            }
            boundaryActions.enqueue(action);
        }
        if (ownerRequest && state == State.IDLE && boundaryReady) {
            var failures = new Failures();
            try {
                failures.observeInterrupt();
                if (failures.present()) {
                    fault();
                }
                drainBoundary(failures, false);
            } finally {
                failures.restoreInterrupt();
            }
            failures.throwIfPresent();
        }
    }

    /**
     * Enqueues periodic structure preparation without draining an idle scheduler. Consecutive
     * entries share a worker batch at the next owner boundary; an intervening ordinary boundary
     * remains an ordering fence. First-load checks continue to use runAtBoundary directly.
     */
    public void enqueueStructureCheck(Supplier<StructureWork> prepare) {
        enqueueStructureCheck(prepare, () -> {
        });
    }

    /**
     * Owner observation follows this check without inserting a fence between adjacent checks.
     */
    public void enqueueStructureCheck(Supplier<StructureWork> prepare, Runnable completed) {
        enqueueStructureCheck(prepare, prepare, completed);
    }

    /**
     * Repeated submissions of one exact owner retain FIFO order and cannot share a worker batch.
     */
    public void enqueueStructureCheck(Object ownerIdentity, Supplier<StructureWork> prepare, Runnable completed) {
        requireOwner();
        Objects.requireNonNull(ownerIdentity);
        Objects.requireNonNull(prepare);
        Objects.requireNonNull(completed);
        if (state != State.IDLE && state != State.MAIN && state != State.PLANNED && state != State.BOUNDARY)
            throw new IllegalStateException("Structure preparation cannot be requested from this owner phase");
        synchronized (submissionLock) {
            if (!acceptingBoundary) throw new IllegalStateException("Scheduler owner boundary is closed");
            boundaryActions.enqueue(new StructureBoundary(ownerIdentity, prepare, completed));
        }
    }

    private void runStructureBatch(List<StructureBoundary> entries) {
        // Allocate all owner bookkeeping before any preparation may borrow native read ports.
        var prepared = new ObjectArrayList<StructureWork>(entries.size());
        var next = new ObjectArrayList<StructureWork>(entries.size());
        var continuations = new ObjectArrayList<Supplier<StructureWork>>(entries.size());
        var jobs = new ObjectArrayList<PlannedTaskExecutor.Job<Boolean>>(entries.size());
        var outcomes = new ObjectArrayList<PlannedTaskExecutor.Result<Boolean>>(entries.size());
        var failures = new Failures();
        for (int index16 = 0; index16 < entries.size(); index16++) {
            var entry = entries.get(index16);
            try {
                var work = entry.prepare.get();
                if (work != null) prepared.add(work);
            } catch (RuntimeException | Error failure) {
                failures.add(failure);
            }
        }
        while (!prepared.isEmpty()) {
            List<PlannedTaskExecutor.Result<Boolean>> results = null;
            Throwable dispatchFailure = null;
            try {
                jobs.clear();
                long now = System.nanoTime();
                long longest = 1;
                for (int index8 = 0; index8 < prepared.size(); index8++) {
                    var work = prepared.get(index8);
                    longest = Math.max(longest, work.deadline.remainingNanos(now));
                    // Each action keeps its own original deadline and work allowance. The envelope
                    // only bounds dispatch/join; a shorter attempt cannot cancel another host.
                    jobs.add(new PlannedTaskExecutor.Job<>(null, work.action, work.deadline));
                }
                results = runStructureWorkers(jobs, new PlannedDeadline(now, longest));
            } catch (RuntimeException | Error failure) {
                dispatchFailure = failure;
                failures.add(failure);
            }
            outcomes.clear();
            for (int index = 0; index < prepared.size(); index++) {
                var result = results == null
                    ? new PlannedTaskExecutor.Failure<Boolean>(Objects.requireNonNull(dispatchFailure)) : results.get(index);
                try {
                    prepared.get(index).joined.accept(result);
                } catch (RuntimeException | Error failure) {
                    failures.add(failure);
                    result = new PlannedTaskExecutor.Failure<>(failure);
                }
                outcomes.add(result);
            }
            // Every borrowed read port is closed before any owner discovers, publishes or opens
            // the next round. Cleanup failures cannot skip another host's compulsory close.
            continuations.clear();
            for (int index = 0; index < prepared.size(); index++) {
                try {
                    var continued = prepared.get(index).completed.apply(outcomes.get(index));
                    if (continued != null) continuations.add(continued);
                } catch (RuntimeException | Error failure) {
                    failures.add(failure);
                }
            }
            // Finish every owner transition before any continuation can borrow the next windows.
            next.clear();
            for (int index6 = 0; index6 < continuations.size(); index6++) {
                var continuation = continuations.get(index6);
                try {
                    var continued = continuation.get();
                    if (continued != null) next.add(continued);
                } catch (RuntimeException | Error failure) {
                    failures.add(failure);
                }
            }
            var completed = prepared;
            prepared = next;
            next = completed;
        }
        for (int index15 = 0; index15 < entries.size(); index15++) {
            var entry = entries.get(index15);
            try {
                entry.completed.run();
            } catch (RuntimeException | Error failure) {
                failures.add(failure);
            }
        }
        failures.throwIfPresent();
    }

    /**
     * Executes one explicitly safe structure computation through the existing planned worker runner.
     * Only the exclusive owner boundary may borrow loaded references. The owner does not resume any
     * World/source/ordinary work until every accepted action has physically exited, including after
     * stopped/failed dispatch or interruption. The exact batch is retained before dispatch.
     */
    public <T> PlannedTaskExecutor.Result<T> runStructureWorker(Supplier<? extends T> action, PlannedDeadline deadline) {
        return runStructureWorkers(List.of(new PlannedTaskExecutor.Job<T>(null, action)), deadline).getFirst();
    }

    private <T> List<PlannedTaskExecutor.Result<T>> runStructureWorkers(List<PlannedTaskExecutor.Job<T>> jobs, PlannedDeadline deadline) {
        requireOwner();
        Objects.requireNonNull(jobs);
        Objects.requireNonNull(deadline);
        if (state != State.BOUNDARY || !boundaryReady)
            throw new IllegalStateException("Structure worker requires the exclusive joined owner boundary");
        requireHealthy();
        initializeExecutors();
        if (plannedTasks == null) plannedTasks = new PlannedTaskExecutor(workers, lanes);
        class Borrow {
            private PlannedTaskExecutor.Batch<T> batch;
        }
        var borrowed = new Borrow();
        long structureStart = System.nanoTime();
        boundaryReady = false;
        Throwable primary = null;
        try {
            plannedTasks.runPrepare(jobs, deadline, batch -> borrowed.batch = batch);
            var results = Objects.requireNonNull(borrowed.batch).results();
            for (int index14 = 0; index14 < results.size(); index14++) {
                var result = results.get(index14);
                if (result instanceof PlannedTaskExecutor.Failure<T>(var failure) && primary == null) primary = failure;
            }
            return results;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            long cleanupStart = System.nanoTime();
            try {
                if (borrowed.batch != null) borrowed.batch.awaitQuiescence();
            } catch (RuntimeException | Error cleanup) {
                if (primary == null) throw cleanup;
                if (primary != cleanup) primary.addSuppressed(cleanup);
            } finally {
                // awaitQuiescence reports interruption only after actual exit. Never release an
                // uncertain live read merely because the ordered batch result became terminal.
                boundaryReady = borrowed.batch == null || borrowed.batch.quiescent();
                if (measurement != null) {
                    measurement.cleanupWallNanos += System.nanoTime() - cleanupStart;
                    measurement.structureWorkerWallNanos += System.nanoTime() - structureStart;
                }
            }
        }
    }

    public void tick() {
        tick(() -> {
        });
    }

    /**
     * Prepares current authority on the owner before each executed main action and before concurrent
     * metadata capture. Formal machine execution also refreshes before its owner component
     * boundaries, using this original callback throughout the captured tick. Preparation has
     * exclusive ownership and cannot recursively tick, close,
     * mutate scheduler registrations or request an owner boundary. Thread-safe submissions still
     * belong to a later tick. A preparation failure faults execution and follows the normal join
     * and boundary-cleanup path; neither preparation nor the rejected action is retried.
     */
    public void tick(Runnable prepare) {
        Objects.requireNonNull(prepare);
        enterTick();
        var eligibleSettings = pendingSettings; // Requests made during this tick cannot install in it.
        var tickMeasurement = new TickMeasurement(System.nanoTime());
        measurement = tickMeasurement;
        metrics.beginTick(policyLabel());
        tickPreparation = prepare;
        var failures = new Failures();
        var futures = new ObjectArrayList<FutureTask<Void>>();
        boolean capturePlanned = false;
        boolean resumingClose = closingRequested && pendingExecution != null;
        try {
            failures.observeInterrupt();
            if (failures.present()) {
                fault();
            }
            if (pendingExecution != null) {
                drivePending(failures, false);
            }
            failures.observeInterrupt();
            if (failures.present()) {
                fault();
            }
            drainBoundary(failures, false);
            failures.throwIfPresent();
            if (pendingExecution == null && !resumingClose) {
                requireHealthy();
                if (eligibleSettings != null) applySettings(eligibleSettings, failures);
                if (pendingExecution == null) {
                    requireHealthy();
                    executeOrdinary(prepare, futures, failures);
                    capturePlanned = true;
                }
            }
        } catch (Throwable failure) {
            failures.add(failure);
            fault();
        } finally {
            state = State.JOINING;
            long joinStart = System.nanoTime();
            for (int index10 = 0; index10 < futures.size(); index10++) {
                FutureTask<Void> future = futures.get(index10);
                boolean completed = false;
                while (!completed) {
                    try {
                        future.get();
                        completed = true;
                    } catch (InterruptedException failure) {
                        failures.interrupted(failure);
                        fault();
                    } catch (ExecutionException failure) {
                        failures.add(Objects.requireNonNull(failure.getCause()));
                        fault();
                        completed = true;
                    }
                }
            }
            failures.observeInterrupt();
            if (failures.present()) {
                fault();
            }
            if (!futures.isEmpty()) tickMeasurement.joinWallNanos += System.nanoTime() - joinStart;
            tickMeasurement.finishOrdinary();
            if (capturePlanned && !failures.present()) {
                try {
                    capturePlanned(prepare);
                } catch (Throwable failure) {
                    failures.add(failure);
                    fault();
                }
                if (pendingExecution != null) {
                    drivePending(failures, true);
                }
            }
            failures.observeInterrupt();
            if (failures.present()) {
                fault();
            }
            if (capturePlanned && !failures.present() && pendingExecution == null && afterOrdinary != null) {
                try {
                    capturePlanned(afterOrdinary);
                    if (pendingExecution != null) drivePending(failures, true);
                } catch (Throwable failure) {
                    failures.add(failure);
                    fault();
                }
            }
            drainBoundary(failures, false);
            tickOpen = false;
            tickPreparation = null;
            state = State.IDLE;
            failures.restoreInterrupt();
            measurement = null;
            metrics.record(tickMeasurement.sample(System.nanoTime()));
            metrics.maybeLog(effectiveSettings.metricsLogIntervalTicks(), effectiveSettings.metricsEnabled(),
                report -> PrototypeEngineering.LOGGER.info(report.toLogLine()));
        }
        failures.throwIfPresent();
    }

    private void executeOrdinary(Runnable prepare, ObjectArrayList<FutureTask<Void>> futures, Failures failures) {
        var current = Objects.requireNonNull(measurement);
        current.mainStart = System.nanoTime();
        try {
            plannedCandidates.clear();
            ObjectArrayList<Submission> batch;
            synchronized (submissionLock) {
                batch = pending;
                pending = new ObjectArrayList<>();
            }
            var main = new ObjectArrayList<Registration>(registered.size());
            for (Registration registration : registered.values()) {
                if (requireScheduledMode(registration.task) == ExecutionMode.MAIN_THREAD) {
                    main.add(registration);
                }
            }
            var executed = new ReferenceOpenHashSet<ScheduledTask>(batch.size());
            state = State.MAIN;
            for (int index4 = 0; index4 < batch.size(); index4++) {
                Submission submission = batch.get(index4);
                if (submission.mode == ExecutionMode.MAIN_THREAD) {
                    runMain(submission.task, prepare, failures, true);
                }
            }
            for (int index11 = 0; index11 < main.size(); index11++) {
                Registration registration = main.get(index11);
                ScheduledTask task = registration.task;
                if (registered.get(task) != registration) {
                    continue;
                }
                boolean active;
                ExecutionMode mode;
                state = State.CAPTURING_MAIN;
                try {
                    mode = requireScheduledMode(task);
                    active = task.active();
                } finally {
                    state = State.MAIN;
                }
                if (mode == ExecutionMode.MAIN_THREAD && active) {
                    executed.add(task);
                    runMain(task::onSchedule, prepare, failures, false);
                }
            }
            current.concurrentStart = System.nanoTime();
            current.mainWallNanos += current.concurrentStart - current.mainStart;
            current.mainStart = 0;
            state = State.CAPTURING_CONCURRENT;
            prepare.run();
            var concurrent = new ObjectArrayList<Snapshot>(registered.size());
            for (Registration registration : registered.values()) {
                ScheduledTask task = registration.task;
                ExecutionMode mode = requireScheduledMode(task);
                if (mode == ExecutionMode.MAIN_THREAD || executed.contains(task) || !task.active()) {
                    continue;
                }
                if (mode == ExecutionMode.PLANNED_PARALLEL || plannedFactory.owns(task)) {
                    plannedCandidates.add(new PlannedRegistration(registration, mode));
                    continue;
                }
                var keys = new ObjectOpenHashSet<>();
                for (Object key : Objects.requireNonNull(task.affinityKeys())) {
                    keys.add(Objects.requireNonNull(key));
                }
                concurrent.add(new Snapshot(task, keys));
            }
            ObjectArrayList<Group> groups = group(concurrent);
            int customConcurrent = 0;
            for (int index2 = 0; index2 < batch.size(); index2++) {
                Submission submission = batch.get(index2);
                if (submission.mode == ExecutionMode.CONCURRENT) {
                    customConcurrent++;
                }
            }
            futures.ensureCapacity(Math.addExact(customConcurrent, groups.size()));
            state = State.DISPATCHING;
            if (customConcurrent != 0 || !groups.isEmpty()) {
                initializeExecutors();
                for (int index5 = 0; index5 < batch.size(); index5++) {
                    Submission submission = batch.get(index5);
                    if (submission.mode == ExecutionMode.CONCURRENT) {
                        dispatch(workers, submission.task, futures);
                        current.customConcurrentTasks++;
                    }
                }
                for (int index7 = 0; index7 < groups.size(); index7++) {
                    Group group = groups.get(index7);
                    ExecutorService executor = group.lane < 0 ? workers : lanes[group.lane];
                    dispatch(executor, () -> runGroup(group), futures);
                    current.concurrentTasks += group.tasks.size();
                    current.affinityGroups++;
                }
            }
        } finally {
            if (current.mainStart != 0) {
                current.mainWallNanos += System.nanoTime() - current.mainStart;
                current.mainStart = 0;
            }
        }
    }

    private void runMain(Runnable action, Runnable prepare, Failures failures, boolean custom) {
        try {
            state = State.CAPTURING_MAIN;
            prepare.run();
            state = State.MAIN;
            var current = Objects.requireNonNull(measurement);
            if (custom) current.customMainTasks++;
            else current.mainTasks++;
            action.run();
        } catch (Throwable failure) {
            failures.add(failure);
            fault();
        } finally {
            drainBoundary(failures, false);
        }
        failures.throwIfPresent();
    }

    private Runnable capturePreparation() {
        var captured = Objects.requireNonNull(tickPreparation);
        return () -> {
            State previous = state;
            state = State.CAPTURING_PLANNED;
            try {
                captured.run();
            } finally {
                state = previous;
            }
        };
    }

    private void capturePlanned(Runnable prepare) {
        if (defaultMachineFactory && plannedCandidates.isEmpty()) {
            return;
        }
        state = State.CAPTURING_PLANNED;
        prepare.run();
        capturePlanned(plannedFactory);
    }

    private void capturePlanned(PlannedFactory factory) {
        state = State.CAPTURING_PLANNED;
        initializeExecutors();
        if (plannedTasks == null) {
            plannedTasks = new PlannedTaskExecutor(workers, lanes);
        }
        long plannedStart = System.nanoTime();
        PlannedExecution captured;
        try {
            captured = factory.capture(plannedTasks, cleanupDeadline());
        } finally {
            if (measurement != null) measurement.plannedWallNanos += System.nanoTime() - plannedStart;
        }
        if (captured != null) {
            // Publication precedes the first callback or asynchronous action in this execution.
            pendingExecution = captured;
            boundaryReady = false;
            pendingExitProven = true;
            pendingStopReason = null;
            pendingStopConfirmed = false;
        }
    }

    /**
     * Exact registrations captured before ordinary dispatch, rechecked after every worker joined.
     */
    List<PlannedCandidate> plannedCandidates() {
        requireOwner();
        if (state != State.CAPTURING_PLANNED) {
            throw new IllegalStateException("Planned candidates require owner capture");
        }
        var result = new ObjectArrayList<PlannedCandidate>();
        for (int index9 = 0; index9 < plannedCandidates.size(); index9++) {
            var captured = plannedCandidates.get(index9);
            var registration = captured.registration;
            var task = registration.task;
            var mode = captured.mode;
            BooleanSupplier current = () -> registered.get(task) == registration && task.active()
                && task.executionMode() == mode;
            if (current.getAsBoolean()) {
                result.add(new PlannedCandidate(task, mode, current));
            }
        }
        return result;
    }

    /**
     * One recovery pass; an initial forward attempt additionally settles its cancelled remainder.
     */
    private void drivePending(Failures failures, boolean initial) {
        long plannedStart = System.nanoTime();
        State previous = state;
        state = State.PLANNED;
        try {
            if (!initial && !pendingStopConfirmed) {
                cancelPending(failures, Reason.EPOCH_CHANGED);
                if (!pendingStopConfirmed) {
                    awaitPending(failures);
                    return;
                }
            }
            if (!pendingExitProven) {
                awaitPending(failures);
                if (!pendingExitProven) {
                    return;
                }
            }
            advancePending(failures);
            boolean finished = pendingFinished(failures);
            if (!finished) {
                cancelPending(failures, failures.present() ? Reason.WORKER_FAILURE : Reason.EPOCH_CHANGED);
            }
            awaitPending(failures);
            if (initial && pendingExitProven && pendingStopConfirmed && !finished) {
                // The forward budget never renews. This pass only confirms or compensates after exit.
                advancePending(failures);
                finished = pendingFinished(failures);
                awaitPending(failures);
            }
            if (pendingExitProven && finished) {
                pendingExecution = null;
                boundaryReady = !closingRequested;
            }
        } finally {
            state = previous;
            if (measurement != null) measurement.plannedWallNanos += System.nanoTime() - plannedStart;
        }
    }

    private void advancePending(Failures failures) {
        pendingExitProven = false;
        try {
            Objects.requireNonNull(pendingExecution).advance(cleanupDeadline());
        } catch (Throwable failure) {
            failures.add(failure);
            fault();
        }
        failures.observeInterrupt();
        if (failures.present()) {
            fault();
        }
    }

    private boolean pendingFinished(Failures failures) {
        State previous = state;
        state = State.CAPTURING_PLANNED;
        try {
            return Objects.requireNonNull(pendingExecution).finished();
        } catch (Throwable failure) {
            failures.add(failure);
            fault();
            return false;
        } finally {
            state = previous;
        }
    }

    private void cancelPending(Failures failures, Reason reason) {
        if (pendingStopConfirmed) {
            return;
        }
        if (pendingStopReason == null) {
            pendingStopReason = reason;
        }
        State previous = state;
        state = State.PLANNED;
        long cleanupStart = System.nanoTime();
        try {
            Objects.requireNonNull(pendingExecution).cancel(pendingStopReason);
            pendingStopConfirmed = true;
        } catch (Throwable failure) {
            failures.add(failure);
            fault();
        } finally {
            state = previous;
            if (measurement != null) measurement.cleanupWallNanos += System.nanoTime() - cleanupStart;
        }
    }

    private void awaitPending(Failures failures) {
        long cleanupStart = System.nanoTime();
        try {
            Objects.requireNonNull(pendingExecution).awaitExit();
            pendingExitProven = true;
        } catch (Throwable failure) {
            failures.add(failure);
            fault();
            // Interrupted implementations report only after exit; retry after clearing the flag
            // to obtain a normal return as the explicit proof, retaining the original failure.
            boolean interrupted = Thread.currentThread().isInterrupted();
            failures.observeInterrupt();
            if (interrupted) {
                try {
                    Objects.requireNonNull(pendingExecution).awaitExit();
                    pendingExitProven = true;
                } catch (Throwable retryFailure) {
                    failures.add(retryFailure);
                    failures.observeInterrupt();
                }
            }
        } finally {
            if (measurement != null) measurement.cleanupWallNanos += System.nanoTime() - cleanupStart;
        }
    }

    private PlannedDeadline cleanupDeadline() {
        return new PlannedDeadline(System.nanoTime(), plannedTimeoutNanos);
    }

    private void drainBoundary(Failures failures, boolean closeAdmission) {
        if (!boundaryReady) {
            return;
        }
        State previous = state;
        state = State.BOUNDARY;
        try {
            while (true) {
                Runnable action;
                synchronized (submissionLock) {
                    if (boundaryActions.isEmpty()) {
                        if (closeAdmission) {
                            acceptingBoundary = false;
                        }
                        return;
                    }
                    action = boundaryActions.dequeue();
                }
                try {
                    action.run();
                } catch (Throwable failure) {
                    failures.add(failure);
                    fault();
                }
                failures.observeInterrupt();
                if (failures.present()) {
                    fault();
                }
            }
        } finally {
            state = previous;
        }
    }

    private ObjectArrayList<Group> group(ObjectArrayList<Snapshot> tasks) {
        var union = new UnionFind(tasks.size());
        var indexes = new Object2IntOpenHashMap<>();
        indexes.defaultReturnValue(-1);
        for (int index = 0; index < tasks.size(); index++) {
            for (Object key : tasks.get(index).keys) {
                int previous = indexes.putIfAbsent(key, index);
                if (previous >= 0) {
                    union.union(index, previous);
                }
            }
        }
        var components = new Int2ObjectLinkedOpenHashMap<IntArrayList>();
        for (int index = 0; index < tasks.size(); index++) {
            components.computeIfAbsent(union.find(index), _ -> new IntArrayList()).add(index);
        }
        var groups = new ObjectArrayList<Group>(components.size());
        for (IntArrayList component : components.values()) {
            var members = new ObjectArrayList<ScheduledTask>(component.size());
            var keys = new ObjectOpenHashSet<>();
            for (int member = 0; member < component.size(); member++) {
                int index = component.getInt(member);
                Snapshot snapshot = tasks.get(index);
                members.add(snapshot.task);
                keys.addAll(snapshot.keys);
            }
            int lane = -1;
            if (!keys.isEmpty()) {
                int[] hashes = new int[keys.size()];
                int index = 0;
                for (Object key : keys) {
                    hashes[index++] = key.hashCode();
                }
                Arrays.sort(hashes);
                int hash = 1;
                for (int value : hashes) {
                    hash = 31 * hash + value;
                }
                lane = (hash & Integer.MAX_VALUE) % laneCount;
            }
            groups.add(new Group(members, lane));
        }
        return groups;
    }

    @SuppressWarnings("resource") // Executors are owned by close(), including cleanup of partially created arrays.
    private void initializeExecutors() {
        if (workers != null && lanes != null) {
            return;
        }
        try {
            boolean virtual = effectiveSettings.workerStrategy() == SchedulerConfig.WorkerStrategy.VIRTUAL_PER_TASK;
            var workerFactory = virtual
                ? Thread.ofVirtual().name("PrototypeEngineering-Scheduler-Worker-", 1).factory()
                : Thread.ofPlatform().daemon().name("PrototypeEngineering-Scheduler-Worker-", 1).factory();
            workers = virtual ? Executors.newThreadPerTaskExecutor(workerFactory)
                : Executors.newFixedThreadPool(workerThreads, workerFactory);
            lanes = new ExecutorService[laneCount];
            for (int lane = 0; lane < laneCount; lane++) {
                var laneFactory = virtual
                    ? Thread.ofVirtual().name("PrototypeEngineering-Scheduler-Lane-" + lane + "-", 1).factory()
                    : Thread.ofPlatform().daemon().name("PrototypeEngineering-Scheduler-Lane-" + lane + "-", 1).factory();
                lanes[lane] = Executors.newSingleThreadExecutor(laneFactory);
            }
        } catch (Throwable failure) {
            synchronized (submissionLock) {
                accepting = false;
            }
            var failures = new Failures();
            failures.add(failure);
            boundaryReady = stopExecutors(failures);
            failures.restoreInterrupt();
            failures.throwIfPresent();
        }
    }

    /**
     * Requires completed cleanup; use tryClose when a retained execution may still be pending.
     */
    @Override
    public void close() {
        if (!tryClose()) {
            throw new IllegalStateException("Scheduler retains pending planned cleanup; retry tryClose after confirmation");
        }
    }

    /**
     * Closes admission and attempts retained cleanup before shutting down its original lanes. False
     * retains the execution, executors and owner actions for a later attempt, without a runtime fault.
     * Actual failures still throw after the available cleanup and never discard pending ownership.
     */
    public boolean tryClose() {
        requireOwner();
        if (state == State.CLOSED) {
            return true;
        }
        if (state != State.IDLE) {
            throw new IllegalStateException("Scheduler boundary is already active");
        }
        state = State.CLOSING;
        closingRequested = true;
        synchronized (submissionLock) {
            accepting = false;
            pending.clear();
        }
        var failures = new Failures();
        boundaryReady = false;
        try {
            failures.observeInterrupt();
            if (pendingExecution != null) {
                cancelPending(failures, Reason.SERVER_STOP);
                drivePending(failures, false);
            }
            if (pendingExecution == null) {
                boundaryReady = false;
                boundaryReady = stopExecutors(failures);
            }
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            failures.observeInterrupt();
            if (failures.present()) {
                fault();
            }
            drainBoundary(failures, true);
            state = boundaryReady ? State.CLOSED : State.IDLE;
            if (boundaryReady) {
                registered.clear();
            }
            failures.restoreInterrupt();
        }
        failures.throwIfPresent();
        return state == State.CLOSED;
    }

    private boolean stopExecutors(Failures failures) {
        var executors = new ObjectArrayList<ExecutorService>(lanes == null ? 1 : lanes.length + 1);
        if (workers != null) {
            executors.add(workers);
        }
        if (lanes != null) {
            for (ExecutorService lane : lanes) {
                if (lane != null) {
                    executors.add(lane);
                }
            }
        }
        for (int index3 = 0; index3 < executors.size(); index3++) {
            ExecutorService executor = executors.get(index3);
            try {
                executor.shutdown();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        boolean quiescent = true;
        for (int index12 = 0; index12 < executors.size(); index12++) {
            ExecutorService executor = executors.get(index12);
            try {
                if (executor.isTerminated()) {
                    continue;
                }
                if (!executor.isShutdown()) {
                    throw new IllegalStateException("Owned executor did not shut down");
                }
                boolean terminated = false;
                while (!terminated) {
                    try {
                        terminated = executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException failure) {
                        failures.interrupted(failure);
                    }
                }
            } catch (Throwable failure) {
                failures.add(failure);
                quiescent = false;
            }
        }
        return quiescent;
    }

    private void fault() {
        synchronized (submissionLock) {
            faulted = true;
            pending.clear();
        }
    }

    private void enterTick() {
        requireOwnerIdle();
        tickGeneration = Math.addExact(tickGeneration, 1);
        tickOpen = true;
        state = State.CAPTURING_MAIN;
    }

    private void requireHealthy() {
        synchronized (submissionLock) {
            if (!accepting || faulted) {
                throw new IllegalStateException("Scheduler is closed, closing or faulted");
            }
        }
    }

    private State enterMutation(boolean requireHealthy) {
        requireOwner();
        if (state != State.IDLE && state != State.MAIN && state != State.BOUNDARY) {
            throw new IllegalStateException("Scheduler boundary is already active");
        }
        synchronized (submissionLock) {
            if (requireHealthy && (!accepting || faulted)) {
                throw new IllegalStateException("Scheduler is closed, closing or faulted");
            }
        }
        State previous = state;
        state = State.REGISTERING;
        return previous;
    }

    private void requireOwnerIdle() {
        requireOwner();
        if (state != State.IDLE) {
            throw new IllegalStateException("Scheduler boundary is already active");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Scheduler requires its owning thread");
        }
    }

    private enum State {IDLE, REGISTERING, CAPTURING_MAIN, MAIN, CAPTURING_CONCURRENT, DISPATCHING, JOINING, CAPTURING_PLANNED, PLANNED, BOUNDARY, CLOSING, CLOSED}

    /**
     * Internal machine-cycle construction. Return without callbacks, worker dispatch or partial
     * ownership acquisition; all such work belongs in advance after scheduler publication.
     */
    @FunctionalInterface
    interface PlannedFactory {
        /**
         * Controls ordinary CONCURRENT auto-admission; explicit formal registrations remain typed.
         */
        default boolean owns(ScheduledTask task) {
            return false;
        }

        @Nullable PlannedExecution capture(PlannedTaskExecutor tasks, PlannedDeadline forwardDeadline);
    }

    public record Status(String policyLabel, int registeredCount, boolean pendingExecution, boolean boundaryReady,
                         boolean closing, boolean faulted, boolean closed) {
    }

    /**
     * One detached computation. After whole-batch exit, all joined callbacks close their windows,
     * then all completed callbacks finish owner transitions. A returned supplier prepares the next
     * round only after every completed callback has returned; it must not prepare eagerly.
     */
    public record StructureWork(PlannedDeadline deadline, Supplier<Boolean> action,
                                Consumer<PlannedTaskExecutor.Result<Boolean>> joined,
                                Function<PlannedTaskExecutor.Result<Boolean>, Supplier<StructureWork>> completed) {
        public StructureWork(PlannedDeadline deadline, Supplier<Boolean> action,
                             Function<PlannedTaskExecutor.Result<Boolean>, Supplier<StructureWork>> completed) {
            this(deadline, action, _ -> {
            }, completed);
        }

        public StructureWork {
            Objects.requireNonNull(deadline);
            Objects.requireNonNull(action);
            Objects.requireNonNull(joined);
            Objects.requireNonNull(completed);
        }
    }

    private static final class TickMeasurement {
        private final long tickStart;
        private long mainStart;
        private long concurrentStart;
        private long mainWallNanos;
        private long concurrentStageWallNanos;
        private long joinWallNanos;
        private long plannedWallNanos;
        private long cleanupWallNanos;
        private long structureWorkerWallNanos;
        private long mainTasks;
        private long concurrentTasks;
        private long customMainTasks;
        private long customConcurrentTasks;
        private long affinityGroups;

        private TickMeasurement(long tickStart) {
            this.tickStart = tickStart;
        }

        private void finishOrdinary() {
            long now = System.nanoTime();
            if (mainStart != 0) {
                mainWallNanos += now - mainStart;
                mainStart = 0;
            }
            if (concurrentStart != 0) {
                concurrentStageWallNanos += now - concurrentStart;
                concurrentStart = 0;
            }
        }

        private SchedulerMetrics.Sample sample(long end) {
            return new SchedulerMetrics.Sample(end - tickStart, mainWallNanos, concurrentStageWallNanos, joinWallNanos,
                plannedWallNanos, cleanupWallNanos, structureWorkerWallNanos, mainTasks, concurrentTasks,
                customMainTasks, customConcurrentTasks, affinityGroups);
        }
    }

    record PlannedCandidate(ScheduledTask task, ExecutionMode mode, BooleanSupplier current) {
    }

    private record PlannedRegistration(Registration registration, ExecutionMode mode) {
    }

    private record Registration(ScheduledTask task) {
    }

    private record Submission(Runnable task, ExecutionMode mode) {
    }

    private record Snapshot(ScheduledTask task, Set<Object> keys) {
    }

    private record Group(ObjectArrayList<ScheduledTask> tasks, int lane) {
    }

    private static final class Failures {
        private Throwable first;
        private boolean interrupted;

        void add(Throwable failure) {
            if (first == null) {
                first = failure;
            } else if (first != failure) {
                first.addSuppressed(failure);
            }
        }

        void interrupted(InterruptedException failure) {
            interrupted = true;
            add(failure);
        }

        void observeInterrupt() {
            if (Thread.interrupted()) {
                interrupted(new InterruptedException("Scheduler boundary was interrupted"));
            }
        }

        boolean present() {
            return first != null;
        }

        void restoreInterrupt() {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        void throwIfPresent() {
            if (first instanceof RuntimeException failure) {
                throw failure;
            }
            if (first instanceof Error failure) {
                throw failure;
            }
            if (first != null) {
                throw new IllegalStateException("Task scheduler failed", first);
            }
        }
    }

    static final class UnionFind {
        private final int[] parent;
        private final int[] rank;

        UnionFind(int size) {
            parent = new int[size];
            rank = new int[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        int find(int index) {
            while (parent[index] != index) {
                parent[index] = parent[parent[index]];
                index = parent[index];
            }
            return index;
        }

        void union(int first, int second) {
            int firstRoot = find(first), secondRoot = find(second);
            if (firstRoot == secondRoot) {
                return;
            }
            if (rank[firstRoot] < rank[secondRoot]) {
                parent[firstRoot] = secondRoot;
            } else {
                parent[secondRoot] = firstRoot;
                if (rank[firstRoot] == rank[secondRoot]) {
                    rank[firstRoot]++;
                }
            }
        }
    }

    private final class StructureBoundary implements Runnable {
        private final Object ownerIdentity;
        private final Supplier<StructureWork> prepare;
        private final Runnable completed;

        private StructureBoundary(Object ownerIdentity, Supplier<StructureWork> prepare, Runnable completed) {
            this.ownerIdentity = ownerIdentity;
            this.prepare = prepare;
            this.completed = completed;
        }

        @Override
        public void run() {
            var entries = new ObjectArrayList<StructureBoundary>();
            entries.add(this);
            synchronized (submissionLock) {
                while (entries.size() < workerThreads && !boundaryActions.isEmpty() && boundaryActions.first() instanceof StructureBoundary next) {
                    if (containsOwner(entries, next.ownerIdentity)) break;
                    boundaryActions.dequeue();
                    entries.add(next);
                }
            }
            runStructureBatch(entries);
        }
    }
}
