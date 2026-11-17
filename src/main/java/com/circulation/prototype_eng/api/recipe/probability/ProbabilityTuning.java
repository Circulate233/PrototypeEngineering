package com.circulation.prototype_eng.api.recipe.probability;


/**
 * Sampler policy without a dependency on Forge configuration. Publish one immutable policy for new
 * calls, or pass an explicit policy for reproducible recipes and benchmarks. All automatic kernels
 * now sample the binomial law. The historical exactSmallBinomial flag requests direct Bernoulli
 * trials below its threshold, capped at 64 so it cannot turn large parallelism into a linear loop.
 * A sampler captures the policy once; configuration changes cannot alter a draw halfway through.
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
        defaultTuning = tuning;
    }
}
