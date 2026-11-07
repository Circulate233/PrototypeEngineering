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
}
