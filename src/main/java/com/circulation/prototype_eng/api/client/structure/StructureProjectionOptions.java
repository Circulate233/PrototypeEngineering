package com.circulation.prototype_eng.api.client.structure;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Native outline/ghost presentation tuning. Display observations do not prove structure matching.
 */
public record StructureProjectionOptions(Style style, boolean hideDisplayedMatches,
                                         @Nullable StructurePreviewModel.Layer layer,
                                         int expansionStepsPerTick, int scanChecksPerTick,
                                         int renderVisitsPerFrame, double maxDistance) {
    public StructureProjectionOptions {
        Objects.requireNonNull(style);
        if (expansionStepsPerTick <= 0 || scanChecksPerTick < 0 || renderVisitsPerFrame <= 0
            || !Double.isFinite(maxDistance) || maxDistance <= 0)
            throw new IllegalArgumentException("Invalid projection presentation limits");
    }

    public enum Style {BOX_OUTLINE, TRANSLUCENT_BOX, BOX_AND_OUTLINE}

    /**
     * PM's existing scan/render/distance defaults, plus a bounded expansion cursor.
     */
    @SuppressWarnings("unused") // Public native box-projection defaults for addon callers.
    public StructureProjectionOptions() {
        this(Style.TRANSLUCENT_BOX, true, null, 2048, 2048, 4096, 64.0);
    }
}
