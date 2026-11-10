package com.circulation.prototype_eng.api.recipe.requirement;

import org.jetbrains.annotations.Nullable;

/**
 * Owner-side binding for the current effective requirement. Only computation() crosses to workers;
 * this binding may retain the original process and sources. acquire() revalidates that binding and
 * returns the original requirement transaction, including every reservation and reversible effect.
 * Owner steps may return transparent pauses; each effect is acquired once under current authorization.
 */
public interface RequirementPreparation {
    @Nullable RecipePureComputation computation();

    RequirementTransaction acquire();

    /**
     * True only when the owner callback charges every internal step to the scoped budget.
     */
    default boolean ownerWorkMetered() {
        return false;
    }

    /**
     * This next owner step only captures facts; it never acquires business or resource effects.
     */
    default boolean captureOnlyPreparation() {
        return false;
    }

    /**
     * Owner callback after a successful worker slice has physically exited; default retains no facts.
     */
    default void retainPureFacts() {
    }

    /**
     * Withdraws only this binding's cached identity before its mutable facts leave the owner.
     */
    default void withdrawPureFacts(RecipePureComputation expected) {
    }

}
