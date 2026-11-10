package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.client.render.gecko.GeckoModelBuildTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

/** Dedicated CPU executor. Closing never joins workers that may be waiting for a client mapping reply. */
public class ModelBuildExecutor implements AutoCloseable {
    private final ExecutorService executor;

    public ModelBuildExecutor(int workers) {
        this(workers, false);
    }

    public ModelBuildExecutor(int workers, boolean fixedThreads) {
        if (workers < 1) throw new IllegalArgumentException("No model workers");
        ThreadFactory factory = Thread.ofPlatform().daemon().name("pe-model-build-", 0).factory();
        executor = fixedThreads ? Executors.newFixedThreadPool(workers, factory) :
            new ForkJoinPool(workers, pool -> {
                var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                thread.setName("pe-model-build-" + thread.getPoolIndex());
                return thread;
            }, null, true);
    }

    public ModelBuildExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    public void submit(GeckoModelBuildTask task) {
        try {
            executor.execute(task);
        } catch (RuntimeException | Error failure) {
            task.retire();
            throw failure;
        }
    }

    @SuppressWarnings("unused")
    // Public diagnostic for addons choosing their own request cadence; it reads the actual dedicated executor queue.
    public long queuedBuilds() {
        if (executor instanceof ForkJoinPool pool) return pool.getQueuedTaskCount() + pool.getQueuedSubmissionCount();
        if (executor instanceof ThreadPoolExecutor pool) return pool.getQueue().size();
        return 0;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
