package com.circulation.prototype_eng.api.research.delivery;

import java.util.Objects;
import java.util.UUID;

/**
 * Output identity of one paid download operation, independent of permanent research rewards.
 */
public record DownloadOutputKey(UUID playerUuid, UUID operationId) implements ItemDeliveryKey {
    public DownloadOutputKey {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(operationId, "operationId");
    }
}
