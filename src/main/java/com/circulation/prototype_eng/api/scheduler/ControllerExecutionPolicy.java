package com.circulation.prototype_eng.api.scheduler;

/**
 * Explicit controller threading choice. Admission and world ownership still return to the scheduler owner.
 * Addon authors choose which of their scan and work callbacks can safely run on a worker.
 */
public enum ControllerExecutionPolicy {
    FULL_ASYNC(true, true),
    ASYNC_SCAN(true, false),
    ASYNC_WORK(false, true),
    SYNCHRONOUS(false, false);

    /**
     * Shared declaration order; callers must not modify this array.
     */
    public static final ControllerExecutionPolicy[] VALUES = values();

    private final boolean scanAsync;
    private final boolean workAsync;

    ControllerExecutionPolicy(boolean scanAsync, boolean workAsync) {
        this.scanAsync = scanAsync;
        this.workAsync = workAsync;
    }

    public boolean scanAsync() {
        return scanAsync;
    }

    public boolean workAsync() {
        return workAsync;
    }
}
