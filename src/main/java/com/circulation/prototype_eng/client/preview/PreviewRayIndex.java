package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToDoubleFunction;

/** Section broad phase: hovering does not run native block hooks across the entire structure. */
final class PreviewRayIndex {
    private final Object2ObjectOpenHashMap<BlockPos, ObjectArrayList<StructurePreviewModel.Cell>> sections = new Object2ObjectOpenHashMap<>();

    void add(StructurePreviewModel.Cell cell) {
        var p = cell.position();
        var section = new BlockPos(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
        sections.computeIfAbsent(section, _ -> new ObjectArrayList<>()).add(cell);
    }

    void clear() { sections.clear(); }

    @Nullable StructurePreviewView.Click pick(StructurePreviewView view, StructurePreviewCamera.Ray ray,
                                            ToDoubleFunction<StructurePreviewModel.Cell> distanceTo) {
        StructurePreviewModel.Cell hit = null;
        double best = Double.POSITIVE_INFINITY;
        var controller = view.controllerCell();
        if (controller != null && view.visible(controller)) {
            double distance = distanceTo.applyAsDouble(controller);
            if (Double.isFinite(distance) && distance >= 0) { best = distance; hit = controller; }
        }
        for (var section : sections.object2ObjectEntrySet()) {
            var p = section.getKey();
            double near = StructurePreviewCamera.intersect(ray, p.getX() << 4, p.getY() << 4, p.getZ() << 4, 16);
            if (!Double.isFinite(near) || near > best) continue;
            for (var cell : section.getValue()) {
                if (!view.visible(cell)) continue;
                double distance = distanceTo.applyAsDouble(cell);
                if (distance >= 0 && distance < best) { best = distance; hit = cell; }
            }
        }
        return hit == null ? null : new StructurePreviewView.Click(view.token(), hit);
    }
}
