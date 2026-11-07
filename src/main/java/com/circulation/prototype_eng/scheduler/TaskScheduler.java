package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Owned ordinary worker pool and serialized affinity lanes. Every tick joins all accepted tasks
 * before returning or throwing. No Future escapes and no accepted Future is cancelled. Failures
 * permanently revoke execution; unregister and close remain available for cleanup. Main callbacks
 * precede concurrent callbacks. Owner-thread main callbacks may synchronously register/unregister:
 * new main tasks wait until the next tick, while concurrent tasks are captured after the main phase.
 * Submissions made during a tick belong to a later tick.
 */
public final class TaskScheduler implements AutoCloseable {
    private final Thread owner;
    private final int workerThreads;
    private final int laneCount;
    @Nullable private final PlannedFactory plannedFactory;
    private final long plannedTimeoutNanos;
    private final Reference2ObjectLinkedOpenHashMap<ScheduledTask, Registration> registered = new Reference2ObjectLinkedOpenHashMap<>();
    private final Object submissionLock = new Object();
    private ObjectArrayList<Submission> pending = new ObjectArrayList<>();
    private final ObjectArrayFIFOQueue<Runnable> boundaryActions = new ObjectArrayFIFOQueue<>();
    private boolean accepting = true;
    private boolean acceptingBoundary = true;
    // A failed close may leave owned executors alive even though ordinary admission is revoked.
    private boolean boundaryReady = true;
    private boolean closingRequested;
    private boolean faulted;
    private State state = State.IDLE;
    private ExecutorService workers;
    private ExecutorService[] lanes;
    @Nullable private PlannedTaskExecutor plannedTasks;
    @Nullable private PlannedExecution pendingExecution;
    private boolean pendingExitProven = true;
    @Nullable private Reason pendingStopReason;
    private boolean pendingStopConfirmed;

    public TaskScheduler(Thread owner, int workerThreads, int laneCount) {
        this(owner, workerThreads, laneCount, null, Duration.ofSeconds(5));
    }

    /** Internal owner capture; formal machine discovery and its authority remain a separate phase. */
    TaskScheduler(Thread owner, int workerThreads, int laneCount, @Nullable PlannedFactory plannedFactory,
                  Duration plannedTimeout) {
        this.owner = Objects.requireNonNull(owner);
        if (workerThreads < 1 || laneCount < 1) { throw new IllegalArgumentException("Worker and lane counts must be positive"); }
        this.workerThreads = workerThreads;
        this.laneCount = laneCount;
        this.plannedFactory = plannedFactory;
        Objects.requireNonNull(plannedTimeout);
        if (plannedTimeout.isNegative() || plannedTimeout.isZero()) {
            throw new IllegalArgumentException("Planned cleanup budget must be positive");
        }
        plannedTimeoutNanos = plannedTimeout.toNanos();
    }

    /** Injected executors transfer ownership to this scheduler and must not cancel accepted work. */
    TaskScheduler(Thread owner, int workerThreads, int laneCount, ExecutorService workers, ExecutorService[] lanes) {
        this(owner, workerThreads, laneCount, workers, lanes, null, Duration.ofSeconds(5));
    }

    TaskScheduler(Thread owner, int workerThreads, int laneCount, ExecutorService workers, ExecutorService[] lanes,
                  @Nullable PlannedFactory plannedFactory, Duration plannedTimeout) {
        this(owner, workerThreads, laneCount, plannedFactory, plannedTimeout);
        Objects.requireNonNull(workers);
        ExecutorService[] copied = Objects.requireNonNull(lanes).clone();
        if (copied.length != laneCount) { throw new IllegalArgumentException("Injected lane count differs from configuration"); }
        for (ExecutorService lane : copied) { Objects.requireNonNull(lane); }
        this.workers = workers;
        this.lanes = copied;
    }

    /** Registration is by task identity; a duplicate registration keeps its original position. */
    public void register(ScheduledTask task) {
        State previous = enterMutation(true);
        try {
            Objects.requireNonNull(task);
            requireOrdinary(task.executionMode());
            if (!registered.containsKey(task)) { registered.put(task, new Registration(task)); }
        } finally { state = previous; }
    }

    public void unregister(ScheduledTask task) {
        State previous = enterMutation(false);
        try { registered.remove(Objects.requireNonNull(task)); }
        finally { state = previous; }
    }

