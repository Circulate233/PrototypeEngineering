package com.circulation.prototype_eng.api.scheduler;

/**
 * Planned execution requires PlannedMachineTask and a real lifecycle-bound machine. It runs after
 * ordinary concurrent work joins, through the owner coordinator, and never falls back to workers.
 * Runnable submissions cannot select PLANNED_PARALLEL.
 */
public enum ExecutionMode {
    MAIN_THREAD,
    CONCURRENT,
    PLANNED_PARALLEL;

    /**
     * Shared declaration order; callers must not modify this array.
     */
    public static final ExecutionMode[] VALUES = values();
}
