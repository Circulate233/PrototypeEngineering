package com.circulation.prototype_eng.api.research.download;

import net.minecraft.util.ResourceLocation;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * One server-produced candidate. Its output, material and source plan remain borrowed read-only.
 * The owner validates current before admitting the click; execution freezes this candidate instead
 * of invoking the callback again. The source plan is checked again after original sources exist.
 */
public record ResearchItemDownloadPreview(ResourceLocation researchId, ResearchDownloadMaterial material,
                                          ResearchItemDownloadEvaluation output,
                                          ResearchDownloadSources.Evaluation sources,
                                          @Nullable ResearchDataCenters.Target target, BooleanSupplier current) {
    public boolean ready() {
        return output.status() == ResearchItemDownloadEvaluation.Status.SUCCESS && sources.plan() != null;
    }

    @SuppressWarnings("unused") // The database display consumes the server's projected effective loss.
    public int teamLevelLoss() {
        return sources.teamLevelLoss();
    }
}
