package com.circulation.prototype_eng.api.research;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Pure eligibility callback evaluated with a captured real start attempt. Throwing aborts the attempt.
 */
@FunctionalInterface
@SuppressWarnings("unused")
public interface ResearchStartCondition {
    ResearchStartCondition ALWAYS_ALLOWED = _ -> true;

    static ResearchStartCondition fromContext(Predicate<ResearchStartContext> predicate) {
        return predicate::test;
    }

    static ResearchStartCondition fromBooleanSupplier(BooleanSupplier supplier) {
        return _ -> supplier.getAsBoolean();
    }

    boolean canStart(ResearchStartContext context);
}
