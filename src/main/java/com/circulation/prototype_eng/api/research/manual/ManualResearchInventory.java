package com.circulation.prototype_eng.api.research.manual;

import ae2.api.stacks.AEKey2LongMap;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Owner-thread inventory and durable receipt boundary for one real online player. */
public interface ManualResearchInventory {
    UUID playerId();
    @Nullable ManualResearchMaterialReceipt find(UUID operationId);
    @Nullable ManualResearchMaterialReceipt findBySession(UUID sessionId);
    byte[] snapshotInventory();
    boolean matches(byte[] inventorySnapshot);
    /** Checks all requested quantities without creating an intent, receipt or material mutation. */
    boolean hasMaterials(AEKey2LongMap quantities);
    /** Retains PREPARED evidence even when synchronous saving throws. */
    void recordPrepared(ManualResearchMaterialReceipt receipt);
    /**
     * Rechecks every quantity and the complete before image. False changes nothing. Success retains
     * COMMITTED with its complete after image before saving; a save failure throws without refunding.
     * An already COMMITTED retry only confirms its after image and saves, never consumes again.
     */
    boolean consume(ManualResearchMaterialReceipt receipt, AEKey2LongMap quantities, long worldTime);
    void persistCommitted(UUID operationId);
    void confirmSettlement(UUID operationId, long worldTime);
    /** Missing receipts permit idempotent deletion retries after the world confirms the exact abort. */
    void removePrepared(UUID operationId);
    /** Missing receipts permit idempotent deletion retries after the world confirms exact settlement. */
    void removeConfirmed(UUID operationId);
}
