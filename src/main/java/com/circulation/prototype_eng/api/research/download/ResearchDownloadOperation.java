package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.UUID;

/**
 * Frozen event output and source payment, retained until material and item effects are confirmed.
 * Output NBT and fragments are borrowed immutable data. Suppliers and readers must not mutate them
 * while an operation or saved checkpoint retains them; replacement transitions create their own list.
 */
public record ResearchDownloadOperation(UUID id, Intent intent, ResearchDownloadSources.Plan sources,
                                        ResearchDownloadMaterial material, NBTTagCompound output, long amount,
                                        long createdAt, Status status, boolean materialArmed,
                                        ObjectList<ItemDeliveryFragment> fragments) {
    public boolean reservesSources() {
        return status != Status.SOURCES_COMMITTED && status != Status.DELIVERING;
    }

    public ResearchDownloadOperation armed() {
        require(Status.PREPARED);
        return new ResearchDownloadOperation(id, intent, sources, material, output, amount, createdAt, status, true, fragments);
    }

    public ResearchDownloadOperation materialCommitted(ResearchDownloadMaterial committed) {
        require(Status.PREPARED);
        if (!material.sameOperation(committed) || committed.status() != ResearchDownloadMaterial.Status.COMMITTED) {
            throw new IllegalArgumentException("Material commit differs from the frozen download");
        }
        return new ResearchDownloadOperation(id, intent, sources, committed, output, amount, createdAt, Status.MATERIAL_COMMITTED, materialArmed, fragments);
    }

    public ResearchDownloadOperation sourcesCommitted() {
        require(Status.MATERIAL_COMMITTED);
        return new ResearchDownloadOperation(id, intent, sources, material, output, amount, createdAt, Status.SOURCES_COMMITTED, materialArmed, fragments);
    }

    public ResearchDownloadOperation plan(ObjectList<ItemDeliveryFragment> planned) {
        require(Status.SOURCES_COMMITTED);
        for (int index1 = 0; index1 < planned.size(); index1++) {
            var fragment = planned.get(index1);
            if (fragment.status() != ItemDeliveryFragment.Status.PLANNED || (fragment.entityUuid() != null)) {
                throw new IllegalArgumentException("New download output is already issued");
            }
        }
        return new ResearchDownloadOperation(id, intent, sources, material, output, amount, createdAt, Status.DELIVERING, materialArmed, planned);
    }

    public ResearchDownloadOperation replace(ItemDeliveryFragment replacement) {
        require(Status.DELIVERING);
        var updated = new ObjectArrayList<>(fragments);
        for (int index = 0; index < updated.size(); index++) {
            var previous = updated.get(index);
            if (!previous.id().equals(replacement.id())) continue;
            previous.requireTransitionTo(replacement);
            updated.set(index, replacement);
            return new ResearchDownloadOperation(id, intent, sources, material, output, amount, createdAt, status, materialArmed, updated);
        }
        throw new IllegalArgumentException("Download fragment is not part of this operation");
    }

    public ResearchDownloadOperation aborting() {
        require(Status.PREPARED);
        return new ResearchDownloadOperation(id, intent, sources, material, output, amount, createdAt, Status.ABORTING, materialArmed, fragments);
    }

    private void require(Status expected) {
        if (status != expected)
            throw new IllegalStateException("Download transition requires " + expected + ", found " + status);
    }

    public enum Status {PREPARED, MATERIAL_COMMITTED, SOURCES_COMMITTED, DELIVERING, ABORTING}

    /**
     * Client retry identity and the server-confirmed original team; retries cannot replace either.
     */
    public record Intent(UUID playerId, UUID nonce, UUID databaseId, String teamId, ResourceLocation researchId,
                         int level, int slot) {
        public Intent {
            if (level < 1 || slot < 0 || slot >= 41)
                throw new IllegalArgumentException("Invalid download level or player inventory slot");
        }
    }
}
