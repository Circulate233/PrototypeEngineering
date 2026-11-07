package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable operation proof shared by player inventory and the exact world-owned session intent. */
public record ManualResearchMaterialReceipt(UUID sessionId, String teamId, TeamResearchQueue.Key queueKey,
                                          ManualResearchSession.MaterialIntent intent, byte[] inventoryBefore,
                                          byte @Nullable [] inventoryAfter, Status status,
                                          long preparedAt, long committedAt, long confirmedAt) {
    public ManualResearchMaterialReceipt {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(teamId);
        Objects.requireNonNull(queueKey);
        Objects.requireNonNull(intent);
        Objects.requireNonNull(status);
        if (teamId.isBlank() || !teamId.equals(teamId.trim()) || preparedAt < 0) {
            throw new IllegalArgumentException("Invalid material receipt team identity or preparation time");
        }
        inventoryBefore = ManualResearchMaterialReceiptNbt.copyInventory(inventoryBefore);
        if (status == Status.PREPARED) {
            if (inventoryAfter != null || committedAt != -1 || confirmedAt != -1) {
                throw new IllegalArgumentException("PREPARED receipt cannot carry committed evidence");
            }
        } else {
            inventoryAfter = ManualResearchMaterialReceiptNbt.copyInventory(Objects.requireNonNull(inventoryAfter));
            if (committedAt < preparedAt || (status == Status.COMMITTED ? confirmedAt != -1 : confirmedAt < committedAt)) {
                throw new IllegalArgumentException("Invalid committed material receipt chronology");
            }
        }
    }

    public UUID operationId() { return intent.operationId(); }
    public UUID playerId() { return intent.contributor(); }
    @Override public byte[] inventoryBefore() { return inventoryBefore.clone(); }
    @Override public byte @Nullable [] inventoryAfter() { return inventoryAfter == null ? null : inventoryAfter.clone(); }

    public ManualResearchMaterialReceipt committed(byte[] after, long worldTime) {
        if (status != Status.PREPARED) { throw new IllegalStateException("Material receipt is already committed"); }
        return new ManualResearchMaterialReceipt(sessionId, teamId, queueKey, intent, inventoryBefore, after,
            Status.COMMITTED, preparedAt, worldTime, -1);
    }

    public ManualResearchMaterialReceipt confirmed(long worldTime) {
        if (status != Status.COMMITTED) { throw new IllegalStateException("Material receipt is not COMMITTED"); }
        return new ManualResearchMaterialReceipt(sessionId, teamId, queueKey, intent, inventoryBefore, inventoryAfter,
            Status.SETTLEMENT_CONFIRMED, preparedAt, committedAt, worldTime);
    }

    /** A PREPARED caller may retry after observing its matching COMMITTED inventory operation. */
    public boolean sameOperation(ManualResearchMaterialReceipt other) {
        return other != null && sessionId.equals(other.sessionId) && teamId.equals(other.teamId)
            && queueKey.equals(other.queueKey) && intent.equals(other.intent) && preparedAt == other.preparedAt
            && Arrays.equals(inventoryBefore, other.inventoryBefore);
    }

    @Override public boolean equals(Object other) {
        return other instanceof ManualResearchMaterialReceipt receipt && sameOperation(receipt)
            && status == receipt.status && committedAt == receipt.committedAt && confirmedAt == receipt.confirmedAt
            && Arrays.equals(inventoryAfter, receipt.inventoryAfter);
    }

    @Override public int hashCode() {
        int result = Objects.hash(sessionId, teamId, queueKey, intent, status, preparedAt, committedAt, confirmedAt);
        result = 31 * result + Arrays.hashCode(inventoryBefore);
        return 31 * result + Arrays.hashCode(inventoryAfter);
    }

    public enum Status { PREPARED, COMMITTED, SETTLEMENT_CONFIRMED }
}
