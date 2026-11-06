package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** One transformed definition, controller anchor and complete declaration of potential reads. */
public final class StructurePlan {
    private final MachineStructure structure;
    private final BlockPos controller;
    private final StructureReadPlan reads;

    public StructurePlan(MachineStructure definition, BlockPos controller, BlockOrientation orientation) {
        Objects.requireNonNull(definition);
        this.controller = Objects.requireNonNull(controller).toImmutable();
        structure = definition.transform(StructureTransform.between(definition.orientation(), Objects.requireNonNull(orientation)));
        var builder = new StructureReadPlan.Builder();
        structure.collectReads(builder, this.controller, this.controller);
        reads = builder.build();
    }

    public StructureReadPlan reads() { return reads; }

    /** Independent attempts cannot retain a successful tree after a later failed match. */
    @Nullable
    public StructureMatch match(StructureSnapshot snapshot) { return structure.match(Objects.requireNonNull(snapshot), controller, controller); }
}
