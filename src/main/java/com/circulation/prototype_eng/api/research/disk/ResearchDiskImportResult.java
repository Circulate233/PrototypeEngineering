package com.circulation.prototype_eng.api.research.disk;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** Authoritative request outcome; PENDING retains its original disk and database obligations. */
public record ResearchDiskImportResult(Status status, @Nullable UUID operationId,
                                       @Nullable ResearchDiskImport.Result precheck) {
    public ResearchDiskImportResult {
        Objects.requireNonNull(status);
    }

    public enum Status {COMPLETED, ABORTED, PENDING, BUSY, STALE_CONTEXT, PERMISSION_DENIED,
        MISSING_DISK, UNSUPPORTED_STORAGE, DISK_REJECTED, NONCE_CONFLICT, RECOVERY_ERROR}
}
