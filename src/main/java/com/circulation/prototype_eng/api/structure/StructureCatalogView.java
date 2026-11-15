package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Complete server-authoritative presentation graph, never a client matching or execution registry.
 * Collections and their contents are borrowed read-only and must remain unchanged for the view's lifetime.
 * Producers must provide an acyclic graph and entries referring to their matching node IDs.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
@SuppressWarnings("ClassCanBeRecord") // This object is the authoritative completed-generation identity.
public final class StructureCatalogView {
    public static final int MAX_NODES = 65536;
    private final UUID session;
    private final long sequence;
    private final List<Node> nodes;
    private final Map<ResourceLocation, Entry> entries;

    public StructureCatalogView(UUID session, long sequence, List<Node> nodes, Map<ResourceLocation, Entry> entries) {
        this.session = session;
        this.sequence = sequence;
        this.nodes = nodes;
        this.entries = entries;
    }

    public static StructureCatalogView capture(StructureCatalog catalog, UUID session, long sequence) {
        var indexes = new Reference2IntOpenHashMap<MachineStructure>();
        indexes.defaultReturnValue(-1);
        var shapes = new ObjectArrayList<MachineStructure>(catalog.definitions().size());
        var entries = new Object2ObjectLinkedOpenHashMap<ResourceLocation, Entry>(catalog.definitions().size());
        for (var definition : catalog.definitions().values()) {
            int index = index(definition.shape(), indexes, shapes);
            entries.put(definition.id(), new Entry(index, definition.translationKey(), definition.displays()));
        }
        var nodes = new ObjectArrayList<Node>(shapes.size());
        for (int cursor = 0; cursor < shapes.size(); cursor++) {
            var shape = shapes.get(cursor);
            var geometry = shape instanceof StructurePreviewProvider provider ? provider.previewGeometry()
                : new StructurePreviewProvider.Geometry(StructurePreviewProvider.Kind.OPAQUE, BlockPos.ORIGIN, 0, 0, BlockPos.ORIGIN, List.of(), List.of());
            var children = new IntArrayList(geometry.children().size());
            var elements = geometry.children();
            for (int index1 = 0; index1 < elements.size(); index1++) {
                var child = elements.get(index1);
                children.add(index(child, indexes, shapes));
            }
            nodes.add(new Node(shape.id(), shape.orientation(), shape.hideWorldBlocks(), geometry.kind(), geometry.offset(),
                geometry.minCount(), geometry.maxCount(), geometry.sliceOffset(), geometry.cells(), children, shape.displays()));
        }
        return new StructureCatalogView(session, sequence, nodes, entries);
    }

    private static int index(MachineStructure shape, Reference2IntOpenHashMap<MachineStructure> indexes, ObjectArrayList<MachineStructure> shapes) {
        int previous = indexes.getInt(shape);
        if (previous >= 0) return previous;
        if (shapes.size() == MAX_NODES) throw new IllegalArgumentException("Too many preview shape nodes");
        int next = shapes.size();
        indexes.put(shape, next);
        shapes.add(shape);
        return next;
    }

    public UUID session() {
        return session;
    }

    public long sequence() {
        return sequence;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public Map<ResourceLocation, Entry> entries() {
        return entries;
    }

    /**
     * All collections are borrowed read-only; positions must be immutable.
     */
    public record Node(ResourceLocation id, BlockOrientation orientation, boolean hideWorldBlocks,
                       StructurePreviewProvider.Kind kind, BlockPos offset, int minCount, int maxCount,
                       BlockPos sliceOffset, List<BlockPos> cells, IntList children,
                       BlockPos2ObjectMap<StructureDisplay> displays) {
        public Node(ResourceLocation id, BlockOrientation orientation, boolean hideWorldBlocks,
                    StructurePreviewProvider.Kind kind, BlockPos offset, int minCount, int maxCount,
                    BlockPos sliceOffset, List<BlockPos> cells, IntList children) {
            this(id, orientation, hideWorldBlocks, kind, offset, minCount, maxCount, sliceOffset, cells, children, BlockPos2ObjectMap.emptyMap());
        }

        public Node {
            offset = offset.toImmutable();
            sliceOffset = sliceOffset.toImmutable();
            if (minCount < 0 || maxCount < minCount) throw new IllegalArgumentException("Invalid preview count domain");
        }
    }

    /**
     * Display entries are borrowed read-only for the catalog lifetime.
     */
    public record Entry(int node, @Nullable String translationKey, BlockPos2ObjectMap<StructureDisplay> displays) {
        public Entry {
            if (translationKey != null && translationKey.isBlank())
                throw new IllegalArgumentException("Empty preview translation key");
        }
    }
}
