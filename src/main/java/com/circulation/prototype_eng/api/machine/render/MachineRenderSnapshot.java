package com.circulation.prototype_eng.api.machine.render;

import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.ints.IntList;

import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete display facts. Parent/child-edge indexes preserve the accepted occurrence tree. */
public record MachineRenderSnapshot(ControllerDisplayData display, CatalogToken catalog, BlockPos controller,
                                    @Nullable ResourceLocation structure, List<Occurrence> occurrences,
                                    List<BlockPos> positions, List<ResourceLocation> components,
                                    @Nullable MachineAnimationState.Snapshot animation) {
    public static final int MAX_OCCURRENCES = 65536;
    public static final int MAX_POSITIONS = 1048576;
    public static final int MAX_REPETITIONS = 1048576;

    public MachineRenderSnapshot {
        Objects.requireNonNull(display);
        Objects.requireNonNull(catalog);
        controller = Objects.requireNonNull(controller).toImmutable();
        if (occurrences.size() > MAX_OCCURRENCES || positions.size() > MAX_POSITIONS || components.size() > MAX_OCCURRENCES)
            throw new IllegalArgumentException("Machine render snapshot exceeds supported scale");
        occurrences = List.copyOf(occurrences);
        positions = copyPositions(positions);
        components = List.copyOf(components);
        if (display.formed() == occurrences.isEmpty() || !occurrences.isEmpty() &&
            (structure == null || !structure.equals(occurrences.getFirst().structure())))
            throw new IllegalArgumentException("Formed display must contain its complete accepted root");
        if (!display.formed() && !positions.isEmpty())
            throw new IllegalArgumentException("Unformed display cannot retain accepted positions");
        var all = new ObjectLinkedOpenHashSet<BlockPos>();
        var childCounts = new int[occurrences.size()];
        long memberships = 0;
        for (int index = 0; index < occurrences.size(); index++) {
            var node = occurrences.get(index);
            if (index == 0 ? node.parent() != -1 || node.childEdge() != -1 :
                node.parent() < 0 || node.parent() >= index || node.childEdge() != childCounts[node.parent()]++)
                throw new IllegalArgumentException("Invalid accepted occurrence parent/child edge");
            memberships += node.blocks().size();
            if (memberships > MAX_POSITIONS) throw new IllegalArgumentException("Too many accepted block memberships");
            all.addAll(node.blocks());
        }
        if (!samePositions(all, positions))
            throw new IllegalArgumentException("Accepted positions must be the ordered distinct occurrence union");
        if (new ObjectLinkedOpenHashSet<>(components).size() != components.size())
            throw new IllegalArgumentException("Duplicate actual component ID");
        if (animation != null && !components.contains(MachineAnimationState.TYPE.id()))
            throw new IllegalArgumentException("Animation state has no actual component binding");
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        if (positions.size() > MAX_POSITIONS) throw new IllegalArgumentException("Too many accepted positions");
        var result = new ObjectArrayList<BlockPos>(positions.size());
        for (var position : positions) result.add(Objects.requireNonNull(position).toImmutable());
        return ObjectLists.unmodifiable(result);
    }

    private static boolean samePositions(ObjectLinkedOpenHashSet<BlockPos> all, List<BlockPos> positions) {
        if (all.size() != positions.size()) return false;
        int index = 0;
        for (var position : all) if (!position.equals(positions.get(index++))) return false;
        return true;
    }

    public static BlockPos sliceAnchor(BlockPos origin, BlockPos step, int index) {
        if (index < 0) throw new IllegalArgumentException("Negative slice index");
        return new BlockPos(Math.toIntExact((long) origin.getX() + (long) index * step.getX()),
            Math.toIntExact((long) origin.getY() + (long) index * step.getY()),
            Math.toIntExact((long) origin.getZ() + (long) index * step.getZ()));
    }

    public StructureBounds bounds() {
        int minX = controller.getX(), minY = controller.getY(), minZ = controller.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        for (var position : positions) {
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY());
            maxZ = Math.max(maxZ, position.getZ());
        }
        return new StructureBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    /** Consumers still require formed + current + drawable replacement before hiding these facts. */
    public List<BlockPos> hidePositions() {
        if (occurrences.isEmpty() || !occurrences.getFirst().hideWorldBlocks()) return List.of();
        var result = new ObjectLinkedOpenHashSet<BlockPos>();
        // PM gates the whole accepted structure on its root flag, including child coverage.
        result.add(controller);
        result.addAll(positions);
        return List.copyOf(result);
    }

    public IntList path(int index) {
        var reversed = new IntArrayList();
        for (int current = index; occurrences.get(current).parent() >= 0; current = occurrences.get(current).parent())
            reversed.add(occurrences.get(current).childEdge());
        var result = new IntArrayList(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.getInt(i));
        return IntLists.unmodifiable(result);
    }

    public record CatalogToken(UUID session, long sequence) {
        public CatalogToken {
            Objects.requireNonNull(session);
            if (sequence < 0) throw new IllegalArgumentException("Negative render catalog sequence");
        }
    }

    public record Occurrence(int parent, int childEdge, ResourceLocation structure, BlockPos origin, int repetitions,
                             StructurePreviewProvider.Kind kind, BlockPos sliceStep, boolean hideWorldBlocks,
                             List<BlockPos> blocks) {
        public Occurrence {
            Objects.requireNonNull(structure);
            Objects.requireNonNull(kind);
            origin = Objects.requireNonNull(origin).toImmutable();
            sliceStep = Objects.requireNonNull(sliceStep).toImmutable();
            blocks = copyPositions(blocks);
            if (repetitions < 0 || repetitions > MAX_REPETITIONS)
                throw new IllegalArgumentException("Accepted repetition count exceeds supported scale");
            if (kind == StructurePreviewProvider.Kind.SLICE && repetitions > 0)
                sliceAnchor(origin, sliceStep, repetitions - 1);
        }
    }
}
