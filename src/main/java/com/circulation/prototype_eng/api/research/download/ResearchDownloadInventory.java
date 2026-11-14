package com.circulation.prototype_eng.api.research.download;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Exact player inventory and durable receipts owned by the world's download operation.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
@SuppressWarnings("unused") // Public world-owned download inventory contract, also implemented by external hosts.
public interface ResearchDownloadInventory {
    UUID playerId();

    @Nullable ResearchDownloadMaterial capture(UUID operationId, int slot);

    @Nullable ResearchDownloadMaterial find(UUID operationId);

    ObjectList<ResearchDownloadMaterial> operations();

    boolean matches(NBTTagCompound inventory);

    void recordPrepared(ResearchDownloadMaterial receipt);

    /**
     * PREPARED requires its before inventory; COMMITTED retries save the receipt without consuming again.
     */
    boolean consume(ResearchDownloadMaterial receipt);

    void persistCommitted(UUID operationId);

    /**
     * Only exact PREPARED before evidence permits an abort. Missing retries still durably save removal.
     */
    void removePrepared(UUID operationId);

    /**
     * The caller must first durably save the world's SOURCES_COMMITTED state. This cleanup checks
     * exact receipt identity and saves its removal; it does not require the old after inventory forever.
     */
    void removeCommitted(UUID operationId);
}
