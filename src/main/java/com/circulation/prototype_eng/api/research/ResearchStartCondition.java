package com.circulation.prototype_eng.api.research;

import java.util.Objects;
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
        Objects.requireNonNull(predicate);
        return context -> predicate.test(Objects.requireNonNull(context));
    }

    static ResearchStartCondition fromBooleanSupplier(BooleanSupplier supplier) {
        Objects.requireNonNull(supplier);
        return context -> {
            Objects.requireNonNull(context);
            return supplier.getAsBoolean();
        };
    }

    boolean canStart(ResearchStartContext context);
}
