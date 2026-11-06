package com.circulation.prototype_eng.api.scheduler;

/** Planned execution requires a formal coordinator and must never fall back to ordinary workers. */
public enum ExecutionMode {
    MAIN_THREAD,
    CONCURRENT,
    PLANNED_PARALLEL
}
