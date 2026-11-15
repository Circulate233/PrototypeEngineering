package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;

import java.util.UUID;

/**
 * Frozen database and single-slot obligations, checkpointed by the research world's writer.
 */
public record ResearchDiskOperation(Intent intent, ResearchDataCenters.Target target, ResearchDiskCommit commit,
                                    ResearchDownloadMaterial material, long createdAt, Status status,
                                    boolean databaseArmed) {
    public void validateSaved() {
        if (createdAt < 0 || !intent.playerId().equals(commit.actor()) || !intent.teamId().equals(commit.teamId())
            || !intent.databaseId().equals(commit.before().dataCenterId()) || !intent.databaseId().equals(target.dataCenterId())
            || !commit.operationId().equals(material.operationId()) || !intent.playerId().equals(material.playerId())
            || intent.slot() != material.slot() || material.status() != ResearchDownloadMaterial.Status.PREPARED
            || (status == Status.DATABASE_COMMITTED || status == Status.COMPLETED || status == Status.RELEASED) && !databaseArmed) {
            throw new IllegalArgumentException("Research disk operation has inconsistent frozen ownership");
        }
    }

    public UUID id() {
        return commit.operationId();
    }

    public ResearchDiskOperation transition(Status next) {
        boolean allowed = switch (status) {
            case PREPARED -> next == Status.DATABASE_COMMITTED || next == Status.ABORTING;
            case DATABASE_COMMITTED -> next == Status.COMPLETED;
            case COMPLETED -> next == Status.RELEASED;
            case ABORTING -> next == Status.ABORTED;
            case RELEASED, ABORTED -> false;
        };
        if (!allowed) throw new IllegalStateException("Invalid research disk operation transition");
        return new ResearchDiskOperation(intent, target, commit, material, createdAt, next, databaseArmed);
    }

    public ResearchDiskOperation armed() {
        if (status != Status.PREPARED || databaseArmed)
            throw new IllegalStateException("Disk operation cannot arm another database attempt");
        return new ResearchDiskOperation(intent, target, commit, material, createdAt, status, true);
    }

    public boolean terminal() {
        return status == Status.RELEASED || status == Status.ABORTED;
    }

    public enum Status {PREPARED, DATABASE_COMMITTED, COMPLETED, RELEASED, ABORTING, ABORTED}

    public record Intent(UUID playerId, UUID nonce, UUID databaseId, String teamId, int slot) {
        public Intent {
            if (teamId.isBlank() || !teamId.equals(teamId.trim())) {
                throw new IllegalArgumentException("Research disk request requires a canonical team");
            }
            PlayerInventoryNbt.encodedSlot(slot);
        }
    }
}
