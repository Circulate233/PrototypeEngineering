package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;

import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Objects;

/**
 * PM preview traversal over the published graph; no world, predicate or definition factory access.
 */
public final class StructurePreviewExpansion {
    private final StructureCatalogView view;
    private final StructurePreviewSettings settings;
    private final int root;
    private final StructureTransform rotation;
    private final Iterator<StructureCatalogView.Entry> definitions;
    private final Int2ObjectOpenHashMap<StructureCatalogView.Entry> byNode = new Int2ObjectOpenHashMap<>();
    private final ObjectArrayList<Frame> stack = new ObjectArrayList<>();
    private final StructurePreviewModel.Builder output;
    private final Object2ObjectLinkedOpenHashMap<BlockPos, StructurePreviewModel.Cell> positions = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2LongLinkedOpenHashMap<StructureDisplay> counts = new Object2LongLinkedOpenHashMap<>();
    @Nullable
    private Iterator<StructurePreviewModel.Cell> cellIterator;
    @Nullable
    private Iterator<Object2LongMap.Entry<StructureDisplay>> bomIterator;
    @Nullable
    private StructurePreviewModel model;
    @Nullable
    private BlockPos min;
    @Nullable
    private BlockPos max;
    private boolean started;
    private int cellVisits;
    private int occurrenceCount;

    public StructurePreviewExpansion(StructureCatalogView view, ResourceLocation id, StructurePreviewSettings settings) {
        this.view = Objects.requireNonNull(view);
        Objects.requireNonNull(id);
        this.settings = Objects.requireNonNull(settings);
        output = new StructurePreviewModel.Builder(view, id, settings);
        var entry = view.entries().get(id);
        if (entry == null) throw new IllegalArgumentException("Structure has no published preview: " + id);
        root = entry.node();
        rotation = StructureTransform.between(view.nodes().get(root).orientation(), settings.orientation());
        definitions = view.entries().values().iterator();
    }

    public @Nullable StructurePreviewModel model() {
        return model;
    }

    public int advance(int maxSteps) {
        if (maxSteps < 0) throw new IllegalArgumentException("Negative preview work budget");
        int used = 0;
        while (used < maxSteps && model == null) {
            step();
            used++;
        }
        return used;
    }

    private void step() {
        if (definitions.hasNext()) {
            var entry = definitions.next();
            byNode.put(entry.node(), entry);
            return;
        }
        if (!started) {
            started = true;
            push(root, StructurePreviewPath.ROOT, BlockPos.ORIGIN);
            return;
        }
        if (!stack.isEmpty()) {
            expand(stack.getLast());
            return;
        }
        if (cellIterator == null) {
            cellIterator = positions.values().iterator();
            return;
        }
        if (cellIterator.hasNext()) {
            var cell = cellIterator.next();
            output.addCell(cell);
            counts.addTo(cell.display(), 1L);
            var position = cell.position();
            if (min == null) {
                min = position;
                max = position;
            } else {
                min = new BlockPos(Math.min(min.getX(), position.getX()), Math.min(min.getY(), position.getY()), Math.min(min.getZ(), position.getZ()));
                var upper = Objects.requireNonNull(max);
                max = new BlockPos(Math.max(upper.getX(), position.getX()), Math.max(upper.getY(), position.getY()), Math.max(upper.getZ(), position.getZ()));
            }
            return;
        }
        if (bomIterator == null) {
            bomIterator = counts.object2LongEntrySet().iterator();
            return;
        }
        if (bomIterator.hasNext()) {
            var entry = bomIterator.next();
            output.addBom(new StructurePreviewModel.BomLine(entry.getKey(), entry.getLongValue()));
            return;
        }
        // Model.Builder owns and freezes the result data; this class owns only geometry traversal.
        model = output.freeze(min == null ? StructureBounds.at(BlockPos.ORIGIN) : new StructureBounds(min, Objects.requireNonNull(max)));
    }

    private void push(int node, StructurePreviewPath path, BlockPos anchor) {
        if (occurrenceCount == settings.maxOccurrences())
            throw new IllegalArgumentException("Preview occurrence limit exceeded");
        var shape = view.nodes().get(node);
        var origin = add(anchor, rotation.apply(shape.offset()));
        int count = switch (shape.kind()) {
            case TEMPLATE -> 1;
            case OPAQUE -> 0;
            case SLICE ->
                Math.clamp(settings.sliceCounts().getOrDefault(path, shape.minCount()), shape.minCount(), shape.maxCount());
        };
        var sliceStep = rotation.apply(shape.sliceOffset());
        var childOrigin = shape.kind() == StructurePreviewProvider.Kind.SLICE
            ? add(origin, multiply(sliceStep, (long) count - 1)) : origin;
        output.addOccurrence(new StructurePreviewModel.Occurrence(path, node, origin, count, shape.kind() == StructurePreviewProvider.Kind.OPAQUE));
        occurrenceCount++;
        stack.add(new Frame(node, path, shape, origin, sliceStep, childOrigin, count));
    }

    private void expand(Frame frame) {
        if (frame.shape.cells().isEmpty()) frame.slice = frame.count;
        if (frame.slice < frame.count) {
            var relative = frame.shape.cells().get(frame.cell++);
            if (cellVisits == settings.maxCellVisits())
                throw new IllegalArgumentException("Preview cell visit limit exceeded");
            cellVisits++;
            var position = add(add(frame.origin, multiply(frame.step, frame.slice)), rotation.apply(relative));
            if (!position.equals(BlockPos.ORIGIN)) {
                var definition = byNode.get(frame.node);
                var display = definition == null ? null : definition.displays().get(relative);
                positions.put(position, new StructurePreviewModel.Cell(position, frame.path, frame.node,
                    frame.shape.kind() == StructurePreviewProvider.Kind.SLICE ? frame.slice : -1, display, frame.shape.hideWorldBlocks()));
            }
            if (frame.cell == frame.shape.cells().size()) {
                frame.cell = 0;
                frame.slice++;
            }
            return;
        }
        if (frame.child < frame.shape.children().size()) {
            int edge = frame.child++;
            push(frame.shape.children().get(edge), frame.path.child(edge), frame.childOrigin);
            return;
        }
        stack.removeLast();
    }

    private static BlockPos add(BlockPos left, BlockPos right) {
        return new BlockPos(Math.addExact(left.getX(), right.getX()), Math.addExact(left.getY(), right.getY()), Math.addExact(left.getZ(), right.getZ()));
    }

    private static BlockPos multiply(BlockPos position, long count) {
        return new BlockPos(Math.toIntExact(position.getX() * count), Math.toIntExact(position.getY() * count), Math.toIntExact(position.getZ() * count));
    }

    private static final class Frame {
        final int node;
        final StructurePreviewPath path;
        final StructureCatalogView.Node shape;
        final BlockPos origin;
        final BlockPos step;
        final BlockPos childOrigin;
        final int count;
        int slice;
        int cell;
        int child;

        Frame(int node, StructurePreviewPath path, StructureCatalogView.Node shape, BlockPos origin,
              BlockPos step, BlockPos childOrigin, int count) {
            this.node = node;
            this.path = path;
            this.shape = shape;
            this.origin = origin;
            this.step = step;
            this.childOrigin = childOrigin;
            this.count = count;
        }
    }
}
