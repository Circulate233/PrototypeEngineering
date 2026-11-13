package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.delivery.DownloadOutputKey;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryTemplates;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Objects;
import java.util.UUID;

/**
 * Permanent nonce result after all transient inventory evidence has been durably released.
 * Fragments are borrowed from the completed operation and remain immutable for this receipt's
 * lifetime. Neither the supplier nor a consumer may mutate their list.
 */
public record ResearchDownloadReceipt(UUID operationId, ResearchDownloadOperation.Intent intent,
                                      ResearchDownloadSources.Plan sources, NBTTagCompound output, long amount,
                                      Outcome outcome, ObjectList<ItemDeliveryFragment> fragments) {
    public ResearchDownloadReceipt {
        DownloadValidation.requireUuid(operationId, "Download operation");
        Objects.requireNonNull(intent);
        Objects.requireNonNull(sources);
        Objects.requireNonNull(outcome);
        output = ItemDeliveryTemplates.copy(output);
        if (amount < 1 || output.getByte("Count") != 1 || !intent.teamId().equals(sources.teamId())
            || !intent.researchId().equals(sources.researchId()) || intent.level() != sources.downloadLevel()) {
            throw new IllegalArgumentException("Terminal download differs from its frozen intent");
        }
        Objects.requireNonNull(fragments);
        if (outcome == Outcome.ABORTED && !fragments.isEmpty())
            throw new IllegalArgumentException("Aborted download has output effects");
        if (outcome == Outcome.COMPLETED) {
            var parent = new DownloadOutputKey(intent.playerId(), operationId);
            var ids = new ObjectOpenHashSet<UUID>(fragments.size());
            long remaining = amount;
            for (var fragment : fragments) {
                if (fragment.status() != ItemDeliveryFragment.Status.CONFIRMED || !parent.equals(fragment.parent())
                    || !ids.add(fragment.id()) || fragment.count() > remaining
                    || !fragment.taggedStackNbt().equals(ItemDeliveryTemplates.taggedLeaf(output, fragment.count(), parent, fragment.id()))) {
                    throw new IllegalArgumentException("Terminal output proof differs from its frozen download");
                }
                remaining -= fragment.count();
            }
            if (remaining != 0) throw new IllegalArgumentException("Terminal output proof does not conserve quantity");
        }
    }

    public static ResearchDownloadReceipt completed(ResearchDownloadOperation operation) {
        if (operation.status() != ResearchDownloadOperation.Status.DELIVERING) {
            throw new IllegalStateException("Download has no output delivery to complete");
        }
        for (var fragment : operation.fragments()) {
            if (fragment.status() != ItemDeliveryFragment.Status.CONFIRMED) {
                throw new IllegalStateException("Download still owes an item effect");
            }
        }
        return of(operation, Outcome.COMPLETED);
    }

    public static ResearchDownloadReceipt aborted(ResearchDownloadOperation operation) {
        if (operation.status() != ResearchDownloadOperation.Status.ABORTING) {
            throw new IllegalStateException("Download has not durably decided to abort");
        }
        return of(operation, Outcome.ABORTED);
    }

    private static ResearchDownloadReceipt of(ResearchDownloadOperation operation, Outcome outcome) {
        return new ResearchDownloadReceipt(operation.id(), operation.intent(), operation.sources(),
            operation.output(), operation.amount(), outcome, operation.fragments());
    }

    @Override
    public NBTTagCompound output() {
        return output.copy();
    }

    public enum Outcome {COMPLETED, ABORTED}
}
