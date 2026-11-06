package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Immutable successful occurrence. Repeated definitions keep separate tree occurrences and counts;
 * never flatten them by structure ID. The shape provides its own covered positions, so external
 * structures participate in discovery/projection without concrete Template/Slice type checks.
 */
public record StructureMatch(MachineStructure structure, BlockPos origin, int repetitions,
                             List<BlockPos> blocks, List<StructureMatch> children) {
    public StructureMatch {
        Objects.requireNonNull(structure);
        origin = Objects.requireNonNull(origin).toImmutable();
        if (repetitions < 0) { throw new IllegalArgumentException("Negative matched repetition count"); }
        var copied = new ObjectArrayList<BlockPos>(blocks.size());
        for (var block : blocks) { copied.add(Objects.requireNonNull(block).toImmutable()); }
        blocks = List.copyOf(copied);
        children = List.copyOf(children);
    }

    public List<BlockPos> allBlocks() {
        var positions = new ObjectLinkedOpenHashSet<BlockPos>();
        collect(positions);
        return List.copyOf(positions);
    }

    private void collect(ObjectLinkedOpenHashSet<BlockPos> positions) {
        positions.addAll(blocks);
        for (var child : children) { child.collect(positions); }
    }
}
