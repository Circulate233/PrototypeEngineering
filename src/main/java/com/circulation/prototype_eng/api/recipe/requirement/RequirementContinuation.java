package com.circulation.prototype_eng.api.recipe.requirement;

import org.jetbrains.annotations.Nullable;

/**
 * Owner-side continuation of one enclosing recipe stage. Each returned acquisition is staged before
 * this cursor advances again; its resources and business undo remain owned by the outer stage.
 * Nested continuations finish before their parent resumes. Returning null closes this cursor.
 * A callback is invoked once, never replayed after a fault. It must return every acquired effect or
 * undo its partial acquisition before throwing, just like an ordinary requirement callback.
 */
@FunctionalInterface
public interface RequirementContinuation {
    @Nullable RequirementTransaction acquireNext();

    /**
     * Framework transparent acquisition pause; this does not stage physical or business effects.
     */
    default boolean preparationPending() {
        return false;
    }

    /**
     * True only when the owner callback charges every internal step to the scoped budget.
     */
    default boolean ownerWorkMetered() {
        return false;
    }

    default boolean captureOnlyPreparation() {
        return false;
    }

    /** Owner-only transfer after the corresponding worker's physical exit. */
    default void retainPureFacts() {
    }

    /** Owner callback at this exact binding; continuations without cached facts need no action. */
    default void withdrawPureFacts(RecipePureComputation expected) {
    }

    /**
     * The already captured pure computation at this exact continuation boundary, if any.
     */
    default @Nullable RecipePureComputation computation() {
        return null;
    }
}
