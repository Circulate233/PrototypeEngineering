package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Immutable successful occurrence. Repeated definitions keep separate tree occurrences and counts;
 * never flatten them by structure ID. The shape provides its own covered positions, so external
 * structures participate in discovery/projection without concrete Template/Slice type checks.
 * The origin, block positions, and both lists are borrowed immutable values for this occurrence
 * tree's lifetime. Producers finish populating them before construction; consumers never mutate them.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record StructureMatch(MachineStructure structure, BlockPos origin, int repetitions,
                             List<BlockPos> blocks, List<StructureMatch> children) {
    public StructureMatch {
        if (repetitions < 0) {
            throw new IllegalArgumentException("Negative matched repetition count");
        }

    }

    /**
     * Publishes the completed occurrence using the supplied immutable lists without copying.
     */
    public static StructureOperation<StructureMatch> beginCreate(MachineStructure structure, BlockPos origin, int repetitions,
                                                                 List<BlockPos> blocks, List<StructureMatch> children) {
        return StructureOperation.call(() -> new StructureMatch(structure, origin, repetitions, blocks, children));
    }

    public List<BlockPos> allBlocks() {
        return beginAllBlocks().run();
    }

    /**
     * Actual accepted occurrence DFS and deduplication, including repeated definition IDs.
     */
    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains this immutable accepted occurrence tree.
    public StructureOperation<List<BlockPos>> beginAllBlocks() {
        return new StructureOperation<>() {
            private final ObjectArrayList<List<StructureMatch>> pending = new ObjectArrayList<>();
            private final IntArrayList childIndices = new IntArrayList();
            private final ObjectOpenHashSet<BlockPos> unique = new ObjectOpenHashSet<>();
            private final ObjectArrayList<BlockPos> collected = new ObjectArrayList<>();
            private StructureMatch node = StructureMatch.this;
            private List<BlockPos> blocks;
            private int blockIndex;
            private boolean walked;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                while (!walked) {
                    if (node != null) {
                        if (!work.take()) return false;
                        blocks = node.blocks;
                        blockIndex = 0;
                        pending.push(node.children);
                        childIndices.push(0);
                        node = null;
                    }
                    while (blockIndex < blocks.size()) {
                        if (!work.take()) return false;
                        var position = blocks.get(blockIndex++);
                        if (unique.add(position)) collected.add(position);
                    }
                    while (!pending.isEmpty() && childIndices.topInt() == pending.top().size()) {
                        if (!work.take()) return false;
                        pending.pop();
                        childIndices.popInt();
                    }
                    if (pending.isEmpty()) {
                        walked = true;
                    } else {
                        if (!work.take()) return false;
                        int parent = childIndices.size() - 1;
                        int child = childIndices.getInt(parent);
                        node = pending.top().get(child);
                        childIndices.set(parent, child + 1);
                    }
                }
                if (!work.take()) return false;
                finish(collected);
                return true;
            }
        };
    }

}
