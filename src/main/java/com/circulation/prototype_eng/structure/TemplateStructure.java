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
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Immutable fixed pattern, followed by base validators and children anchored at its offset origin. */
@SuppressWarnings({"unused", "ClassCanBeRecord"}) // Definitions use identity, not recursive structural equality.
public final class TemplateStructure implements MachineStructure {
    private final ResourceLocation id;
    private final BlockOrientation orientation;
    private final BlockPos offset;
    private final boolean hideWorldBlocks;
    private final StructurePattern pattern;
    private final List<StructurePredicate> validators;
    private final List<MachineStructure> children;

    public TemplateStructure(ResourceLocation id, BlockOrientation orientation, BlockPos offset,
                             boolean hideWorldBlocks, StructurePattern pattern,
                             List<StructurePredicate> validators, List<MachineStructure> children) {
        this.id = Objects.requireNonNull(id);
        this.orientation = Objects.requireNonNull(orientation);
        this.offset = Objects.requireNonNull(offset).toImmutable();
        this.hideWorldBlocks = hideWorldBlocks;
        this.pattern = Objects.requireNonNull(pattern);
        this.validators = List.copyOf(validators);
        this.children = List.copyOf(children);
    }

    @Override public ResourceLocation id() { return id; }
    @Override public BlockOrientation orientation() { return orientation; }
    public BlockPos offset() { return offset; }
    @Override public boolean hideWorldBlocks() { return hideWorldBlocks; }
    public StructurePattern pattern() { return pattern; }
    public List<StructurePredicate> validators() { return validators; }
    public List<MachineStructure> children() { return children; }

    @Override
    public TemplateStructure transform(StructureTransform rotation) {
        Objects.requireNonNull(rotation);
        BlockOrientation transformedOrientation = BlockOrientation.get(
                rotation.apply(orientation.getSide(RelativeSide.FRONT)),
                rotation.apply(orientation.getSide(RelativeSide.TOP)));
        var transformedValidators = new ObjectArrayList<StructurePredicate>(validators.size());
        for (StructurePredicate validator : validators) { transformedValidators.add(validator.transform(rotation)); }
        var transformedChildren = new ObjectArrayList<MachineStructure>(children.size());
        for (MachineStructure child : children) { transformedChildren.add(child.transform(rotation)); }
        return new TemplateStructure(id, transformedOrientation, rotation.apply(offset), hideWorldBlocks,
                pattern.transform(rotation), transformedValidators, transformedChildren);
    }

    @Override
    public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(controller);
        BlockPos origin = StructurePattern.add(anchor, offset);
        pattern.collectReads(plan, origin, controller);
        for (StructurePredicate validator : validators) { validator.collectReads(plan, origin); }
        for (MachineStructure child : children) { child.collectReads(plan, origin, controller); }
    }

    @Override
    @Nullable
    public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(controller);
        BlockPos origin = StructurePattern.add(anchor, offset);
        if (!pattern.matches(snapshot, origin, controller)) { return null; }
        for (StructurePredicate validator : validators) {
            if (!validator.matches(snapshot, origin)) { return null; }
        }
        var matchedChildren = new ObjectArrayList<StructureMatch>(children.size());
        for (MachineStructure child : children) {
            StructureMatch result = child.match(snapshot, origin, controller);
            if (result == null) { return null; }
            matchedChildren.add(result);
        }
        var blocks = new ObjectArrayList<BlockPos>(pattern.blocks().size());
        for (BlockPos relative : pattern.blocks().keySet()) {
            BlockPos position = StructurePattern.add(origin, relative);
            if (!position.equals(controller)) { blocks.add(position); }
        }
        return new StructureMatch(this, origin, 1, blocks, matchedChildren);
    }
}