    public int registeredCount() {
        requireOwner();
        return registered.size();
    }

    /** Thread-safe enqueue; PLANNED_PARALLEL is explicitly unsupported by this ordinary scheduler. */
    public void submit(Runnable task, ExecutionMode mode) {
        Objects.requireNonNull(task);
        requireOrdinary(mode);
        synchronized (submissionLock) {
            if (!accepting || faulted) { throw new IllegalStateException("Scheduler is closed, closing or faulted"); }
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
            if (!acceptingBoundary) { throw new IllegalStateException("Scheduler owner boundary is closed"); }
            boundaryActions.enqueue(action);
        }
        if (ownerRequest && state == State.IDLE && boundaryReady) {
            var failures = new Failures();
            try {
                failures.observeInterrupt();
                if (failures.present()) { fault(); }
                drainBoundary(failures, false);
            } finally { failures.restoreInterrupt(); }
            failures.throwIfPresent();
        }
    }

    public void tick() {
        tick(() -> {});
    }

    /**
     * Prepares current authority on the owner before each executed main action and before concurrent
     * metadata capture. Preparation has exclusive ownership and cannot recursively tick, close,
     * mutate scheduler registrations or request an owner boundary. Thread-safe submissions still
     * belong to a later tick. A preparation failure faults execution and follows the normal join
     * and boundary-cleanup path; neither preparation nor the rejected action is retried.
     */
    public void tick(Runnable prepare) {
        Objects.requireNonNull(prepare);
        enterTick();
        var failures = new Failures();
        var futures = new ObjectArrayList<FutureTask<Void>>();
        boolean capturePlanned = false;
        boolean resumingClose = closingRequested && pendingExecution != null;
        try {
            failures.observeInterrupt();
            if (failures.present()) { fault(); }
            if (pendingExecution != null) { drivePending(failures, false); }
            failures.observeInterrupt();
            if (failures.present()) { fault(); }
            drainBoundary(failures, false);
            failures.throwIfPresent();
            if (pendingExecution == null && !resumingClose) {
                requireHealthy();
                executeOrdinary(prepare, futures, failures);
                capturePlanned = true;
            }
        } catch (Throwable failure) {
            failures.add(failure);
            fault();
        } finally {
            state = State.JOINING;
            for (FutureTask<Void> future : futures) {
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
            if (failures.present()) { fault(); }
            if (capturePlanned && !failures.present() && plannedFactory != null) {
                try { capturePlanned(); }
                catch (Throwable failure) { failures.add(failure); fault(); }
                if (pendingExecution != null) { drivePending(failures, true); }
            }
            failures.observeInterrupt();
            if (failures.present()) { fault(); }
            drainBoundary(failures, false);
            state = State.IDLE;
            failures.restoreInterrupt();
        }
        failures.throwIfPresent();
    }

    private void executeOrdinary(Runnable prepare, ObjectArrayList<FutureTask<Void>> futures, Failures failures) {
        ObjectArrayList<Submission> batch;
        synchronized (submissionLock) {
            batch = pending;
            pending = new ObjectArrayList<>();
        }
        var main = new ObjectArrayList<Registration>();
        for (Registration registration : registered.values()) {
            if (requireOrdinary(registration.task.executionMode()) == ExecutionMode.MAIN_THREAD) { main.add(registration); }
        }
        var executed = new ReferenceOpenHashSet<ScheduledTask>();
        state = State.MAIN;
        for (Submission submission : batch) {
            if (submission.mode == ExecutionMode.MAIN_THREAD) { runMain(submission.task, prepare, failures); }
        }
        for (Registration registration : main) {
            ScheduledTask task = registration.task;
            if (registered.get(task) != registration) { continue; }
            boolean active;
            ExecutionMode mode;
            state = State.CAPTURING_MAIN;
            try {
                mode = requireOrdinary(task.executionMode());
                active = task.active();
            } finally { state = State.MAIN; }
            if (mode == ExecutionMode.MAIN_THREAD && active) {
                executed.add(task);
                runMain(task::onSchedule, prepare, failures);
            }
        }
        state = State.CAPTURING_CONCURRENT;
        prepare.run();
        var concurrent = new ObjectArrayList<Snapshot>();
        for (Registration registration : registered.values()) {
            ScheduledTask task = registration.task;
            ExecutionMode mode = requireOrdinary(task.executionMode());
            if (mode != ExecutionMode.CONCURRENT || executed.contains(task) || !task.active()) { continue; }
            var keys = new ObjectOpenHashSet<>();
            for (Object key : Objects.requireNonNull(task.affinityKeys())) { keys.add(Objects.requireNonNull(key)); }
            concurrent.add(new Snapshot(task, keys));
        }
        ObjectArrayList<Group> groups = group(concurrent);
        int customConcurrent = 0;
        for (Submission submission : batch) {
            if (submission.mode == ExecutionMode.CONCURRENT) { customConcurrent++; }
        }
        futures.ensureCapacity(Math.addExact(customConcurrent, groups.size()));
        state = State.DISPATCHING;
        if (customConcurrent != 0 || !groups.isEmpty()) {
            initializeExecutors();
            for (Submission submission : batch) {
                if (submission.mode == ExecutionMode.CONCURRENT) { dispatch(workers, submission.task, futures); }
            }
            for (Group group : groups) {
                ExecutorService executor = group.lane < 0 ? workers : lanes[group.lane];
                dispatch(executor, () -> runGroup(group), futures);
            }
        }
    }

    private void runMain(Runnable action, Runnable prepare, Failures failures) {
        try {
            state = State.CAPTURING_MAIN;
            prepare.run();
            state = State.MAIN;
            action.run();
        }
        catch (Throwable failure) { failures.add(failure); fault(); }
        finally { drainBoundary(failures, false); }
        failures.throwIfPresent();
    }

    private void capturePlanned() {
        state = State.CAPTURING_PLANNED;
        initializeExecutors();
        if (plannedTasks == null) { plannedTasks = new PlannedTaskExecutor(workers, lanes); }
        var captured = Objects.requireNonNull(plannedFactory).capture(plannedTasks, cleanupDeadline());
        if (captured != null) {
            // Publication precedes the first callback or asynchronous action in this execution.
            pendingExecution = captured;
            boundaryReady = false;
            pendingExitProven = true;
            pendingStopReason = null;
            pendingStopConfirmed = false;
        }
    }

    /** One recovery pass; an initial forward attempt additionally settles its cancelled remainder. */
    private void drivePending(Failures failures, boolean initial) {
        State previous = state;
        state = State.PLANNED;
        try {
            if (!initial && !pendingStopConfirmed) {
                cancelPending(failures, Reason.EPOCH_CHANGED);
                if (!pendingStopConfirmed) { awaitPending(failures); return; }
            }
            if (!pendingExitProven) {
                awaitPending(failures);
                if (!pendingExitProven) { return; }
            }
            advancePending(failures);
            boolean finished = pendingFinished(failures);
            if (!finished) { cancelPending(failures, failures.present() ? Reason.WORKER_FAILURE : Reason.EPOCH_CHANGED); }
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
        } finally { state = previous; }
    }

    private void advancePending(Failures failures) {
        pendingExitProven = false;
        try { Objects.requireNonNull(pendingExecution).advance(cleanupDeadline()); }
        catch (Throwable failure) { failures.add(failure); fault(); }
        failures.observeInterrupt();
        if (failures.present()) { fault(); }
    }

    private boolean pendingFinished(Failures failures) {
        State previous = state;
        state = State.CAPTURING_PLANNED;
        try { return Objects.requireNonNull(pendingExecution).finished(); }
        catch (Throwable failure) { failures.add(failure); fault(); return false; }
        finally { state = previous; }
    }

    private void cancelPending(Failures failures, Reason reason) {
        if (pendingStopConfirmed) { return; }
        if (pendingStopReason == null) { pendingStopReason = reason; }
        State previous = state;
        state = State.PLANNED;
        try {
            Objects.requireNonNull(pendingExecution).cancel(pendingStopReason);
            pendingStopConfirmed = true;
        }
        catch (Throwable failure) { failures.add(failure); fault(); }
        finally { state = previous; }
    }

    private void awaitPending(Failures failures) {
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
                try { Objects.requireNonNull(pendingExecution).awaitExit(); pendingExitProven = true; }
                catch (Throwable retryFailure) { failures.add(retryFailure); failures.observeInterrupt(); }
            }
        }
    }

    private PlannedDeadline cleanupDeadline() { return new PlannedDeadline(System.nanoTime(), plannedTimeoutNanos); }

    private void drainBoundary(Failures failures, boolean closeAdmission) {
        if (!boundaryReady) { return; }
        State previous = state;
        state = State.BOUNDARY;
        try {
            while (true) {
                Runnable action;
                synchronized (submissionLock) {
                    if (boundaryActions.isEmpty()) {
                        if (closeAdmission) { acceptingBoundary = false; }
                        return;
                    }
                    action = boundaryActions.dequeue();
                }
                try { action.run(); }
                catch (Throwable failure) { failures.add(failure); fault(); }
                failures.observeInterrupt();
                if (failures.present()) { fault(); }
            }
        } finally { state = previous; }
    }

    private static void dispatch(ExecutorService executor, Runnable action, ObjectArrayList<FutureTask<Void>> futures) {
        var future = new FutureTask<Void>(action, null);
        executor.execute(future);
        futures.add(future);
    }

    private ObjectArrayList<Group> group(ObjectArrayList<Snapshot> tasks) {
        var union = new UnionFind(tasks.size());
        var indexes = new Object2IntOpenHashMap<>();
        indexes.defaultReturnValue(-1);
        for (int index = 0; index < tasks.size(); index++) {
            for (Object key : tasks.get(index).keys) {
                int previous = indexes.putIfAbsent(key, index);
                if (previous >= 0) { union.union(index, previous); }
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
                for (Object key : keys) { hashes[index++] = key.hashCode(); }
                Arrays.sort(hashes);
                int hash = 1;
                for (int value : hashes) { hash = 31 * hash + value; }
                lane = (hash & Integer.MAX_VALUE) % laneCount;
            }
            groups.add(new Group(members, lane));
        }
        return groups;
    }

    private static void runGroup(Group group) {
        for (ScheduledTask task : group.tasks) { task.onSchedule(); }
    }

    @SuppressWarnings("resource") // Executors are owned by close(), including cleanup of partially created arrays.
    private void initializeExecutors() {
        if (workers != null && lanes != null) { return; }
        try {
            workers = Executors.newFixedThreadPool(workerThreads,
                Thread.ofPlatform().daemon().name("PrototypeEngineering-Scheduler-Worker-", 1).factory());
            lanes = new ExecutorService[laneCount];
            for (int lane = 0; lane < laneCount; lane++) {
                lanes[lane] = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("PrototypeEngineering-Scheduler-Lane-" + lane + "-", 1).factory());
            }
        } catch (Throwable failure) {
            synchronized (submissionLock) { accepting = false; }
            var failures = new Failures();
            failures.add(failure);
            boundaryReady = stopExecutors(failures);
            failures.restoreInterrupt();
            failures.throwIfPresent();
        }
    }

    /** Requires completed cleanup; use tryClose when a retained execution may still be pending. */
    @Override
    public void close() {
        if (!tryClose()) { throw new IllegalStateException("Scheduler retains pending planned cleanup; retry tryClose after confirmation"); }
    }

    /**
     * Closes admission and attempts retained cleanup before shutting down its original lanes. False
     * retains the execution, executors and owner actions for a later attempt, without a runtime fault.
     * Actual failures still throw after the available cleanup and never discard pending ownership.
     */
    public boolean tryClose() {
        requireOwner();
        if (state == State.CLOSED) { return true; }
        if (state != State.IDLE) { throw new IllegalStateException("Scheduler boundary is already active"); }
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
        } catch (Throwable failure) { failures.add(failure); }
        finally {
            failures.observeInterrupt();
            if (failures.present()) { fault(); }
            drainBoundary(failures, true);
            state = boundaryReady ? State.CLOSED : State.IDLE;
            if (boundaryReady) { registered.clear(); }
            failures.restoreInterrupt();
        }
        failures.throwIfPresent();
        return state == State.CLOSED;
    }

    private boolean stopExecutors(Failures failures) {
        var executors = new ObjectArrayList<ExecutorService>();
        if (workers != null) { executors.add(workers); }
        if (lanes != null) {
            for (ExecutorService lane : lanes) { if (lane != null) { executors.add(lane); } }
        }
        for (ExecutorService executor : executors) {
            try { executor.shutdown(); }
            catch (Throwable failure) { failures.add(failure); }
        }
        boolean quiescent = true;
        for (ExecutorService executor : executors) {
            try {
                if (executor.isTerminated()) { continue; }
                if (!executor.isShutdown()) { throw new IllegalStateException("Owned executor did not shut down"); }
                boolean terminated = false;
                while (!terminated) {
                    try { terminated = executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException failure) { failures.interrupted(failure); }
                }
            } catch (Throwable failure) { failures.add(failure); quiescent = false; }
        }
        return quiescent;
    }

    private void fault() {
        synchronized (submissionLock) { faulted = true; pending.clear(); }
    }

    private void enterTick() {
        requireOwnerIdle();
        state = State.CAPTURING_MAIN;
    }

    private void requireHealthy() {
        synchronized (submissionLock) {
            if (!accepting || faulted) { throw new IllegalStateException("Scheduler is closed, closing or faulted"); }
        }
    }

    private State enterMutation(boolean requireHealthy) {
        requireOwner();
        if (state != State.IDLE && state != State.MAIN && state != State.BOUNDARY) { throw new IllegalStateException("Scheduler boundary is already active"); }
        synchronized (submissionLock) {
            if (requireHealthy && (!accepting || faulted)) { throw new IllegalStateException("Scheduler is closed, closing or faulted"); }
        }
        State previous = state;
        state = State.REGISTERING;
        return previous;
    }

    private void requireOwnerIdle() {
        requireOwner();
        if (state != State.IDLE) { throw new IllegalStateException("Scheduler boundary is already active"); }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Scheduler requires its owning thread"); }
    }

    private static ExecutionMode requireOrdinary(ExecutionMode mode) {
        Objects.requireNonNull(mode);
        if (mode == ExecutionMode.PLANNED_PARALLEL) { throw new IllegalArgumentException("PLANNED_PARALLEL requires a formal planned coordinator"); }
        return mode;
    }

    /**
     * Internal machine-cycle construction. Return without callbacks, worker dispatch or partial
     * ownership acquisition; all such work belongs in advance after scheduler publication.
     */
    @FunctionalInterface
    interface PlannedFactory {
        @Nullable PlannedExecution capture(PlannedTaskExecutor tasks, PlannedDeadline forwardDeadline);
    }

    private enum State { IDLE, REGISTERING, CAPTURING_MAIN, MAIN, CAPTURING_CONCURRENT, DISPATCHING, JOINING, CAPTURING_PLANNED, PLANNED, BOUNDARY, CLOSING, CLOSED }
    private record Registration(ScheduledTask task) {}
    private record Submission(Runnable task, ExecutionMode mode) {}
    private record Snapshot(ScheduledTask task, Set<Object> keys) {}
    private record Group(ObjectArrayList<ScheduledTask> tasks, int lane) {}

    private static final class Failures {
        private Throwable first;
        private boolean interrupted;
        void add(Throwable failure) {
            if (first == null) { first = failure; }
            else if (first != failure) { first.addSuppressed(failure); }
        }
        void interrupted(InterruptedException failure) { interrupted = true; add(failure); }
        void observeInterrupt() {
            if (Thread.interrupted()) { interrupted(new InterruptedException("Scheduler boundary was interrupted")); }
        }
        boolean present() { return first != null; }
        void restoreInterrupt() { if (interrupted) { Thread.currentThread().interrupt(); } }
        void throwIfPresent() {
            if (first instanceof RuntimeException failure) { throw failure; }
            if (first instanceof Error failure) { throw failure; }
            if (first != null) { throw new IllegalStateException("Task scheduler failed", first); }
        }
    }

    private static final class UnionFind {
        private final int[] parent;
        private final int[] rank;
        UnionFind(int size) {
            parent = new int[size]; rank = new int[size];
            for (int index = 0; index < size; index++) { parent[index] = index; }
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
            if (firstRoot == secondRoot) { return; }
            if (rank[firstRoot] < rank[secondRoot]) { parent[firstRoot] = secondRoot; }
            else {
                parent[secondRoot] = firstRoot;
                if (rank[firstRoot] == rank[secondRoot]) { rank[firstRoot]++; }
            }
        }
    }
}
