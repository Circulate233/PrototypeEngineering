package com.circulation.prototype_eng.structure.tool;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** Common-side expansion for construction; unknown display requirements remain unbuildable. */
public final class StructureBuildPlan {
    public record Cell(BlockPos position, @Nullable StructureDisplay display, String path) {
    }

    public record Node(String path, int index, BlockPos origin, int count) {
    }

    public record Result(List<Cell> cells, List<Node> nodes) {
    }

    private record Visit(int node, String path, BlockPos anchor) {
    }

    private StructureBuildPlan() {
    }

    public static Result expand(StructureCatalogView view, ResourceLocation id, BlockOrientation orientation, Map<String, Integer> slices) {
        var root = view.entries().get(id);
        if (root == null) throw new IllegalArgumentException("Unknown construction structure");
        var rotation = StructureTransform.between(view.nodes().get(root.node()).orientation(), orientation);
        var byNode = new Int2ObjectOpenHashMap<StructureCatalogView.Entry>();
        view.entries().values().forEach(entry -> byNode.put(entry.node(), entry));
        var pending = new ObjectArrayList<Visit>();
        pending.add(new Visit(root.node(), "0", BlockPos.ORIGIN));
        var cells = new Object2ObjectLinkedOpenHashMap<BlockPos, Cell>();
        var nodes = new ObjectArrayList<Node>();
        int visits = 0;
        while (!pending.isEmpty()) {
            var visit = pending.removeLast();
            var node = view.nodes().get(visit.node());
            if (nodes.size() >= 65536) throw new IllegalArgumentException("Too many construction structure instances");
            var origin = add(visit.anchor(), rotation.apply(node.offset()));
            int count = switch (node.kind()) {
                case TEMPLATE -> 1;
                case OPAQUE -> 0;
                case SLICE ->
                    Math.clamp(slices.getOrDefault(visit.path(), node.minCount()), node.minCount(), node.maxCount());
            };
            nodes.add(new Node(visit.path(), visit.node(), origin, count));
            var entry = byNode.get(visit.node());
            var step = rotation.apply(node.sliceOffset());
            for (int slice = 0; slice < count && !node.cells().isEmpty(); slice++) {
                var anchor = add(origin, multiply(step, slice));
                for (var relative : node.cells()) {
                    if (++visits > 1000000) throw new IllegalArgumentException("Too many construction cells");
                    var position = add(anchor, rotation.apply(relative));
                    if (!position.equals(BlockPos.ORIGIN))
                        cells.put(position, new Cell(position, entry == null ? null : entry.displays().get(relative), visit.path()));
                }
            }
            var childOrigin = node.kind() == StructurePreviewProvider.Kind.SLICE ? add(origin, multiply(step, (long) count - 1)) : origin;
            for (int child = node.children().size() - 1; child >= 0; child--)
                pending.add(new Visit(node.children().get(child), visit.path() + "/" + child, childOrigin));
        }
        return new Result(List.copyOf(cells.values()), List.copyOf(nodes));
    }

    public static BlockPos add(BlockPos a, BlockPos b) {
        return new BlockPos(Math.addExact(a.getX(), b.getX()), Math.addExact(a.getY(), b.getY()), Math.addExact(a.getZ(), b.getZ()));
    }

    private static BlockPos multiply(BlockPos position, long amount) {
        return new BlockPos(Math.toIntExact(position.getX() * amount), Math.toIntExact(position.getY() * amount), Math.toIntExact(position.getZ() * amount));
    }
}
