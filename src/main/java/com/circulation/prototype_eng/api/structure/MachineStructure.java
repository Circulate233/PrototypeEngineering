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

    @SuppressWarnings("unused")
        // External render/preview consumers use this preserved visual flag.
    boolean hideWorldBlocks();

    MachineStructure transform(StructureTransform rotation);

    void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller);

    @Nullable StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller);

    /**
     * Explicit opt-in to sharing completed transformed definitions and read declarations. Geometry,
     * transforms and declarations must remain stable for this object's lifetime, including every
     * transformed result. This flag is stable and O(1). Independent requests may concurrently use
     * the same instance at different anchors: each begin call must own its cursor, builder and
     * scratch. Matching still consumes each request's fresh snapshot. Unknown extensions stay on
     * their original virtual path; mutable definitions must not opt in.
     */
    default boolean planReusable() {
        return false;
    }

    /** Unknown shapes retain their exact existing virtual operations as cooperative callbacks. */
    default StructureOperation<MachineStructure> beginTransform(StructureTransform rotation) {
        return StructureOperation.call(() -> transform(rotation));
    }

    default StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        return StructureOperation.call(() -> {
            collectReads(plan, anchor, controller);
            return null;
        });
    }

    default StructureOperation<StructureMatchResult> beginMatch(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
        return StructureOperation.call(() -> {
            long unavailable = snapshot.unavailableVisits();
            var result = match(snapshot, anchor, controller);
            if (result != null) return StructureMatchResult.matched(result);
            return snapshot.unavailableVisits() == unavailable ? StructureMatchResult.mismatch() : StructureMatchResult.unavailable();
        });
    }

}
