package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.List;
import java.nio.ByteOrder;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Only the current filtered C samples can prepare or emit CPU quads; pending work never bypasses filtering.
 */
final class PreviewSampleBlockModels {
    private final Thread owner = Thread.currentThread();
    private final BiFunction<StructurePreviewModel, StructurePreviewModel.Cell, Preparation> preparations;
    private final Reference2ObjectOpenHashMap<StructurePreviewModel.Cell, CellMesh> cells = new Reference2ObjectOpenHashMap<>();
    private StructurePreviewModel model;
    private Object epoch = new Object();
    private int workCursor;

    PreviewSampleBlockModels(BiFunction<StructurePreviewModel, StructurePreviewModel.Cell, Preparation> preparations) {
        this.preparations = preparations;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Sample models require their client owner thread");
    }

    void invalidate() {
        requireOwner();
        model = null;
        cells.clear();
        workCursor = 0;
        epoch = new Object();
    }

    /**
     * Initial cell admission, every preparation visit and every emitted quad consume this independent work budget.
     */
    List<StructureProjectionState.Sample> present(StructurePreviewModel expected, List<StructureProjectionState.Sample> samples,
                                                  int visits, BooleanSupplier current, VertexSink output) {
        requireOwner();
        if (visits < 0) throw new IllegalArgumentException("Negative sample quad work budget");
        if (model != expected) {
            invalidate();
            model = expected;
        }
        var captured = epoch;
        var pending = new ObjectArrayList<StructureProjectionState.Sample>();
        var selected = new Reference2ObjectOpenHashMap<StructurePreviewModel.Cell, IntArrayList>();
        BooleanSupplier valid = () -> captured == epoch && model == expected && current.getAsBoolean();
        int start = samples.isEmpty() ? 0 : workCursor % samples.size();
        for (int visit = 0; visit < samples.size(); visit++) {
            int index = (start + visit) % samples.size();
            var sample = samples.get(index);
            int before = visits;
            if (!valid.getAsBoolean()) return List.of();
            var mesh = cells.get(sample.cell());
            if (mesh == null) {
                if (visits == 0) {
                    pending.add(sample);
                    continue;
                }
                visits--;
                var preparation = preparations.apply(expected, sample.cell());
                if (!valid.getAsBoolean()) return List.of();
                mesh = new CellMesh(preparation);
                cells.put(sample.cell(), mesh);
            }
            while (mesh.preparation != null && visits > 0) {
                visits--;
                boolean complete = mesh.preparation.step(mesh.append);
                if (!valid.getAsBoolean()) return List.of();
                if (complete) mesh.preparation = null;
            }
            if (before != visits) workCursor = index + 1 == samples.size() ? 0 : index + 1;
            if (mesh.preparation != null) {
                pending.add(sample);
                continue;
            }
            int quads = mesh.vertices.size() / 28;
            int count = Math.min(visits, quads);
            if (count == 0 && quads != 0) pending.add(sample);
            for (int i = 0; i < count; i++) {
                if (!valid.getAsBoolean()) return List.of();
                int quad = mesh.cursor;
                mesh.cursor = mesh.cursor + 1 == quads ? 0 : mesh.cursor + 1;
                visits--;
                selected.computeIfAbsent(sample.cell(), _ -> new IntArrayList()).add(quad);
                if (!valid.getAsBoolean()) return List.of();
            }
            if (before != visits) workCursor = index + 1 == samples.size() ? 0 : index + 1;
        }
        // Work rotates for fairness, but selected quads retain the caller's far-to-near sample order.
        for (int index2 = 0; index2 < samples.size(); index2++) {
            var sample = samples.get(index2);
            var chosen = selected.get(sample.cell());
            if (chosen == null) continue;
            var mesh = cells.get(sample.cell());
            for (int index1 = 0; index1 < chosen.size(); index1++) {
                int offset = chosen.getInt(index1) * 28;
                if (!valid.getAsBoolean()) return List.of();
                output.put(mesh.vertices.elements(), offset, 28);
                if (!valid.getAsBoolean()) return List.of();
            }
        }
        return pending;
    }

    interface Preparation {
        boolean step(PreviewBlockQuadCursor.QuadSink sink);
    }

    @FunctionalInterface
    interface VertexSink {
        void put(int[] vertices, int offset, int length);
    }

    private static final class CellMesh {
        final IntArrayList vertices = new IntArrayList();
        final PreviewBlockQuadCursor.QuadSink append = (_, data, offset, tint, translation) ->
            PreviewBlockVertices.append(vertices, data, offset, tint, ByteOrder.nativeOrder(), translation);
        Preparation preparation;
        int cursor;

        CellMesh(Preparation preparation) {
            this.preparation = preparation;
        }
    }
}
