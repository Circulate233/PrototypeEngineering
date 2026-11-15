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
    private final BlockPos.MutableBlockPos shiftedOrigin = new BlockPos.MutableBlockPos();
    private final StructurePreviewModel.Builder output;
    private final Object2ObjectLinkedOpenHashMap<BlockPos, StructurePreviewModel.Cell> positions = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2LongLinkedOpenHashMap<StructureDisplay> counts = new Object2LongLinkedOpenHashMap<>();
    @Nullable
    private Iterator<StructurePreviewModel.Cell> cellIterator;
    @Nullable
    private Iterator<Object2LongMap.Entry<StructureDisplay>> bomIterator;
    @Nullable
    private StructurePreviewModel model;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    private boolean started;
    private int cellVisits;
    private int occurrenceCount;

    public StructurePreviewExpansion(StructureCatalogView view, ResourceLocation id, StructurePreviewSettings settings) {
        this.view = view;
        this.settings = settings;
        output = new StructurePreviewModel.Builder(view, id, settings);
        var entry = view.entries().get(id);
        if (entry == null) throw new IllegalArgumentException("Structure has no published preview: " + id);
        root = entry.node();
        rotation = StructureTransform.between(view.nodes().get(root).orientation(), settings.orientation());
        definitions = view.entries().values().iterator();
    }

    private static BlockPos add(BlockPos left, BlockPos right) {
        return new BlockPos(Math.addExact(left.getX(), right.getX()), Math.addExact(left.getY(), right.getY()), Math.addExact(left.getZ(), right.getZ()));
    }

    private static BlockPos multiply(BlockPos position, long count) {
        return new BlockPos(Math.toIntExact(position.getX() * count), Math.toIntExact(position.getY() * count), Math.toIntExact(position.getZ() * count));
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
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY());
            maxZ = Math.max(maxZ, position.getZ());
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
        model = output.freeze(positions.isEmpty() ? StructureBounds.at(BlockPos.ORIGIN)
            : new StructureBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)));
    }

    private void push(int node, StructurePreviewPath path, BlockPos anchor) {
        if (occurrenceCount == settings.maxOccurrences())
            throw new IllegalArgumentException("Preview occurrence limit exceeded");
        var shape = view.nodes().get(node);
        var origin = rotation.applyAt(anchor, shape.offset());
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
            shiftedOrigin.setPos(
                Math.addExact(frame.origin.getX(), Math.toIntExact((long) frame.step.getX() * frame.slice)),
                Math.addExact(frame.origin.getY(), Math.toIntExact((long) frame.step.getY() * frame.slice)),
                Math.addExact(frame.origin.getZ(), Math.toIntExact((long) frame.step.getZ() * frame.slice)));
            var position = rotation.applyAt(shiftedOrigin, relative);
            if (!position.equals(BlockPos.ORIGIN)) {
                var definition = byNode.get(frame.node);
                var display = definition == null ? frame.shape.displays().get(relative) : definition.displays().getOrDefault(relative, frame.shape.displays().get(relative));
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
            push(frame.shape.children().getInt(edge), frame.path.child(edge), frame.childOrigin);
            return;
        }
        stack.removeLast();
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
