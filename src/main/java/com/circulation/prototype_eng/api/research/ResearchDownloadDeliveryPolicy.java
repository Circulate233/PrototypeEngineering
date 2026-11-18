package com.circulation.prototype_eng.api.research;

/**
 * Available download delivery paths for research. Ordinary levels belong to players.
 */
public enum ResearchDownloadDeliveryPolicy {
    /**
     * Item download requires no stored database level; it consumes the declared member levels.
     */
    ITEM_ONLY,
    /**
     * Database download preserves levels; item download consumes the declared member levels.
     */
    DATABASE_AND_ITEM
}
