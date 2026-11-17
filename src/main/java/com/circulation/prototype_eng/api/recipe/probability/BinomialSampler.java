/*
 * BTRD and inversion adapted from Boost.Random binomial_distribution.hpp.
 * Copyright Steven Watanabe 2010.
 * Distributed under the Boost Software License, Version 1.0;
 * see META-INF/licenses/Boost-Random-LICENSE in the source tree and jar.
 */
package com.circulation.prototype_eng.api.recipe.probability;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;

import java.util.random.RandomGenerator;

/**
 * One aggregate binomial draw, without a Gaussian/Poisson approximation. Dispatch depends on both
 * trial count and probability: one Bernoulli, tiny direct batches, fair random bits, small-mean
 * inversion, or Hormann's BTRD. Rejection has bounded expected cost even at int-sized parallelism.
 */
public final class BinomialSampler {
    private static final int SINGLE = 0;
    private static final int DIRECT = 1;
    private static final int FAIR_BITS = 2;
    private static final int INVERSION = 3;
    private static final int BTRD = 4;
    private static final int DIRECT_LIMIT = 4;
    private static final int DENSE_DIRECT_LIMIT = 6;
    private static final int TUNED_DIRECT_LIMIT = 64;
    private static final int FAIR_BITS_LIMIT = 384;
    private static final double INVERSION_MEAN_LIMIT = 10;
    private static final int REJECTED = -1;
    private static final int CHECK_RATIO = -2;

    private static final double[] STIRLING_CORRECTIONS = {
        0.08106146679532726, 0.04134069595540929, 0.02767792568499834,
        0.02079067210376509, 0.01664469118982119, 0.01387612882307075,
        0.01189670994589177, 0.01041126526197209, 0.009255462182712733,
        0.008330563433362871
    };

    private BinomialSampler() {
    }

    @SuppressWarnings("unused")
    public static int sample(RandomGenerator random, int n, double probability) {
        return sample(random, n, probability, ProbabilityTuning.defaultTuning());
    }

    /**
     * Nonfinite probability is rejected; finite nonpositive n/p yield zero and p>=1 yields n.
     * One parallel uses exactly one nextDouble before consulting any policy or batch machinery.
     */
    public static int sample(RandomGenerator random, int n, double probability, ProbabilityTuning tuning) {
        requireFinite(probability);
        if (n <= 0 || probability <= 0) return 0;
        if (probability >= 1) return n;
        if (n == 1) return random.nextDouble() < probability ? 1 : 0;

        int strategy = strategy(n, probability, tuning);
        if (strategy == DIRECT) {
            int successes = 0;
            for (int trial = 0; trial < n; trial++) {
                successes += random.nextDouble() < probability ? 1 : 0;
            }
            return successes;
        }
        if (strategy == FAIR_BITS) return fairBits(random, n);

        boolean complement = probability > 0.5;
        double p = complement ? 1 - probability : probability;
        int sampled = strategy == INVERSION ? new Inversion(n, p).sample(random) : new Btrd(n, p).sample(random);
        return complement ? n - sampled : sampled;
    }

    private static void requireFinite(double probability) {
        if (!Double.isFinite(probability)) throw new IllegalArgumentException("Probability must be finite");
    }

    /**
     * Select once, outside the rejection/recurrence loops. An explicit direct policy stays bounded.
     */
    private static int strategy(int n, double probability, ProbabilityTuning tuning) {
        if (tuning.exactSmallBinomial() && n <= Math.min(tuning.exactSmallBinomialThreshold(), TUNED_DIRECT_LIMIT))
            return DIRECT;
        if (probability == 0.5 && n <= FAIR_BITS_LIMIT) return FAIR_BITS;
        if (n <= DIRECT_LIMIT) return DIRECT;
        if (n <= DENSE_DIRECT_LIMIT && probability >= 0.125 && probability <= 0.875) return DIRECT;
        return n * Math.min(probability, 1 - probability) < INVERSION_MEAN_LIMIT ? INVERSION : BTRD;
    }

    private static int fairBits(RandomGenerator random, int n) {
        int successes = 0;
        while (n > Long.SIZE) {
            successes += Long.bitCount(random.nextLong());
            n -= Long.SIZE;
        }
        return successes + (n <= Integer.SIZE
            ? Integer.bitCount(random.nextInt() >>> (Integer.SIZE - n))
            : Long.bitCount(random.nextLong() >>> (Long.SIZE - n)));
    }

