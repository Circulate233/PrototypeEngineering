package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * External structure-shape responsibility. Definitions are immutable; transformed shapes must
 * rotate offsets, predicates, validators and children together. Matching publishes one complete
 * result or null; it must not mutate the snapshot or retain a stale result from a previous attempt.
 * Matching is deterministic from the immutable definition, anchors and snapshot only. External
 * mutable state or undeclared live reads would invalidate the publication proof.
 */
public interface MachineStructure {
    ResourceLocation id();
    BlockOrientation orientation();
    @SuppressWarnings("unused") // External render/preview consumers use this preserved visual flag.
    boolean hideWorldBlocks();
    MachineStructure transform(StructureTransform rotation);
    void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller);
    @Nullable StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller);
}
