package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Optional backend responsibility for durable disk imports. A backend without this capability cannot
 * consume a disk. The content and complete operation receipt must share one atomic saved state.
 */
public interface ResearchDiskStorage extends DataCenterStorage {
    /** Check real platform availability before preparing a new import; this grants no write authority. */
    boolean diskPersistenceAvailable();

    /**
     * Revalidate the complete batch and caller authority before atomically publishing contents and receipt.
     * A rejected return guarantees no effect from this invocation. Exceptions are unknown outcomes.
     * An accepted return, including an exact receipt replay, is not proof of durable storage.
     */
    Result commitDisk(ResearchDiskCommit commit, TeamResearchView cloud, BooleanSupplier current);

    /** Missing evidence never proves that an import succeeded or that its disk can be consumed. */
    @Nullable
    ResearchDiskCommit receipt(UUID operationId);

    /**
     * Save the current complete state and read back the authoritative recovery source, including this
     * exact receipt. Recheck the original physical binding through the barrier, independently of old
     * contents, team membership or selected database. Return only after durability is proven.
     */
    void persistDisk(ResearchDiskCommit commit, BooleanSupplier physicalCurrent);

    /**
     * Only a durably completed world operation may remove its receipt. Missing receipts are idempotent:
     * still save and verify the current complete state. Conflicting operation evidence must fail.
     */
    void clearDiskReceipt(ResearchDiskCommit commit, BooleanSupplier physicalCurrent);
}