    /**
     * Stable log1p keeps tiny p meaningful even when 1-p rounds to one. The descending tail stops
     * below double precision, as in Boost's inversion, rather than looping after CDF roundoff.
     */
    private static class Inversion {
        private final int n;
        private final double odds;
        private final double scaledOdds;
        private double mass;
        private double uniform;
        private int count;
        private boolean started;

        private Inversion(int n, double p) {
            this.n = n;
            odds = p / (1 - p);
            scaledOdds = (n + 1.0) * odds;
            mass = Math.exp(n * Math.log1p(-p));
        }

        private int sample(RandomGenerator random) {
            uniform = random.nextDouble();
            while (uniform > mass && count < n) {
                if (step()) break;
            }
            return count;
        }

        private boolean step() {
            uniform -= mass;
            count++;
            double nextMass = (scaledOdds / count - odds) * mass;
            boolean tail = nextMass < Math.ulp(1.0) && nextMass < mass;
            mass = nextMass;
            return tail;
        }

        private boolean advance(RandomGenerator random, RecipeWorkBudget budget) {
            if (!started) {
                if (!budget.take()) return false;
                uniform = random.nextDouble();
                started = true;
            }
            while (uniform > mass && count < n) {
                if (!budget.take()) return false;
                if (step()) return true;
            }
            return true;
        }
    }

    /**
     * BTRD's central rectangle accepts with one uniform; only the remaining area checks a ratio.
     */
    private static class Btrd {
        private final int n;
        private final int mode;
        private final double odds;
        private final double scaledOdds;
        private final double variance;
        private final double a;
        private final double b;
        private final double center;
        private final double alpha;
        private final double rectangle;
        private final double fastRectangle;
        private int candidate;
        private int ratioIndex;
        private int ratioSteps;
        private double ratioProduct;
        private double ratioUniform;
        private int result;

        private Btrd(int n, double p) {
            this.n = n;
            mode = (int) ((n + 1.0) * p);
            odds = p / (1 - p);
            scaledOdds = (n + 1.0) * odds;
            variance = n * p * (1 - p);
            double deviation = Math.sqrt(variance);
            b = 1.15 + 2.53 * deviation;
            a = -0.0873 + 0.0248 * b + 0.01 * p;
            center = n * p + 0.5;
            alpha = (2.83 + 5.1 / b) * deviation;
            rectangle = 0.92 - 4.2 / b;
            fastRectangle = 0.86 * rectangle;
        }

        private int sample(RandomGenerator random) {
            while (true) {
                int selected = propose(random);
                if (selected >= 0) return selected;
                if (selected == CHECK_RATIO) {
                    while (ratioSteps > 0) ratioStep();
                    if (ratioAccepted()) return candidate;
                }
            }
        }

        private int propose(RandomGenerator random) {
            double v = random.nextDouble();
            if (v <= fastRectangle) {
                // For np>=10 this rectangle is entirely inside the binomial support.
                double u = v / rectangle - 0.43;
                return (int) Math.floor((2 * a / (0.5 - Math.abs(u)) + b) * u + center);
            }

            double u;
            if (v >= rectangle) {
                u = random.nextDouble() - 0.5;
            } else {
                u = v / rectangle - 0.93;
                u = Math.copySign(0.5, u) - u;
                v = random.nextDouble() * rectangle;
            }
            double width = 0.5 - Math.abs(u);
            double proposed = Math.floor((2 * a / width + b) * u + center);
            // Compare before narrowing: an unbounded proposal must not saturate to int MAX_VALUE.
            if (proposed < 0 || proposed > n) return REJECTED;
            int k = (int) proposed;
            v *= alpha / (a / (width * width) + b);
            int distance = Math.abs(k - mode);
            if (distance <= 15) {
                if (distance == 0) return v <= 1 ? k : REJECTED;
                candidate = k;
                ratioIndex = Math.min(k, mode);
                ratioSteps = distance;
                ratioProduct = 1;
                ratioUniform = v;
                return CHECK_RATIO;
            }

            double logV = Math.log(v);
            double rho = (distance / variance) * (((distance / 3.0 + 0.625) * distance + 1.0 / 6) / variance + 0.5);
            double normalBound = -(double) distance * distance / (2 * variance);
            if (logV < normalBound - rho) return k;
            if (logV > normalBound + rho) return REJECTED;

            // log1p retains the near-one ratios at large n; n+1 is widened before addition.
            double nm = n - mode + 1.0;
            double nk = n - k + 1.0;
            double scaledMode = odds * nm;
            double h = (mode + 0.5) * Math.log1p((mode + 1.0 - scaledMode) / scaledMode)
                + stirlingCorrection(mode) + stirlingCorrection(n - mode);
            double logRatio = h + (n + 1.0) * Math.log1p((k - mode) / nk)
                + (k + 0.5) * Math.log1p((nk * odds - (k + 1.0)) / (k + 1.0))
                - stirlingCorrection(k) - stirlingCorrection(n - k);
            return logV <= logRatio ? k : REJECTED;
        }

