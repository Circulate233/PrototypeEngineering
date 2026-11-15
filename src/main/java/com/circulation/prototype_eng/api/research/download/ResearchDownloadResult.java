package com.circulation.prototype_eng.api.research.download;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Typed request and recovery outcome; PENDING retains the exact operation and its obligations.
 */
public record ResearchDownloadResult(Status status, @Nullable UUID operationId,
                                     @Nullable ResearchDownloadSources.Status sourceStatus) {

    public enum Status {
        COMPLETED, ABORTED, PENDING, BUSY, STALE_CONTEXT, PERMISSION_DENIED, SOURCE_REJECTED,
        MISSING_MATERIAL, OUTPUT_BLOCKED, NO_EVENT, EVENT_REJECTED, NONCE_CONFLICT, RECOVERY_ERROR
    }
}
