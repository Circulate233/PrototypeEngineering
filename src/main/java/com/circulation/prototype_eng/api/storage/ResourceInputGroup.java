package com.circulation.prototype_eng.api.storage;

import ae2.api.util.AEColor;

import java.util.Objects;

/** Stable input selection; uncolored storage is shared and never split into isolated partitions. */
public record ResourceInputGroup(AEColor color, String partition) {
    public static final ResourceInputGroup TRANSPARENT = new ResourceInputGroup(AEColor.TRANSPARENT, "");
    public static final ResourceInputGroup RED = new ResourceInputGroup(AEColor.RED, "");

    public ResourceInputGroup {
        Objects.requireNonNull(color);
        Objects.requireNonNull(partition);
        if (partition.length() > 1024)
            throw new IllegalArgumentException("Invalid resource input group");
        if (color == AEColor.TRANSPARENT) partition = "";
    }

    public ResourceInputGroup(AEColor color) {
        this(color, "");
    }
}