        private void ratioStep() {
            // At most 15 factors; counting remaining steps avoids overflowing an int end index.
            ratioProduct *= scaledOdds / ++ratioIndex - odds;
            ratioSteps--;
        }

        private boolean ratioAccepted() {
            return candidate >= mode ? ratioUniform <= ratioProduct : ratioUniform * ratioProduct <= 1;
        }

        private boolean advance(RandomGenerator random, RecipeWorkBudget budget) {
            while (true) {
                if (ratioSteps > 0) {
                    while (ratioSteps > 0) {
                        if (!budget.take()) return false;
                        ratioStep();
                    }
                    if (ratioAccepted()) {
                        result = candidate;
                        return true;
                    }
                }
                if (!budget.take()) return false;
                int selected = propose(random);
                if (selected >= 0) {
                    result = selected;
                    return true;
                }
            }
        }
    }

    private static double stirlingCorrection(int k) {
        if (k < STIRLING_CORRECTIONS.length) return STIRLING_CORRECTIONS[k];
        double inverse = 1 / (k + 1.0);
        double square = inverse * inverse;
        return (1.0 / 12 - (1.0 / 360 - square / 1260) * square) * inverse;
    }

    /**
     * Same kernels and RNG calls as sample(). Strategy/parameters are captured once; every direct
     * trial, inversion recurrence, BTRD proposal and short-ratio factor consumes local work budget.
     */
    public static class Cursor {
        private final RandomGenerator random;
        private final int n;
        private final double probability;
        private final int strategy;
        private final boolean complement;
        private Inversion inversion;
        private Btrd btrd;
        private int trials;
        private int successes;
        private boolean complete;
        private int result;

        public Cursor(RandomGenerator random, int n, double probability, ProbabilityTuning tuning) {
            requireFinite(probability);
            this.random = random;
            this.n = n;
            if (n <= 0 || probability <= 0 || probability >= 1) {
                strategy = SINGLE;
                complement = false;
                this.probability = probability;
                finish(n <= 0 || probability <= 0 ? 0 : n);
                return;
            }
            strategy = n == 1 ? SINGLE : strategy(n, probability, tuning);
            complement = strategy >= INVERSION && probability > 0.5;
            this.probability = complement ? 1 - probability : probability;
        }

        public boolean advance(RecipeWorkBudget budget) {
            if (complete) return true;
            switch (strategy) {
                case SINGLE -> {
                    if (!budget.take()) return false;
                    finish(random.nextDouble() < probability ? 1 : 0);
                }
                case DIRECT -> {
                    while (trials < n) {
                        if (!budget.take()) return false;
                        successes += random.nextDouble() < probability ? 1 : 0;
                        trials++;
                    }
                    finish(successes);
                }
                case FAIR_BITS -> {
                    while (n - trials > Long.SIZE) {
                        if (!budget.take()) return false;
                        successes += Long.bitCount(random.nextLong());
                        trials += Long.SIZE;
                    }
                    if (!budget.take()) return false;
                    finish(successes + fairBits(random, n - trials));
                }
                case INVERSION -> {
                    if (inversion == null) {
                        if (!budget.take()) return false;
                        inversion = new Inversion(n, probability);
                    }
                    if (!inversion.advance(random, budget)) return false;
                    finish(inversion.count);
                }
                case BTRD -> {
                    if (btrd == null) {
                        if (!budget.take()) return false;
                        btrd = new Btrd(n, probability);
                    }
                    if (!btrd.advance(random, budget)) return false;
                    finish(btrd.result);
                }
                default -> throw new IllegalStateException("Unknown binomial strategy");
            }
            return true;
        }

        private void finish(int sampled) {
            result = complement ? n - sampled : sampled;
            complete = true;
        }

        public int result() {
            if (!complete) throw new IllegalStateException("Binomial draw is incomplete");
            return result;
        }
    }
}
