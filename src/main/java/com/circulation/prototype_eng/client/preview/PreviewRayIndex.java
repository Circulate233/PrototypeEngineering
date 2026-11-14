package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToDoubleFunction;

/**
 * Section broad phase: hovering does not run native block hooks across the entire structure.
 */
final class PreviewRayIndex {
    private final BlockPos2ObjectMap<ObjectArrayList<StructurePreviewModel.Cell>> sections = new BlockPos2ObjectMap<>();

    void add(StructurePreviewModel.Cell cell) {
        var p = cell.position();
        var section = BlockPos2ObjectMap.toLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
        sections.computeIfAbsent(section, _ -> new ObjectArrayList<>()).add(cell);
    }

    void clear() {
        sections.clear();
    }

    @Nullable StructurePreviewView.Click pick(StructurePreviewView view, StructurePreviewCamera.Ray ray,
                                              ToDoubleFunction<StructurePreviewModel.Cell> distanceTo) {
        StructurePreviewModel.Cell hit = null;
        double best = Double.POSITIVE_INFINITY;
        var controller = view.controllerCell();
        if (controller != null && view.visible(controller)) {
            double distance = distanceTo.applyAsDouble(controller);
            if (Double.isFinite(distance) && distance >= 0) {
                best = distance;
                hit = controller;
            }
        }
        for (var entries = sections.fastIterator(); entries.hasNext(); ) {
            var section = entries.next();
            long packed = section.getLongKey();
            int x = BlockPos2ObjectMap.unpackX(packed), y = BlockPos2ObjectMap.unpackY(packed), z = BlockPos2ObjectMap.unpackZ(packed);
            double near = StructurePreviewCamera.intersect(ray, x << 4, y << 4, z << 4, 16);
            if (!Double.isFinite(near) || near > best) continue;

            var elements = section.getValue();
            for (int index = 0; index < elements.size(); index++) {
                var cell = elements.get(index);
                if (!view.visible(cell)) continue;
                double distance = distanceTo.applyAsDouble(cell);
                if (distance >= 0 && distance < best) {
                    best = distance;
                    hit = cell;
                }
            }

        }
        return hit == null ? null : new StructurePreviewView.Click(view.token(), hit);
    }
}
