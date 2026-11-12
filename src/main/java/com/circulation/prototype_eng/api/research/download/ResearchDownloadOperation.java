package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.delivery.DownloadOutputKey;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryTemplates;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Frozen event output and source payment, retained until material and item effects are confirmed.
 */
public record ResearchDownloadOperation(UUID id, Intent intent, ResearchDownloadSources.Plan sources,
                                        ResearchDownloadMaterial material, NBTTagCompound output, long amount,
                                        long createdAt, Status status, boolean materialArmed,
                                        ObjectList<ItemDeliveryFragment> fragments) {
    public ResearchDownloadOperation {
        DownloadValidation.requireUuid(id, "Download operation");
        Objects.requireNonNull(intent);
        Objects.requireNonNull(sources);
        Objects.requireNonNull(material);
        Objects.requireNonNull(status);
        output = ItemDeliveryTemplates.copy(output);
        if (output.getByte("Count") != 1 || amount < 1 || createdAt < 0) {
            throw new IllegalArgumentException("Download output must retain a one-item template and positive logical amount");
        }
        if (!id.equals(material.operationId()) || !intent.playerId().equals(material.playerId())
            || intent.slot() != material.slot() || !intent.teamId().equals(sources.teamId())
            || !intent.researchId().equals(sources.researchId()) || intent.level() != sources.downloadLevel()) {
            throw new IllegalArgumentException("Download operation sources, actor or material differ from its intent");
        }
        boolean materialCommitted = status != Status.PREPARED && status != Status.ABORTING;
        if (materialCommitted && !materialArmed)
            throw new IllegalArgumentException("Committed material was never durably armed");
        if (materialCommitted != (material.status() == ResearchDownloadMaterial.Status.COMMITTED)) {
            throw new IllegalArgumentException("Download phase differs from its material evidence");
        }
        fragments = ObjectLists.unmodifiable(new ObjectArrayList<>(Objects.requireNonNull(fragments)));
        if (status != Status.DELIVERING && !fragments.isEmpty()) {
            throw new IllegalArgumentException("Only output delivery carries item fragments");
        }
        if (status == Status.DELIVERING) {
            if (fragments.isEmpty())
                throw new IllegalArgumentException("Download delivery requires a complete output plan");
            var key = new DownloadOutputKey(intent.playerId(), id);
            var seen = new ObjectOpenHashSet<UUID>(fragments.size());
            long remaining = amount;
            for (var fragment : fragments) {
                if (!fragment.parent().equals(key) || !seen.add(fragment.id()) || fragment.count() > remaining
                    || !fragment.taggedStackNbt().equals(ItemDeliveryTemplates.taggedLeaf(output, fragment.count(), key, fragment.id()))) {
                    throw new IllegalArgumentException("Download fragments differ from the frozen output");
                }
                remaining -= fragment.count();
            }
            if (remaining != 0)
                throw new IllegalArgumentException("Download fragments do not conserve output quantity");
        }
    }

    @Override
    public NBTTagCompound output() {
        return output.copy();
    }

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
        for (var fragment : planned) {
            if (fragment.status() != ItemDeliveryFragment.Status.PLANNED || fragment.entityUuid().isPresent()) {
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
            DownloadValidation.requireUuid(playerId, "Download player");
            DownloadValidation.requireUuid(nonce, "Download request nonce");
            DownloadValidation.requireUuid(databaseId, "Download database");
            DownloadValidation.requireTeamKey(teamId);
            Objects.requireNonNull(researchId);
            if (level < 1 || slot < 0 || slot >= 41)
                throw new IllegalArgumentException("Invalid download level or player inventory slot");
        }
    }
}
