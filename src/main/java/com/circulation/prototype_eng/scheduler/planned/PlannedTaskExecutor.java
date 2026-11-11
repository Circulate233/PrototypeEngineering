package com.circulation.prototype_eng.scheduler.planned;

import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * PM's ordered planned execution barriers, separate from coordinator policy. Executors are borrowed,
 * never cancelled or closed here. A stopped result is not an exit proof: retain its Batch until
 * quiescent before retiring owners or releasing uncertain source reservations.
 */
public final class PlannedTaskExecutor {
    private final ExecutorService workers;
    private final ExecutorService[] sourceLanes;
    private final LongSupplier nanoTime;
    private final DispatchMode mode;
    private final PlannedTaskExecutor original;
    @Nullable
    private final PlannedTaskExecutor synchronousView;
    @Nullable
    private final PlannedTaskExecutor synchronousSourcesView;

    public PlannedTaskExecutor(ExecutorService workers, ExecutorService[] sourceLanes) {
        this(workers, sourceLanes, System::nanoTime);
    }

    PlannedTaskExecutor(ExecutorService workers, ExecutorService[] sourceLanes, LongSupplier nanoTime) {
        this.workers = Objects.requireNonNull(workers);
        this.sourceLanes = sourceLanes.clone();
        if (sourceLanes.length == 0) {
            throw new IllegalArgumentException("Planned source execution needs at least one serial lane");
        }
        for (var lane : this.sourceLanes) {
            Objects.requireNonNull(lane);
        }
        this.nanoTime = Objects.requireNonNull(nanoTime);
        mode = DispatchMode.ASYNC;
        original = this;
        synchronousView = new PlannedTaskExecutor(this, DispatchMode.SYNCHRONOUS);
        synchronousSourcesView = new PlannedTaskExecutor(this, DispatchMode.SYNCHRONOUS_SOURCES);
    }

    private PlannedTaskExecutor(PlannedTaskExecutor original, DispatchMode mode) {
        workers = original.workers;
        sourceLanes = original.sourceLanes;
        nanoTime = original.nanoTime;
        this.mode = mode;
        this.original = original;
        synchronousView = null;
        synchronousSourcesView = null;
    }

    /**
     * Cached calling-thread view of this runner. It shares the same batch/deadline/cancellation
     * protocol, borrows the same executors, and submits no job to them. Cancellation cannot preempt
     * a synchronous callback; its exact batch remains nonquiescent until that callback returns.
     */
    public PlannedTaskExecutor synchronous() {
        return Objects.requireNonNull(original.synchronousView);
    }

    /**
     * Keeps independent pure preparation jobs on the original worker pool while running source
     * actions directly on the calling coordinator thread. A coordinator already owns its serial
     * source lane, so nested source dispatch must not wait for that same occupied lane.
     */
    public PlannedTaskExecutor synchronousSources() {
        return Objects.requireNonNull(original.synchronousSourcesView);
    }

    public <T> Batch<T> runPrepare(List<Job<T>> jobs, PlannedDeadline deadline) {
        return runPrepare(jobs, deadline, _ -> {
        });
    }

    /**
     * Delivers the exact prepare batch before accepting or executing any action, as for source lanes.
     * Retain it even after a direct dispatch fault so actual exit can be joined before owner cleanup.
     * The callback must not await its undispatched batch.
     */
    public <T> Batch<T> runPrepare(List<Job<T>> jobs, PlannedDeadline deadline,
                                   Consumer<Batch<T>> beforeDispatch) {
        var dispatches = new ObjectArrayList<Dispatch<T>>(jobs.size());
        for (var job : jobs) {
            dispatches.add(new Dispatch<>(workers, job));
        }
        return run(dispatches, deadline, Objects.requireNonNull(beforeDispatch), mode == DispatchMode.SYNCHRONOUS);
    }

    /**
     * Commit, query and compensation for the same source must all use the same affinity.
     */
    public <T> Batch<T> runOnSourceLanes(List<SourceJob<T>> jobs, PlannedDeadline deadline) {
        return runOnSourceLanes(jobs, deadline, _ -> {
        });
    }

    /**
     * Publishes the exact batch to its coordinator synchronously, before any action can be accepted
     * or executed. Retain the handle even if this call throws; its results and actual exit proof still
     * describe every submitted or stopped action. The callback must not await its undispatched batch.
     */
    public <T> Batch<T> runOnSourceLanes(List<SourceJob<T>> jobs, PlannedDeadline deadline,
                                         Consumer<Batch<T>> beforeDispatch) {
        var dispatches = new ObjectArrayList<Dispatch<T>>(jobs.size());
        for (var job : jobs) {
            dispatches.add(new Dispatch<>(sourceLanes[laneIndex(job.affinity())], job.job()));
        }
        return run(dispatches, deadline, Objects.requireNonNull(beforeDispatch), mode != DispatchMode.ASYNC);
    }

