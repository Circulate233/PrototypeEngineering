package com.circulation.prototype_eng.api.storage;

/**
 * Loaded native and addon tile hosts opt into the same owner-boundary configuration bridge.
 */
public interface ReconfigurableHatch {
    HatchTier tier();

    HatchConfiguration effectiveConfiguration();

    HatchReconfigurationStatus requestConfiguration(HatchConfiguration configuration);

    @SuppressWarnings("unused")
        // Public status polling for addon/script callers after a boundary request.
    HatchReconfigurationStatus configurationStatus();
}
