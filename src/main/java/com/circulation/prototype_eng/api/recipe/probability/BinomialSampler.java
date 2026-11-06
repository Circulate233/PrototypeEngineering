package com.circulation.prototype_eng.api.recipe.probability;

import java.util.Objects;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * PM's gameplay sampler: optional exact Bernoulli trials for small n, Poisson approximation below
 * mean 16, otherwise a fixed Gaussian table. The default policy favors bounded expected cost over an
 * exact binomial distribution. Every result lies in [0,n]; the table and a seeded generator make
 * repeated calls deterministic when the same policy is used.
 */
public final class BinomialSampler {
    private static final int GAUSSIAN_TABLE_SIZE = 2048;
    private static final int GAUSSIAN_TABLE_MASK = GAUSSIAN_TABLE_SIZE - 1;
    private static final double[] GAUSSIAN_TABLE = new double[GAUSSIAN_TABLE_SIZE];

    static {
        var source = new Random(0x4A4D4831L);
        for (int index = 0; index < GAUSSIAN_TABLE.length; index++) {
            GAUSSIAN_TABLE[index] = source.nextGaussian();
        }
    }

    private BinomialSampler() {
    }

    /** Convenience entry point for downstream callers using the current default policy. */
    @SuppressWarnings("unused")
    public static int sample(RandomGenerator random, int n, double probability) {
        return sample(random, n, probability, ProbabilityTuning.defaultTuning());
    }

    /** Nonfinite probability is rejected; finite nonpositive n/p yield zero and p>=1 yields n. */
    public static int sample(RandomGenerator random, int n, double probability, ProbabilityTuning tuning) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(tuning, "tuning");
        if (!Double.isFinite(probability)) {
            throw new IllegalArgumentException("Probability must be finite");
        }
        if (n <= 0 || probability <= 0) {
            return 0;
        }
        if (probability >= 1) {
            return n;
        }
        if (probability > 0.5) {
            return n - sample(random, n, 1 - probability, tuning);
        }
        if (tuning.exactSmallBinomial() && n <= tuning.exactSmallBinomialThreshold()) {
            int successes = 0;
            for (int index = 0; index < n; index++) {
                if (random.nextDouble() < probability) {
                    successes++;
                }
            }
            return successes;
        }
        double mean = n * probability;
        if (mean < 16) {
            return poisson(random, mean, n);
        }
        double deviation = Math.sqrt(mean * (1 - probability));
        double gaussian = GAUSSIAN_TABLE[random.nextInt() & GAUSSIAN_TABLE_MASK];
        int sampled = (int) Math.floor(mean + deviation * gaussian + 0.5);
        return Math.clamp(sampled, 0, n);
    }

    /** Stop once the clamped result is known, preventing counter overflow even with extreme draws. */
    private static int poisson(RandomGenerator random, double mean, int maximum) {
        double limit = Math.exp(-mean);
        double product = 1;
        int successes = 0;
        while (true) {
            product *= random.nextDouble();
            if (product <= limit) {
                return successes;
            }
            if (successes == maximum) {
                return maximum;
            }
            successes++;
        }
    }
}
