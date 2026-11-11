package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Optional immutable display geometry for an external shape; no matcher/factory crosses the wire.
 * Read only the immutable definition. Do not inspect World, mutate configuration or enter scheduler
 * callbacks. Children reference the existing shape graph, not another expansion/matching algorithm.
 */
public interface StructurePreviewProvider {
    Geometry previewGeometry();

    enum Kind {OPAQUE, TEMPLATE, SLICE}

    record Geometry(Kind kind, BlockPos offset, int minCount, int maxCount, BlockPos sliceOffset,
                    List<BlockPos> cells, List<MachineStructure> children) {
        public Geometry {
            Objects.requireNonNull(kind);
            offset = Objects.requireNonNull(offset).toImmutable();
            sliceOffset = Objects.requireNonNull(sliceOffset).toImmutable();
            if (minCount < 0 || maxCount < minCount) throw new IllegalArgumentException("Invalid preview slice counts");
            var positions = new ObjectArrayList<BlockPos>(cells.size());
            for (var position : cells) positions.add(Objects.requireNonNull(position).toImmutable());
            cells = ObjectLists.unmodifiable(positions);
            children = List.copyOf(children);
        }
    }
}
