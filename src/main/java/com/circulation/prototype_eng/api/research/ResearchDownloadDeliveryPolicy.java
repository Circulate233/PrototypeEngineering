package com.circulation.prototype_eng.api.research;

/**
 * Ordinary completion ownership and available download delivery paths, independent of specialOnly.
 */
public enum ResearchDownloadDeliveryPolicy {
    /**
     * Team-owned completion; item download consumes the declared number of levels.
     */
    ITEM_ONLY,
    /**
     * Player-owned completion; database download preserves levels, item download consumes them.
     */
    DATABASE_AND_ITEM
}
