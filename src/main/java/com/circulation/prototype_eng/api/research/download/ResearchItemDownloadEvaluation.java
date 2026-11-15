package com.circulation.prototype_eng.api.research.download;

import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A business refusal or frozen candidate output; no inventory or research mutation has occurred.
 */
@SuppressWarnings("unused") // Public result consumed by download transactions.
public final class ResearchItemDownloadEvaluation {
    private static final ResearchItemDownloadEvaluation NO_EVENT = new ResearchItemDownloadEvaluation(Status.NO_EVENT, null, 0, 0);
    private static final ResearchItemDownloadEvaluation REJECTED = new ResearchItemDownloadEvaluation(Status.REJECTED, null, 0, 0);
    private final Status status;
    @Nullable
    private final ItemStack output;
    private final int researchLevel;
    private final int levelCost;

    private ResearchItemDownloadEvaluation(Status status, @Nullable ItemStack output, int researchLevel, int levelCost) {
        this.status = status;
        this.output = output;
        this.researchLevel = researchLevel;
        this.levelCost = levelCost;
    }

    static ResearchItemDownloadEvaluation noEvent() {
        return NO_EVENT;
    }

    static ResearchItemDownloadEvaluation rejected() {
        return REJECTED;
    }

    public static ResearchItemDownloadEvaluation success(ItemStack output, int researchLevel, int levelCost) {
        return new ResearchItemDownloadEvaluation(Status.SUCCESS, output, researchLevel, levelCost);
    }

    public int researchLevel() {
        return researchLevel;
    }

    public int levelCost() {
        return levelCost;
    }

    public Status status() {
        return status;
    }

    /**
     * Borrowed read-only output, or EMPTY for either business refusal.
     */
    public ItemStack output() {
        return output == null ? ItemStack.EMPTY : output;
    }

    public enum Status {NO_EVENT, REJECTED, SUCCESS}
}
