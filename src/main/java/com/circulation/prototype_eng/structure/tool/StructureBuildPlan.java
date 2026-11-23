package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

public final class StructureBuildPlan {
    private StructureBuildPlan() {
    }

    public static Result expand(StructureCatalogView view, ResourceLocation id, BlockOrientation orientation, Object2IntMap<String> slices) {
        var expansion = new Expansion(view, id, orientation, slices);
        expansion.complete();
        return new Result(new ObjectArrayList<>(expansion.cells.values()), expansion.nodes);
    }

    public static BlockPos add(BlockPos a, BlockPos b) {
        return new BlockPos(Math.addExact(a.getX(), b.getX()), Math.addExact(a.getY(), b.getY()), Math.addExact(a.getZ(), b.getZ()));
    }

    private static BlockPos stepAt(BlockPos origin, BlockPos step, long amount) {
        return new BlockPos(Math.addExact(origin.getX(), Math.toIntExact(step.getX() * amount)),
            Math.addExact(origin.getY(), Math.toIntExact(step.getY() * amount)),
            Math.addExact(origin.getZ(), Math.toIntExact(step.getZ() * amount)));
    }

    public record Cell(BlockPos position, @Nullable StructureDisplay display, String path) {
    }

    public record Node(String path, int index, BlockPos origin, int count) {
    }

    public record Result(List<Cell> cells, List<Node> nodes) {
    }

    static final class Expansion {
        private final StructureCatalogView view;
        private final Object2IntMap<String> slices;
        final StructureTransform rotation;
        private final Iterator<StructureCatalogView.Entry> entries;
        private final Int2ObjectOpenHashMap<StructureCatalogView.Entry> byNode = new Int2ObjectOpenHashMap<>();
        private final ObjectArrayList<Visit> pending = new ObjectArrayList<>();
        private final Object2ObjectLinkedOpenHashMap<BlockPos, Cell> cells = new Object2ObjectLinkedOpenHashMap<>();
        private final ObjectArrayList<Node> nodes = new ObjectArrayList<>();
        private int visits;
        private boolean complete;

        Expansion(StructureCatalogView view, ResourceLocation id, BlockOrientation orientation, Object2IntMap<String> slices) {
            this.view = view;
            this.slices = slices;
            var root = view.entries().get(id);
            if (root == null) throw new IllegalArgumentException("Unknown construction structure " + id);
            rotation = StructureTransform.between(view.nodes().get(root.node()).orientation(), orientation);
            entries = view.entries().values().iterator();
            pending.add(new Visit(root.node(), "0", BlockPos.ORIGIN));
        }

        void complete() {
            boolean pending;
            do {
                pending = step();
            } while (pending);
        }

        boolean step() {
            if (entries.hasNext()) {
                var entry = entries.next();
                byNode.put(entry.node(), entry);
                return true;
            }
            if (pending.isEmpty()) {
                complete = true;
                return false;
            }
            var visit = pending.getLast();
            var node = view.nodes().get(visit.index);
            if (visit.origin == null) {
                if (nodes.size() >= 65536) throw new IllegalArgumentException("Too many construction structure instances");
                visit.origin = rotation.applyAt(visit.anchor, node.offset());
                visit.count = switch (node.kind()) {
                    case TEMPLATE -> 1;
                    case OPAQUE -> 0;
                    case SLICE -> Math.clamp(slices.getOrDefault(visit.path, node.minCount()), node.minCount(), node.maxCount());
                };
                visit.step = rotation.apply(node.sliceOffset());
                visit.sliceOrigin = visit.origin;
                nodes.add(new Node(visit.path, visit.index, visit.origin, visit.count));
                return true;
            }
            if (visit.slice < visit.count && !node.cells().isEmpty()) {
                var relative = node.cells().get(visit.cell++);
                if (++visits > 1000000) throw new IllegalArgumentException("Too many construction cells");
                var position = rotation.applyAt(visit.sliceOrigin, relative);
                if (!position.equals(BlockPos.ORIGIN)) {
                    var entry = byNode.get(visit.index);
                    var display = node.displays().get(relative);
                    cells.put(position, new Cell(position, entry == null ? display : entry.displays().getOrDefault(relative, display), visit.path));
                }
                if (visit.cell == node.cells().size() && ++visit.slice < visit.count) {
                    visit.cell = 0;
                    visit.sliceOrigin = stepAt(visit.origin, visit.step, visit.slice);
                }
                return true;
            }
            if (visit.child < node.children().size()) {
                int child = visit.child++;
                var childOrigin = node.kind() == StructurePreviewProvider.Kind.SLICE
                    ? stepAt(visit.origin, visit.step, (long) visit.count - 1) : visit.origin;
                pending.add(new Visit(node.children().getInt(child), visit.path + "/" + child, childOrigin));
            } else pending.removeLast();
            return true;
        }

        Iterator<Cell> cells() {
            if (!complete) throw new IllegalStateException("Construction expansion is incomplete");
            return cells.values().iterator();
        }
    }

    private static final class Visit {
        final int index;
        final String path;
        final BlockPos anchor;
        BlockPos origin;
        BlockPos step;
        BlockPos sliceOrigin;
        int count;
        int slice;
        int cell;
        int child;

        Visit(int index, String path, BlockPos anchor) {
            this.index = index;
            this.path = path;
            this.anchor = anchor;
        }
    }
}
