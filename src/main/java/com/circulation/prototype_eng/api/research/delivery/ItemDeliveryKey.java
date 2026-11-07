package com.circulation.prototype_eng.api.research.delivery;

import java.util.UUID;

/**
 * Durable owner identity of an item delivery; it does not grant research reward eligibility.
 */
public interface ItemDeliveryKey {
    UUID playerUuid();
}
