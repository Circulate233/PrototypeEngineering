package com.circulation.prototype_eng.api.client.render;

import java.util.Objects;

public record GeckoStructureBinding(GeckoModelBinding model, SliceMode sliceMode) {
    public GeckoStructureBinding {
        Objects.requireNonNull(model);
        Objects.requireNonNull(sliceMode);
    }

    public GeckoStructureBinding(GeckoModelBinding model) {
        this(model, SliceMode.PER_SLICE);
    }

    public enum SliceMode {PER_SLICE, STRUCTURE_ONLY}
}
