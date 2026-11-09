package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Iterator;

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
        if (!(blocks instanceof Frozen<?>)) {
            var copied = new ObjectArrayList<BlockPos>(blocks.size());
        for (var block : blocks) copied.add(Objects.requireNonNull(block).toImmutable());
            blocks = new Frozen<>(copied);
        }
        if (!(children instanceof Frozen<?>)) children = List.copyOf(children);
    }

    /** Copies the complete occurrence incrementally, publishing no partially copied tree. */
    public static StructureOperation<StructureMatch> beginCreate(MachineStructure structure, BlockPos origin, int repetitions,
                                                                 Iterable<BlockPos> blocks, List<StructureMatch> children) {
        Objects.requireNonNull(structure); Objects.requireNonNull(origin);
        var blockIterator = blocks.iterator();
        var childIterator = children.iterator();
        return new StructureOperation<>() {
            private final ObjectArrayList<BlockPos> frozenBlocks = new ObjectArrayList<>();
            private final ObjectArrayList<StructureMatch> frozenChildren = new ObjectArrayList<>();

            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                while (blockIterator.hasNext()) {
                    if (!work.take()) return false;
                    frozenBlocks.add(Objects.requireNonNull(blockIterator.next()).toImmutable());
                }
                while (childIterator.hasNext()) {
                    if (!work.take()) return false;
                    frozenChildren.add(Objects.requireNonNull(childIterator.next()));
                }
                if (!work.take()) return false;
                finish(new StructureMatch(structure, origin, repetitions, new Frozen<>(frozenBlocks), new Frozen<>(frozenChildren)));
                return true;
            }
        };
    }

    public List<BlockPos> allBlocks() {
        return Objects.requireNonNull(beginAllBlocks().run());
    }

    /** Actual accepted occurrence DFS and deduplication, including repeated definition IDs. */
    @SuppressWarnings("ReturnOfInnerClass") // The cursor retains this immutable accepted occurrence tree.
    public StructureOperation<List<BlockPos>> beginAllBlocks() {
        return new StructureOperation<>() {
            private final ArrayDeque<Iterator<StructureMatch>> pending = new ArrayDeque<>();
            private final ObjectLinkedOpenHashSet<BlockPos> unique = new ObjectLinkedOpenHashSet<>();
            private final ObjectArrayList<BlockPos> frozen = new ObjectArrayList<>();
            private StructureMatch node = StructureMatch.this;
            private Iterator<BlockPos> blocks;
            private Iterator<BlockPos> copy;
            private boolean walked;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                while (!walked) {
                    if (node != null) {
                        if (!work.take()) return false;
                        blocks = node.blocks.iterator();
                        pending.push(node.children.iterator());
                        node = null;
                    }
                    while (blocks.hasNext()) {
                        if (!work.take()) return false;
                        unique.add(blocks.next());
                    }
                    while (!pending.isEmpty() && !pending.peek().hasNext()) {
                        if (!work.take()) return false;
                        pending.pop();
                    }
                    if (pending.isEmpty()) {
                        walked = true;
                        copy = unique.iterator();
                    } else {
                        if (!work.take()) return false;
                        node = Objects.requireNonNull(pending.peek()).next();
                    }
                }
                while (copy.hasNext()) {
                    if (!work.take()) return false;
                    frozen.add(copy.next());
                }
                if (!work.take()) return false;
                finish(new Frozen<>(frozen));
                return true;
            }
        };
    }

    /** Only the private completed computations can transfer an owned immutable list without copying. */
    private static final class Frozen<T> extends AbstractList<T> {
        private final List<T> owned;
        private Frozen(List<T> owned) { this.owned = owned; }
        @Override public T get(int index) { return owned.get(index); }
        @Override public int size() { return owned.size(); }
    }
}
