package com.circulation.prototype_eng.api.recipe.probability;

import java.util.random.RandomGenerator;

/**
 * Percentage-to-execution counts; percentages above 100 can execute more than once per parallel.
 */
public final class ChanceMath {
    private ChanceMath() {
    }

    public static long sampleTimes(RandomGenerator random, int parallels, double chancePercent) {
        return sampleTimes(random, parallels, chancePercent, ProbabilityTuning.defaultTuning());
    }

    /**
     * floor(C/100)*parallels + Binomial(parallels, fractional(C/100)). Counts use long because even
     * valid int parallelism can produce more than int executions. Positive overflow saturates at
     * Long.MAX_VALUE. Nonfinite chance is rejected; finite nonpositive chance or parallels yield zero.
     */
    public static long sampleTimes(RandomGenerator random, int parallels, double chancePercent,
                                   ProbabilityTuning tuning) {
        requireFinite(chancePercent);
        if (parallels <= 0 || chancePercent <= 0) {
            return 0;
        }
        double ratio = chancePercent / 100;
        double whole = Math.floor(ratio);
        // Double represents Long.MAX_VALUE as 2^63; test before converting or deriving a fraction.
        if (whole >= 0x1.0p63) {
            return Long.MAX_VALUE;
        }
        long guaranteed = scaleCount((long) whole, parallels);
        if (guaranteed == Long.MAX_VALUE) {
            return guaranteed;
        }
        double fractional = ratio - whole;
        int extra = fractional > 0 ? BinomialSampler.sample(random, parallels, fractional, tuning) : 0;
        return guaranteed > Long.MAX_VALUE - extra ? Long.MAX_VALUE : guaranteed + extra;
    }

    /**
     * Conservative ceil(C/100)*parallels capacity bound, using the same nonnegative saturation.
     */
    public static long maxTimes(int parallels, double chancePercent) {
        requireFinite(chancePercent);
        if (parallels <= 0 || chancePercent <= 0) {
            return 0;
        }
        double ceiling = Math.ceil(chancePercent / 100);
        if (ceiling >= 0x1.0p63) {
            return Long.MAX_VALUE;
        }
        return scaleCount((long) ceiling, parallels);
    }

    /**
     * Quantities for repeated execution: zero times uses no resources; positive overflow saturates.
     */
    public static long scaleCount(long base, long times) {
        if (base < 0 || times < 0) {
            throw new IllegalArgumentException("Quantity and execution count must be nonnegative");
        }
        if (base == 0 || times == 0) {
            return 0;
        }
        return base > Long.MAX_VALUE / times ? Long.MAX_VALUE : base * times;
    }

    private static void requireFinite(double chancePercent) {
        if (!Double.isFinite(chancePercent)) {
            throw new IllegalArgumentException("Chance percentage must be finite");
        }
    }
}
