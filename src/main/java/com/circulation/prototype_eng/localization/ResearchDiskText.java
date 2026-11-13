package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImportResult;

/**
 * Feedback for using a research disk on a database block.
 */
public enum ResearchDiskText implements LocalizationEnum {
    DiskImportCompleted, DiskImportAborted, RecoveryPending, Busy, Stale, Forbidden, InvalidDisk,
    DiskImportUnsupportedStorage, DiskImportRejected, DiskImportNonceConflict, RecoveryError;

    private final String translationKey = "message." + Tags.MOD_ID + ".research_disk." + name();

    public static ResearchDiskText diskImport(ResearchDiskImportResult.Status status) {
        return switch (status) {
            case COMPLETED -> DiskImportCompleted;
            case ABORTED -> DiskImportAborted;
            case PENDING -> RecoveryPending;
            case BUSY -> Busy;
            case STALE_CONTEXT -> Stale;
            case PERMISSION_DENIED -> Forbidden;
            case MISSING_DISK -> InvalidDisk;
            case UNSUPPORTED_STORAGE -> DiskImportUnsupportedStorage;
            case DISK_REJECTED -> DiskImportRejected;
            case NONCE_CONFLICT -> DiskImportNonceConflict;
            case RECOVERY_ERROR -> RecoveryError;
        };
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
