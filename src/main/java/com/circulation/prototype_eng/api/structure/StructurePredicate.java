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
    default StructurePredicate transform(StructureTransform rotation) { return this; }
}
