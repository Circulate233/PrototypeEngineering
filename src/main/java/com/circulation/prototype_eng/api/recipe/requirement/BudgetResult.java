package com.circulation.prototype_eng.api.recipe.requirement;

/**
 * A cooperative check result. YIELDED resumes the same operation; ACCEPTED and REJECTED are final.
 */
public enum BudgetResult {
    YIELDED, ACCEPTED, REJECTED;

    public static BudgetResult of(boolean accepted) {
        return accepted ? ACCEPTED : REJECTED;
    }
}
