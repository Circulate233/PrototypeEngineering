package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * Permanent nonce result after all transient inventory evidence has been durably released.
 * Output NBT and fragments are borrowed from the completed operation. Suppliers and readers must
 * not mutate them while a receipt retains them.
 */
public record ResearchDownloadReceipt(UUID operationId, ResearchDownloadOperation.Intent intent,
                                      ResearchDownloadSources.Plan sources, NBTTagCompound output, long amount,
                                      Outcome outcome, ObjectList<ItemDeliveryFragment> fragments) {
    public static ResearchDownloadReceipt completed(ResearchDownloadOperation operation) {
        if (operation.status() != ResearchDownloadOperation.Status.DELIVERING) {
            throw new IllegalStateException("Download has no output delivery to complete");
        }
        var elements = operation.fragments();
        for (int index = 0; index < elements.size(); index++) {
            var fragment = elements.get(index);
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

    public enum Outcome {COMPLETED, ABORTED}
}
