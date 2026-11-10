package com.circulation.prototype_eng.api.research.download;

import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A business refusal or frozen candidate output; no inventory or research mutation has occurred.
 */
@SuppressWarnings("unused") // Public result consumed by download transactions.
public final class ResearchItemDownloadEvaluation {
    private final Status status;
    @Nullable
    private final ItemStack output;

    private ResearchItemDownloadEvaluation(Status status, @Nullable ItemStack output) {
        this.status = status;
        this.output = output;
    }

    static ResearchItemDownloadEvaluation noEvent() {
        return new ResearchItemDownloadEvaluation(Status.NO_EVENT, null);
    }

    static ResearchItemDownloadEvaluation rejected() {
        return new ResearchItemDownloadEvaluation(Status.REJECTED, null);
    }

    static ResearchItemDownloadEvaluation success(ItemStack output) {
        return new ResearchItemDownloadEvaluation(Status.SUCCESS, DownloadValidation.copyStack(output, "Download output"));
    }

    public Status status() {
        return status;
    }

    /**
     * Independent stack on each access, or EMPTY for either business refusal.
     */
    public ItemStack output() {
        return output == null ? ItemStack.EMPTY : output.copy();
    }

    public enum Status {NO_EVENT, REJECTED, SUCCESS}
}
