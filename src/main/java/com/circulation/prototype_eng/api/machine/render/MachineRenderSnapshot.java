package com.circulation.prototype_eng.api.machine.render;

import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Complete display facts. Parent/child-edge indexes preserve the accepted occurrence tree.
 * Producers provide a valid tree, distinct ordered positions, and immutable BlockPos values.
 * Lists and their elements are borrowed read-only and must stay unchanged for the snapshot lifetime.
 * Network payload validation belongs to ControllerRenderSnapshotCodec.decode.
 */
public record MachineRenderSnapshot(ControllerDisplayData display, CatalogToken catalog, BlockPos controller,
                                    @Nullable ResourceLocation structure, List<Occurrence> occurrences,
                                    List<BlockPos> positions, List<ResourceLocation> components,
                                    @Nullable MachineAnimationState.Snapshot animation) {
    public static final int MAX_OCCURRENCES = 65536;
    public static final int MAX_POSITIONS = 1048576;
    public static final int MAX_REPETITIONS = 1048576;

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

    /**
     * Consumers still require formed + current + drawable replacement before hiding these facts.
     */
    public List<BlockPos> hidePositions() {
        if (occurrences.isEmpty() || !occurrences.getFirst().hideWorldBlocks()) return List.of();
        var result = new ObjectLinkedOpenHashSet<BlockPos>();
        // PM gates the whole accepted structure on its root flag, including child coverage.
        result.add(controller);
        result.addAll(positions);
        return new ObjectArrayList<>(result);
    }

    public IntList path(int index) {
        var reversed = new IntArrayList();
        for (int current = index; occurrences.get(current).parent() >= 0; current = occurrences.get(current).parent())
            reversed.add(occurrences.get(current).childEdge());
        var result = new IntArrayList(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.getInt(i));
        return result;
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

    }
}
