package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;

/**
 * Greedy consecutive slices. Validators run once at the base; children run once at the last matched
 * slice, with zero matches placing children one sliceOffset before the base. Child failure does not
 * retry a shorter length. An unavailable attempted slice fails the whole structure.
 */
@SuppressWarnings({"unused", "ClassCanBeRecord"}) // Definitions use identity, not recursive structural equality.
public class SliceStructure implements MachineStructure, StructurePreviewProvider {
    private final ResourceLocation id;
    private final BlockOrientation orientation;
    private final BlockPos offset;
    private final boolean hideWorldBlocks;
    private final StructurePattern pattern;
    private final int minCount;
    private final int maxCount;
    private final BlockPos sliceOffset;
    private final List<StructurePredicate> validators;
    private final List<MachineStructure> children;

    public SliceStructure(ResourceLocation id, BlockOrientation orientation, BlockPos offset,
                          boolean hideWorldBlocks, StructurePattern pattern, int minCount, int maxCount,
                          BlockPos sliceOffset, List<StructurePredicate> validators, List<MachineStructure> children) {
        if (minCount < 0 || maxCount < minCount) {
            throw new IllegalArgumentException("Slice counts must satisfy 0 <= minCount <= maxCount");
        }
        this.id = Objects.requireNonNull(id);
        this.orientation = Objects.requireNonNull(orientation);
        this.offset = Objects.requireNonNull(offset).toImmutable();
        this.hideWorldBlocks = hideWorldBlocks;
        this.pattern = Objects.requireNonNull(pattern);
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.sliceOffset = Objects.requireNonNull(sliceOffset).toImmutable();
        this.validators = List.copyOf(validators);
        this.children = List.copyOf(children);
    }

    @Override public ResourceLocation id() { return id; }
    @Override public BlockOrientation orientation() { return orientation; }
    public BlockPos offset() { return offset; }
    @Override public boolean hideWorldBlocks() { return hideWorldBlocks; }
    public StructurePattern pattern() { return pattern; }
    public int minCount() { return minCount; }
    public int maxCount() { return maxCount; }
    public BlockPos sliceOffset() { return sliceOffset; }
    public List<StructurePredicate> validators() { return validators; }
    public List<MachineStructure> children() { return children;
    }

    @Override public Geometry previewGeometry() {
        return new Geometry(Kind.SLICE, offset, minCount, maxCount, sliceOffset,
            new ObjectArrayList<>(pattern.blocks().keySet()), children); }

    @Override
    public SliceStructure transform(StructureTransform rotation) {
        Objects.requireNonNull(rotation);
        BlockOrientation transformedOrientation = BlockOrientation.get(
                rotation.apply(orientation.getSide(RelativeSide.FRONT)),
                rotation.apply(orientation.getSide(RelativeSide.TOP)));
        var transformedValidators = new ObjectArrayList<StructurePredicate>(validators.size());
        for (StructurePredicate validator : validators) { transformedValidators.add(validator.transform(rotation)); }
        var transformedChildren = new ObjectArrayList<MachineStructure>(children.size());
        for (MachineStructure child : children) { transformedChildren.add(child.transform(rotation)); }
        return new SliceStructure(id, transformedOrientation, rotation.apply(offset), hideWorldBlocks,
                pattern.transform(rotation), minCount, maxCount, rotation.apply(sliceOffset),
                transformedValidators, transformedChildren);
    }

    @Override
    public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(controller);
        BlockPos origin = StructurePattern.add(anchor, offset);
        for (long index = 0; index < maxCount; index++) {
            pattern.collectReads(plan, sliceOrigin(origin, Math.toIntExact(index)), controller);
        }
        for (StructurePredicate validator : validators) { validator.collectReads(plan, origin); }
        if (!children.isEmpty()) {
            for (long count = minCount; count <= maxCount; count++) {
                BlockPos childOrigin = sliceOrigin(origin, Math.toIntExact(count - 1));
                for (MachineStructure child : children) { child.collectReads(plan, childOrigin, controller); }
            }
        }
    }

    @Override
    @Nullable
    public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(controller);
        BlockPos origin = StructurePattern.add(anchor, offset);
        var blocks = new ObjectLinkedOpenHashSet<BlockPos>();
        int count = 0;
        while (count < maxCount) {
            BlockPos current = sliceOrigin(origin, count);
            // Unloaded is a fatal attempted branch, never the greedy pattern-mismatch stop condition.
            if (!snapshot.isAreaLoaded(pattern.at(current))) { return null; }
            if (!pattern.matches(snapshot, current, controller)) { break; }
            for (BlockPos relative : pattern.blocks().keySet()) {
                BlockPos position = StructurePattern.add(current, relative);
                if (!position.equals(controller)) { blocks.add(position); }
            }
            count++;
        }
        if (count < minCount) { return null; }
        for (StructurePredicate validator : validators) {
            if (!validator.matches(snapshot, origin)) { return null; }
        }
        var matchedChildren = new ObjectArrayList<StructureMatch>(children.size());
        if (!children.isEmpty()) {
            BlockPos childOrigin = sliceOrigin(origin, Math.toIntExact((long) count - 1));
            for (MachineStructure child : children) {
                StructureMatch result = child.match(snapshot, childOrigin, controller);
                if (result == null) { return null; }
                matchedChildren.add(result);
            }
        }
        return new StructureMatch(this, origin, count, List.copyOf(blocks), matchedChildren);
    }

    private BlockPos sliceOrigin(BlockPos origin, int index) {
        return StructurePattern.add(origin, StructurePattern.multiply(sliceOffset, index));
    }
}
