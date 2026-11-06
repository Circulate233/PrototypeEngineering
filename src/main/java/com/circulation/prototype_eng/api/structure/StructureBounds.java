package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/** Inclusive area requested by a pattern or validator, including gaps in sparse patterns. */
public record StructureBounds(BlockPos min, BlockPos max) {
    public StructureBounds {
        min = Objects.requireNonNull(min).toImmutable();
        max = Objects.requireNonNull(max).toImmutable();
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException("Inverted structure bounds");
        }
    }

    public static StructureBounds at(BlockPos position) { return new StructureBounds(position, position); }
}
