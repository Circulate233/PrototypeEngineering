package com.circulation.prototype_eng.api.machine.workslot;

/**
 * Derived runtime status for UI and diagnostics.
 */
public enum WorkSlotStatus {
    IDLE,
    RUNNING,
    BLOCKED,
    FAILED;

    /** Shared declaration order; callers must not modify this array. */
    public static final WorkSlotStatus[] VALUES = values();
}
