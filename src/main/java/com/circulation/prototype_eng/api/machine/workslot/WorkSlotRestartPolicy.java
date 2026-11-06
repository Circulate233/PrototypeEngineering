package com.circulation.prototype_eng.api.machine.workslot;

/** Restart intent retained from PM; the host supplies any rescan scheduling policy. */
public enum WorkSlotRestartPolicy {
    /** Retain the current process and retry its blocked stage. */
    LOCKED_RETRY,
    /** Request a later rescan; this metadata does not define a cooldown or discard a process. */
    RESCAN
}
