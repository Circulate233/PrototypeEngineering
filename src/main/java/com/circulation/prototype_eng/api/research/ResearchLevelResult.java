package com.circulation.prototype_eng.api.research;

/** Level-operation outcomes disclose no private source state on rejection. */
public enum ResearchLevelResult {
    APPLIED, UNCHANGED, PERMISSION_DENIED, STALE_CONTEXT, SOURCE_MISMATCH, OUT_OF_RANGE,
    SOURCE_RESERVED, BUSY, RECOVERY_PENDING
}
