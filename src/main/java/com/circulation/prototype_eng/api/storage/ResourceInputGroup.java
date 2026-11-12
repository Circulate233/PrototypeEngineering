package com.circulation.prototype_eng.api.storage;

import ae2.api.util.AEColor;

import java.util.Objects;

/** Stable machine-input selection; a partition isolates one pattern inside a color group. */
public record ResourceInputGroup(AEColor color, String partition) {
    public static final ResourceInputGroup RED = new ResourceInputGroup(AEColor.RED, "");

    public ResourceInputGroup {
        Objects.requireNonNull(color);
        Objects.requireNonNull(partition);
        if (color == AEColor.TRANSPARENT || partition.length() > 1024)
            throw new IllegalArgumentException("Invalid resource input group");
    }

    public ResourceInputGroup(AEColor color) {
        this(color, "");
    }
}