    private <T> Batch<T> run(ObjectList<Dispatch<T>> dispatches, PlannedDeadline deadline,
                             Consumer<Batch<T>> beforeDispatch, boolean synchronous) {
        var batch = new Batch<>(dispatches, Objects.requireNonNull(deadline), nanoTime, synchronous);
        var registrations = new ObjectArrayList<PlannedCancellationToken.Registration>();
        var tokens = new ReferenceOpenHashSet<PlannedCancellationToken>();
        boolean interrupted = Thread.interrupted();
        Throwable primary = null;
        try {
            beforeDispatch.accept(batch);
            if (interrupted) {
                batch.stop(Reason.INTERRUPTED);
            }
            for (var dispatch : dispatches) {
                var token = dispatch.job.cancellation();
                if (token != null && tokens.add(token)) {
                    registrations.add(token.onCancel(batch::stop));
                }
            }
            for (var entry : batch.entries) {
                if (!batch.accept(entry)) {
                    if (batch.stopReason() != null) break;
                    continue;
                }
                try {
                    if (synchronous) {
                        batch.execute(entry);
                        if (Thread.interrupted()) {
                            interrupted = true;
                            batch.stop(Reason.INTERRUPTED);
                        }
                    } else entry.executor.execute(() -> batch.execute(entry));
                } catch (RuntimeException | Error failure) {
                    batch.rejected(entry, failure);
                }
            }
            try {
                batch.awaitTerminal();
            } catch (InterruptedException failure) {
                interrupted = true;
                batch.stop(Reason.INTERRUPTED);
            }
            batch.freezeResults();
        } catch (RuntimeException | Error failure) {
            primary = failure;
            batch.abort(failure);
        } finally {
            Throwable cleanup = null;
            for (var registration : registrations) {
                try {
                    registration.close();
                } catch (RuntimeException | Error failure) {
                    if (cleanup == null) {
                        cleanup = failure;
                    } else {
                        Batch.suppress(cleanup, failure);
                    }
                }
            }
            if (interrupted) {
                try {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | Error failure) {
                    if (cleanup == null) {
                        cleanup = failure;
                    } else {
                        Batch.suppress(cleanup, failure);
                    }
                }
            }
            if (cleanup != null) {
                if (primary != null) {
                    Batch.suppress(primary, cleanup);
                } else {
                    batch.abort(cleanup);
                    primary = cleanup;
                }
            }
        }
        if (primary instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (primary instanceof Error error) {
            throw error;
        }
        return batch;
    }

    /**
     * Original PM UTF-16 FNV-1a mapping; stable across batches and backend instances.
     */
    private int laneIndex(String affinity) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < affinity.length(); index++) {
            hash ^= affinity.charAt(index);
            hash *= 0x100000001b3L;
        }
        return Math.floorMod(hash, sourceLanes.length);
    }

    private enum Stage {NEW, QUEUED, RUNNING, DONE}

    private enum DispatchMode {ASYNC, SYNCHRONOUS, SYNCHRONOUS_SOURCES}

    // The type parameter keeps every outcome tied to its batch's success payload type.
    @SuppressWarnings("unused")
    public sealed interface Result<T> permits Success, Failure, Stopped {
    }

    /** A null per-job deadline preserves the original batch-wide planned execution policy. */
    public record Job<T>(@Nullable PlannedCancellationToken cancellation, Supplier<? extends T> action,
                         @Nullable PlannedDeadline deadline) {
        public Job(@Nullable PlannedCancellationToken cancellation, Supplier<? extends T> action) {
            this(cancellation, action, null);
        }

        public Job {
            Objects.requireNonNull(action);
        }
    }

    public record SourceJob<T>(String affinity, Job<T> job) {
        public SourceJob {
            Objects.requireNonNull(affinity);
            Objects.requireNonNull(job);
            if (affinity.isBlank()) {
                throw new IllegalArgumentException("Planned source affinity must not be blank");
            }
        }
    }

    public record Success<T>(T value) implements Result<T> {
    }

    public record Failure<T>(Throwable failure) implements Result<T> {
        public Failure {
            Objects.requireNonNull(failure);
        }
    }

    /**
     * started=true is uncertain: interruption does not prove whether source effects happened.
     */
    public record Stopped<T>(Reason reason, boolean started) implements Result<T> {
        public Stopped {
            Objects.requireNonNull(reason);
        }
    }

    public static final class Batch<T> {
        private final Object lock = new Object();
        private final ObjectList<Entry<T>> entries;
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final PlannedDeadline deadline;
        private final LongSupplier nanoTime;
        private final boolean synchronous;
        private final boolean individualDeadlines;
        private int unfinished;
        private int runningOrEligible;
        @Nullable
        private Reason stopReason;
        @Nullable
        private ObjectList<Result<T>> results;

        private Batch(ObjectList<Dispatch<T>> dispatches, PlannedDeadline deadline, LongSupplier nanoTime, boolean synchronous) {
            this.deadline = deadline;
            this.nanoTime = nanoTime;
            this.synchronous = synchronous;
            this.entries = new ObjectArrayList<>(dispatches.size());
            boolean individual = false;
            for (var dispatch : dispatches) {
                entries.add(new Entry<>(dispatch.executor, dispatch.job.action(), dispatch.job.deadline()));
                individual |= dispatch.job.deadline() != null;
            }
            individualDeadlines = individual;
            unfinished = entries.size();
            runningOrEligible = entries.size();
            if (entries.isEmpty()) {
                terminal.countDown();
                exited.countDown();
            }
        }

        private static void suppress(Throwable primary, Throwable secondary) {
            if (primary != secondary) {
                primary.addSuppressed(secondary);
            }
        }

        /**
         * Immutable ordered terminal snapshots. Late task returns never overwrite these outcomes.
         */
        public ObjectList<Result<T>> results() {
            return Objects.requireNonNull(results);
        }

        @Nullable
        public Reason stopReason() {
            return stopReason;
        }

        /**
         * Proves no accepted user action can still execute; queued stopped wrappers are inert.
         */
        public boolean quiescent() {
            return exited.getCount() == 0;
        }

        /**
         * Never returns early on interruption; restores the flag and reports it only after actual exit.
         */
        public void awaitQuiescence() {
            InterruptedException interrupted = null;
            boolean done = false;
            while (!done) {
                try {
                    exited.await();
                    done = true;
                } catch (InterruptedException failure) {
                    if (interrupted == null) {
                        interrupted = failure;
                    } else {
                        interrupted.addSuppressed(failure);
                    }
                }
            }
            if (interrupted != null) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting planned action exit", interrupted);
            }
        }

        private boolean accept(Entry<T> entry) {
            synchronized (lock) {
                if (stopReason == null && deadline.expired(nanoTime.getAsLong())) {
                    stopLocked(Reason.WORKER_TIMEOUT);
                }
                if (stopReason != null) {
                    return false;
                }
                if (entry.deadline != null && entry.deadline.expired(nanoTime.getAsLong())) {
                    stopEntryLocked(entry, Reason.WORKER_TIMEOUT);
                    return false;
                }
                entry.stage = Stage.QUEUED;
                return true;
            }
        }

        private void execute(Entry<T> entry) {
            synchronized (lock) {
                if (entry.stage != Stage.QUEUED) {
                    return;
                }
                try {
                    if (deadline.expired(nanoTime.getAsLong())) {
                        stopLocked(Reason.WORKER_TIMEOUT);
                        return;
                    }
                    if (entry.deadline != null && entry.deadline.expired(nanoTime.getAsLong())) {
                        stopEntryLocked(entry, Reason.WORKER_TIMEOUT);
                        return;
                    }
                } catch (Throwable failure) {
                    try {
                        if (entry.result == null) {
                            publish(entry, new Failure<>(failure));
                        }
                    } finally {
                        finish(entry);
                    }
                    return;
                }
                entry.stage = Stage.RUNNING;
                entry.thread = Thread.currentThread();
            }
            Result<T> result;
            try {
                result = new Success<>(entry.action.get());
            } catch (Throwable failure) {
                result = new Failure<>(failure);
            }
            synchronized (lock) {
                try {
                    if (entry.result == null && deadline.expired(nanoTime.getAsLong())) {
                        stopLocked(Reason.WORKER_TIMEOUT);
                    }
                    if (entry.result == null && entry.deadline != null && entry.deadline.expired(nanoTime.getAsLong())) {
                        stopEntryLocked(entry, Reason.WORKER_TIMEOUT);
                    }
                    if (entry.result == null) {
                        publish(entry, result);
                    }
                } catch (Throwable failure) {
                    if (entry.result == null) {
                        if (result instanceof Failure<T>(var businessFailure)) {
                            suppress(businessFailure, failure);
                            publish(entry, result);
                        } else {
                            publish(entry, new Failure<>(failure));
                        }
                    }
                } finally {
                    entry.thread = null;
                    if (entry.stage != Stage.DONE) {
                        finish(entry);
                    }
                }
            }
        }

        private void rejected(Entry<T> entry, Throwable failure) {
            synchronized (lock) {
                // Executor rejection means the action was not accepted; cancellation may already have won.
                if (entry.result == null) {
                    publish(entry, new Failure<>(failure));
                }
                if (entry.stage != Stage.RUNNING && entry.stage != Stage.DONE) {
                    finish(entry);
                }
            }
        }

        private void awaitTerminal() throws InterruptedException {
            if (individualDeadlines) {
                awaitIndividualDeadlines();
                return;
            }
            long remaining = deadline.remainingNanos(nanoTime.getAsLong());
            if (terminal.getCount() != 0 && (remaining == 0 || !terminal.await(remaining, TimeUnit.NANOSECONDS))) {
                stop(Reason.WORKER_TIMEOUT);
            }
        }

        /** The waiting owner services each deadline; no new timer pool or backend is needed. */
        private void awaitIndividualDeadlines() throws InterruptedException {
            while (terminal.getCount() != 0) {
                long remaining;
                synchronized (lock) {
                    long now = nanoTime.getAsLong();
                    remaining = deadline.remainingNanos(now);
                    if (remaining == 0) {
                        stopLocked(Reason.WORKER_TIMEOUT);
                        return;
                    }
                    for (var entry : entries) {
                        if (entry.result != null || entry.deadline == null) continue;
                        long local = entry.deadline.remainingNanos(now);
                        if (local == 0) stopEntryLocked(entry, Reason.WORKER_TIMEOUT);
                        else remaining = Math.min(remaining, local);
                    }
                    if (unfinished == 0) return;
                }
                if (terminal.await(remaining, TimeUnit.NANOSECONDS)) return;
            }
        }

        private void stop(Reason reason) {
            synchronized (lock) {
                stopLocked(reason);
            }
        }

        private void stopLocked(Reason reason) {
            if (stopReason != null || unfinished == 0) {
                return;
            }
            stopReason = reason;
            Throwable failure = null;
            for (var entry : entries) {
                try {
                    stopEntryLocked(entry, reason);
                } catch (RuntimeException | Error interrupt) {
                    if (failure == null) {
                        failure = interrupt;
                    } else {
                        suppress(failure, interrupt);
                    }
                }
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        private void stopEntryLocked(Entry<T> entry, Reason reason) {
            if (entry.result != null) return;
            boolean running = entry.stage == Stage.RUNNING;
            publish(entry, new Stopped<>(reason, running));
            if (running && !synchronous) {
                // Pin the exact running entry under the lock until interrupt has been delivered.
                Objects.requireNonNull(entry.thread).interrupt();
            } else if (!running) finish(entry);
        }

        private void abort(Throwable primary) {
            try {
                stop(Reason.WORKER_FAILURE);
            } catch (RuntimeException | Error cleanup) {
                suppress(primary, cleanup);
            }
            try {
                freezeResults();
            } catch (RuntimeException | Error cleanup) {
                suppress(primary, cleanup);
            }
        }

        private void publish(Entry<T> entry, Result<T> result) {
            entry.result = result;
            if (--unfinished == 0) {
                terminal.countDown();
            }
        }

        private void finish(Entry<T> entry) {
            if (entry.stage == Stage.DONE) {
                return;
            }
            entry.stage = Stage.DONE;
            if (--runningOrEligible == 0) {
                exited.countDown();
            }
        }

        private void freezeResults() {
            synchronized (lock) {
                var copy = new ObjectArrayList<Result<T>>(entries.size());
                for (var entry : entries) {
                    copy.add(Objects.requireNonNull(entry.result));
                }
                results = ObjectLists.unmodifiable(copy);
            }
        }
    }

    private record Dispatch<T>(ExecutorService executor, Job<T> job) {
    }

    private static final class Entry<T> {
        private final ExecutorService executor;
        private final Supplier<? extends T> action;
        @Nullable
        private final PlannedDeadline deadline;
        private Stage stage = Stage.NEW;
        @Nullable
        private Thread thread;
        @Nullable
        private Result<T> result;

        private Entry(ExecutorService executor, Supplier<? extends T> action, @Nullable PlannedDeadline deadline) {
            this.executor = executor;
            this.action = action;
            this.deadline = deadline;
        }
    }
}
