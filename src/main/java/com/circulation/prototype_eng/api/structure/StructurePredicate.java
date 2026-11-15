package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only cell or multi-position validator. Declare every possible read before matching; then
 * consume only detached facts. Matching must be pure. Rotation-invariant predicates may keep the
 * default transform; directional predicates and relative-position validators must transform both
 * their declarations and their tests consistently.
 */
public interface StructurePredicate {
    void collectReads(StructureReadPlan.Builder plan, BlockPos position);

    boolean matches(StructureSnapshot snapshot, BlockPos position);

    /**
     * Legacy predicates retain their original two-argument callbacks.
     */
    default void collectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
        collectReads(plan, position);
    }

    default boolean matches(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
        return matches(snapshot, position);
    }

    /**
     * Legacy budget hooks remain authoritative unless a predicate explicitly opts into context.
     */
    default StructureOperation<@Nullable Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
        return beginCollectReads(plan, position);
    }

    default StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
        return beginMatch(snapshot, position);
    }

    /**
     * Stable presentation owned by this declaration. Unknown callbacks return null; no matcher is
     * executed to derive a representation. Directional declarations transform this data as well.
     */
    default @Nullable StructureDisplay display() {
        return null;
    }

    /**
     * Explicit, stable O(1) promise that transforms and read declarations (including query descriptor
     * identities) are immutable and their results can be shared across requests.
     * Concurrent begin/match calls at different positions
     * must own independent cursors and scratch; no mutable attempt state belongs to this instance.
     * Query capture, condition evaluation and freshness checks still run for every attempt.
     */
    default boolean planReusable() {
        return false;
    }

    /**
     * Existing external predicate overrides remain the actual callback used by a budgeted plan.
     */
    default StructureOperation<StructurePredicate> beginTransform(StructureTransform rotation) {
        return StructureOperation.call(() -> transform(rotation));
    }

    default StructureOperation<@Nullable Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position) {
        return StructureOperation.call(() -> {
            collectReads(plan, position);
            return null;
        });
    }

    default StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position) {
        return StructureOperation.call(() -> {
            long unavailable = snapshot.unavailableVisits();
            if (matches(snapshot, position)) return StructurePredicateResult.MATCHED;
            return snapshot.unavailableVisits() == unavailable ? StructurePredicateResult.MISMATCH : StructurePredicateResult.UNAVAILABLE;
        });
    }

    default StructurePredicate transform(StructureTransform rotation) {
        return this;
    }

    /**
     * Explicit opt-in to controller-aware callbacks without inspecting overrides. The old facade
     * uses its position as controller; structure drivers always supply the actual controller.
     * Implementations with finer budgeting override the three-argument begin hooks.
     */
    interface ControllerAware extends StructurePredicate {
        @Override
        void collectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller);

        @Override
        boolean matches(StructureSnapshot snapshot, BlockPos position, BlockPos controller);

        @Override
        default void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
            collectReads(plan, position, position);
        }

        @Override
        default boolean matches(StructureSnapshot snapshot, BlockPos position) {
            return matches(snapshot, position, position);
        }

        @Override
        default StructureOperation<@Nullable Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position) {
            return beginCollectReads(plan, position, position);
        }

        @Override
        default StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position) {
            return beginMatch(snapshot, position, position);
        }

        @Override
        default StructureOperation<@Nullable Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            return StructureOperation.call(() -> {
                collectReads(plan, position, controller);
                return null;
            });
        }

        @Override
        default StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            return StructureOperation.call(() -> {
                long unavailable = snapshot.unavailableVisits();
                if (matches(snapshot, position, controller)) return StructurePredicateResult.MATCHED;
                return snapshot.unavailableVisits() == unavailable ? StructurePredicateResult.MISMATCH : StructurePredicateResult.UNAVAILABLE;
            });
        }
    }
}
