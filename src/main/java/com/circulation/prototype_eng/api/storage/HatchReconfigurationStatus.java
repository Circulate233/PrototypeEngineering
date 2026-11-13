package com.circulation.prototype_eng.api.storage;

/**
 * A normal capacity or ownership refusal retains the entire previous effective profile.
 */
public enum HatchReconfigurationStatus {
    APPLIED, UNCHANGED, PENDING_BUSY, BLOCKED_CAPACITY, STALE_TARGET, CLOSED
}
