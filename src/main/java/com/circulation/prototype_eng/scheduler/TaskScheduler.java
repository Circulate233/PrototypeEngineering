package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

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
    private final Reference2ObjectLinkedOpenHashMap<ScheduledTask, Registration> registered = new Reference2ObjectLinkedOpenHashMap<>();
    private final Object submissionLock = new Object();
    private ObjectArrayList<Submission> pending = new ObjectArrayList<>();
    private boolean accepting = true;
    private boolean faulted;
    private State state = State.IDLE;
    private ExecutorService workers;
    private ExecutorService[] lanes;

    public TaskScheduler(Thread owner, int workerThreads, int laneCount) {
        this.owner = Objects.requireNonNull(owner);
        if (workerThreads < 1 || laneCount < 1) { throw new IllegalArgumentException("Worker and lane counts must be positive"); }
        this.workerThreads = workerThreads;
        this.laneCount = laneCount;
    }

    /** Injected executors transfer ownership to this scheduler and must not cancel accepted work. */
    TaskScheduler(Thread owner, int workerThreads, int laneCount, ExecutorService workers, ExecutorService[] lanes) {
        this(owner, workerThreads, laneCount);
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

    public void tick() {
        enterTick();
        var failures = new Failures();
        var futures = new ObjectArrayList<FutureTask<Void>>();
        try {
            failures.observeInterrupt();
            failures.throwIfPresent();
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
                if (submission.mode == ExecutionMode.MAIN_THREAD) { submission.task.run(); }
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
                    task.onSchedule();
                }
            }
            state = State.CAPTURING_CONCURRENT;
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
            state = State.IDLE;
            failures.restoreInterrupt();
        }
        failures.throwIfPresent();
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
            stopExecutors(failures);
            failures.restoreInterrupt();
            failures.throwIfPresent();
        }
    }

    /** A failed close keeps resources attached and admission revoked; cleanup may be retried. */
    @Override
    public void close() {
        requireOwner();
        if (state == State.CLOSED) { return; }
        if (state != State.IDLE) { throw new IllegalStateException("Scheduler boundary is already active"); }
        state = State.CLOSING;
        synchronized (submissionLock) {
            accepting = false;
            pending.clear();
        }
        var failures = new Failures();
        try {
            failures.observeInterrupt();
            stopExecutors(failures);
        } catch (Throwable failure) { failures.add(failure); }
        finally {
            state = failures.present() ? State.IDLE : State.CLOSED;
            if (!failures.present()) { registered.clear(); }
            failures.restoreInterrupt();
        }
        failures.throwIfPresent();
    }

    private void stopExecutors(Failures failures) {
        var executors = new ObjectArrayList<ExecutorService>();
        if (workers != null) { executors.add(workers); }
        if (lanes != null) {
            for (ExecutorService lane : lanes) { if (lane != null) { executors.add(lane); } }
        }
        for (ExecutorService executor : executors) {
            try { executor.shutdown(); }
            catch (Throwable failure) { failures.add(failure); }
        }
        for (ExecutorService executor : executors) {
            try {
                if (!executor.isShutdown()) { throw new IllegalStateException("Owned executor did not shut down"); }
                boolean terminated = false;
                while (!terminated) {
                    try { terminated = executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException failure) { failures.interrupted(failure); }
                }
            } catch (Throwable failure) { failures.add(failure); }
        }
    }

    private void fault() {
        synchronized (submissionLock) { faulted = true; pending.clear(); }
    }

    private void enterTick() {
        requireOwnerIdle();
        synchronized (submissionLock) {
            if (!accepting || faulted) { throw new IllegalStateException("Scheduler is closed, closing or faulted"); }
        }
        state = State.CAPTURING_MAIN;
    }

    private State enterMutation(boolean requireHealthy) {
        requireOwner();
        if (state != State.IDLE && state != State.MAIN) { throw new IllegalStateException("Scheduler boundary is already active"); }
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

    private enum State { IDLE, REGISTERING, CAPTURING_MAIN, MAIN, CAPTURING_CONCURRENT, DISPATCHING, JOINING, CLOSING, CLOSED }
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
