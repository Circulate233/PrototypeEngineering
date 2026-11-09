package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.math.BlockPos;

/**
 * Read-only cell or multi-position validator. Declare every possible read before matching; then
 * consume only detached facts. Matching must be pure. Rotation-invariant predicates may keep the
 * default transform; directional predicates and relative-position validators must transform both
 * their declarations and their tests consistently.
 */
public interface StructurePredicate {
    void collectReads(StructureReadPlan.Builder plan, BlockPos position);
    boolean matches(StructureSnapshot snapshot, BlockPos position);

    /** Existing external predicate overrides remain the actual callback used by a budgeted plan. */
    default StructureOperation<StructurePredicate> beginTransform(StructureTransform rotation) {
        return StructureOperation.call(() -> transform(rotation));
    }

    default StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position) {
        return StructureOperation.call(() -> { collectReads(plan, position); return null; });
    }

    default StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position) {
        return StructureOperation.call(() -> {
            long unavailable = snapshot.unavailableVisits();
            if (matches(snapshot, position)) return StructurePredicateResult.MATCHED;
            return snapshot.unavailableVisits() == unavailable ? StructurePredicateResult.MISMATCH : StructurePredicateResult.UNAVAILABLE;
        });
    }
    default StructurePredicate transform(StructureTransform rotation) { return this; }
}
