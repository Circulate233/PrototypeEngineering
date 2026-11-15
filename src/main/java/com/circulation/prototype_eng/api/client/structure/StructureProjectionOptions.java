package com.circulation.prototype_eng.api.client.structure;

import org.jetbrains.annotations.Nullable;


/**
 * Native outline/ghost presentation tuning. Display observations do not prove structure matching.
 */
public record StructureProjectionOptions(Style style, boolean hideDisplayedMatches,
                                         @Nullable StructurePreviewModel.Layer layer,
                                         int expansionStepsPerTick, int scanChecksPerTick,
                                         int renderVisitsPerFrame, double maxDistance,
                                         int blockModelBuildVisitsPerFrame, int blockModelChunkVisitsPerFrame,
                                         int blockModelRenderAllChunksIfUnder) {
    public StructureProjectionOptions {
        if (expansionStepsPerTick <= 0 || scanChecksPerTick < 0 || renderVisitsPerFrame <= 0
            || !Double.isFinite(maxDistance) || maxDistance <= 0 || blockModelBuildVisitsPerFrame <= 0
            || blockModelChunkVisitsPerFrame <= 0 || blockModelRenderAllChunksIfUnder < 0)
            throw new IllegalArgumentException("Invalid projection presentation limits");
    }

    /**
     * Retains C's existing source/API constructor; native VBO work uses its own PM chunk budgets.
     */
    public StructureProjectionOptions(Style style, boolean hideDisplayedMatches, @Nullable StructurePreviewModel.Layer layer,
                                      int expansionStepsPerTick, int scanChecksPerTick, int renderVisitsPerFrame, double maxDistance) {
        this(style, hideDisplayedMatches, layer, expansionStepsPerTick, scanChecksPerTick, renderVisitsPerFrame, maxDistance, 4096, 96, 128);
    }

    /**
     * PM's existing scan/render/distance defaults, plus a bounded expansion cursor.
     */
    @SuppressWarnings("unused") // Public native box-projection defaults for addon callers.
    public StructureProjectionOptions() {
        this(Style.TRANSLUCENT_BOX, true, null, 2048, 2048, 4096, 64.0);
    }

    /**
     * Keeps the same renderer budgets and visual policy when choosing another projection plane.
     */
    public StructureProjectionOptions withLayer(@Nullable StructurePreviewModel.Layer nextLayer) {
        return new StructureProjectionOptions(style, hideDisplayedMatches, nextLayer, expansionStepsPerTick,
            scanChecksPerTick, renderVisitsPerFrame, maxDistance, blockModelBuildVisitsPerFrame,
            blockModelChunkVisitsPerFrame, blockModelRenderAllChunksIfUnder);
    }

    public enum Style {BOX_OUTLINE, TRANSLUCENT_BOX, BOX_AND_OUTLINE, BLOCK_MODEL}
}
