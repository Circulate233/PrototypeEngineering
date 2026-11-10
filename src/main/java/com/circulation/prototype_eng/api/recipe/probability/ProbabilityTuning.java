package com.circulation.prototype_eng.api.recipe.probability;

import java.util.Objects;

/**
 * Sampler policy without a dependency on Forge configuration. Publish one immutable policy for new
 * calls, or pass an explicit policy for reproducible recipes and benchmarks. A sampler captures the
 * policy once, so a concurrent configuration change cannot alter a draw halfway through.
 */
public record ProbabilityTuning(boolean exactSmallBinomial, int exactSmallBinomialThreshold) {
    private static volatile ProbabilityTuning defaultTuning = new ProbabilityTuning(false, 1024);

    public ProbabilityTuning {
        if (exactSmallBinomialThreshold < 0) {
            throw new IllegalArgumentException("Exact binomial threshold must be nonnegative");
        }
    }

    public static ProbabilityTuning defaultTuning() {
        return defaultTuning;
    }

    /**
     * Startup/runtime configuration entry point; explicit per-call policies remain independent.
     */
    @SuppressWarnings("unused")
    public static void setDefault(ProbabilityTuning tuning) {
        defaultTuning = Objects.requireNonNull(tuning, "tuning");
    }
}
