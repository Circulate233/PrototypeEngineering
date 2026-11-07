package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Complete server-authoritative presentation graph, never a client matching or execution registry. */
@SuppressWarnings("ClassCanBeRecord") // This object is the authoritative completed-generation identity.
public final class StructureCatalogView {
    public static final int MAX_NODES = 65536;
    private final UUID session;
    private final long sequence;
    private final List<Node> nodes;
    private final Map<ResourceLocation, Entry> entries;

    public StructureCatalogView(UUID session, long sequence, List<Node> nodes, Map<ResourceLocation, Entry> entries) {
        this.session = Objects.requireNonNull(session);
        if (sequence < 0 || nodes.size() > MAX_NODES || entries.size() > MAX_NODES) throw new IllegalArgumentException("Invalid catalog view bounds");
        this.sequence = sequence;
        this.nodes = List.copyOf(nodes);
        var copy = new Object2ObjectLinkedOpenHashMap<ResourceLocation, Entry>();
        entries.forEach((id, entry) -> {
            Objects.requireNonNull(id); Objects.requireNonNull(entry);
            if (entry.node < 0 || entry.node >= nodes.size() || !id.equals(nodes.get(entry.node).id))
                throw new IllegalArgumentException("Catalog view entry has no matching node: " + id);
            copy.put(id, entry);
        });
        this.entries = Object2ObjectMaps.unmodifiable(copy);
        validateGraph();
    }

    public static StructureCatalogView capture(StructureCatalog catalog, UUID session, long sequence) {
        var indexes = new Reference2IntOpenHashMap<MachineStructure>(); indexes.defaultReturnValue(-1);
        var shapes = new ObjectArrayList<MachineStructure>();
        var entries = new Object2ObjectLinkedOpenHashMap<ResourceLocation, Entry>();
        for (var definition : catalog.definitions().values()) {
            int index = index(definition.shape(), indexes, shapes);
            entries.put(definition.id(), new Entry(index, definition.translationKey(), definition.displays()));
        }
        var nodes = new ObjectArrayList<Node>();
        for (int cursor = 0; cursor < shapes.size(); cursor++) {
            var shape = shapes.get(cursor);
            var geometry = shape instanceof StructurePreviewProvider provider ? Objects.requireNonNull(provider.previewGeometry())
                : new StructurePreviewProvider.Geometry(StructurePreviewProvider.Kind.OPAQUE, BlockPos.ORIGIN, 0, 0, BlockPos.ORIGIN, List.of(), List.of());
            var children = new ObjectArrayList<Integer>();
            for (var child : geometry.children()) children.add(index(child, indexes, shapes));
            nodes.add(new Node(shape.id(), shape.orientation(), shape.hideWorldBlocks(), geometry.kind(), geometry.offset(),
                geometry.minCount(), geometry.maxCount(), geometry.sliceOffset(), geometry.cells(), children));
        }
        return new StructureCatalogView(session, sequence, nodes, entries);
    }

    private static int index(MachineStructure shape, Reference2IntOpenHashMap<MachineStructure> indexes, ObjectArrayList<MachineStructure> shapes) {
        Objects.requireNonNull(shape);
        int previous = indexes.getInt(shape);
        if (previous >= 0) return previous;
        if (shapes.size() == MAX_NODES) throw new IllegalArgumentException("Too many preview shape nodes");
        int next = shapes.size(); indexes.put(shape, next); shapes.add(shape); return next;
    }

    private void validateGraph() {
        var colors = new byte[nodes.size()];
        var stack = new int[nodes.size()];
        var next = new int[nodes.size()];
        for (int root = 0; root < nodes.size(); root++) {
            if (colors[root] != 0) continue;
            int depth = 0; stack[0] = root; colors[root] = 1;
            while (depth >= 0) {
                int node = stack[depth];
                if (next[node] == nodes.get(node).children.size()) { colors[node] = 2; depth--; continue; }
                int child = nodes.get(node).children.get(next[node]++);
                if (child < 0 || child >= nodes.size()) throw new IllegalArgumentException("Unknown preview child node");
                if (colors[child] == 1) throw new IllegalArgumentException("Cyclic preview graph");
                if (colors[child] == 0) { colors[child] = 1; stack[++depth] = child; }
            }
        }
    }

    public UUID session() { return session; }
    public long sequence() { return sequence; }
    public List<Node> nodes() { return nodes; }
    public Map<ResourceLocation, Entry> entries() { return entries; }

    public record Node(ResourceLocation id, BlockOrientation orientation, boolean hideWorldBlocks,
                       StructurePreviewProvider.Kind kind, BlockPos offset, int minCount, int maxCount,
                       BlockPos sliceOffset, List<BlockPos> cells, List<Integer> children) {
        public Node {
            Objects.requireNonNull(id); Objects.requireNonNull(orientation); Objects.requireNonNull(kind);
            offset = Objects.requireNonNull(offset).toImmutable(); sliceOffset = Objects.requireNonNull(sliceOffset).toImmutable();
            if (minCount < 0 || maxCount < minCount) throw new IllegalArgumentException("Invalid preview count domain");
            cells = cells.stream().map(position -> Objects.requireNonNull(position).toImmutable()).toList();
            children = List.copyOf(children);
        }
    }

    public record Entry(int node, @Nullable String translationKey, Map<BlockPos, StructureDisplay> displays) {
        public Entry {
            if (translationKey != null && translationKey.isBlank()) throw new IllegalArgumentException("Empty preview translation key");
            var copy = new Object2ObjectLinkedOpenHashMap<BlockPos, StructureDisplay>();
            displays.forEach((position, display) -> copy.put(Objects.requireNonNull(position).toImmutable(), Objects.requireNonNull(display)));
            displays = Object2ObjectMaps.unmodifiable(copy);
        }
    }
}
