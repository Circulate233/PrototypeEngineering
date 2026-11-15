package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Optional immutable display geometry for an external shape; no matcher/factory crosses the wire.
 * Read only the immutable definition. Do not inspect World, mutate configuration or enter scheduler
 * callbacks. Children reference the existing shape graph, not another expansion/matching algorithm.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public interface StructurePreviewProvider {
    Geometry previewGeometry();

    enum Kind {
        OPAQUE, TEMPLATE, SLICE;

        /**
         * Shared declaration order; callers must not modify this array.
         */
        public static final Kind[] VALUES = values();
    }

    /**
     * Lists are borrowed read-only for the geometry lifetime and cell positions must be immutable.
     */
    record Geometry(Kind kind, BlockPos offset, int minCount, int maxCount, BlockPos sliceOffset,
                    List<BlockPos> cells, List<MachineStructure> children) {
        public Geometry {
            offset = offset.toImmutable();
            sliceOffset = sliceOffset.toImmutable();
            if (minCount < 0 || maxCount < minCount) throw new IllegalArgumentException("Invalid preview slice counts");
        }
    }
}
