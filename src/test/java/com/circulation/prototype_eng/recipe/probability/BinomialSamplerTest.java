package com.circulation.prototype_eng.recipe.probability;

import com.circulation.prototype_eng.api.recipe.probability.BinomialSampler;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinomialSamplerTest {
    private static final ProbabilityTuning AUTOMATIC = new ProbabilityTuning(false, 0);

    @Test
    void oneParallelUsesOneUniformBeforeAnyBatchPolicyAndDoesNotRedrawWhenResumed() {
        var forced = new ProbabilityTuning(true, Integer.MAX_VALUE);
        for (double p : new double[]{0.01, 0.25, 0.5, 0.75, 0.99}) {
            for (double u : new double[]{0, Math.nextDown(p), p, Math.nextUp(p), Math.nextDown(1.0)}) {
                int expected = u < p ? 1 : 0;
                var ordinary = new DoubleSequence(u);
                assertEquals(expected, BinomialSampler.sample(ordinary, 1, p, forced));
                assertEquals(1, ordinary.index);
                var resumed = new DoubleSequence(u);
                var cursor = new BinomialSampler.Cursor(resumed, 1, p, forced);
                assertFalse(cursor.advance(new RecipeWorkBudget(0)));
                assertEquals(0, resumed.index);
                assertThrows(IllegalStateException.class, cursor::result);
                var one = new RecipeWorkBudget(1);
                assertTrue(cursor.advance(one));
                assertEquals(0, one.remaining());
                assertEquals(expected, cursor.result());
                assertTrue(cursor.advance(new RecipeWorkBudget(0)));
                assertEquals(1, resumed.index);
            }
        }
    }

    @Test
    void explicitDirectPolicyChangesSmallDrawsButCannotForceAMillionTrialLoop() {
        assertEquals(0, BinomialSampler.sample(new DoubleSequence(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5),
            8, 0.4, new ProbabilityTuning(true, 8)));
        assertEquals(3, BinomialSampler.sample(new DoubleSequence(0.5),
            8, 0.4, new ProbabilityTuning(true, 7)));
        assertThrows(IllegalArgumentException.class, () -> new ProbabilityTuning(true, -1));

        var random = new CountingRandom(0xA114L);
        var forced = new ProbabilityTuning(true, Integer.MAX_VALUE);
        for (int draw = 0; draw < 1000; draw++) {
            int value = BinomialSampler.sample(random, 1_000_000, 0.25, forced);
            assertTrue(value >= 0 && value <= 1_000_000);
        }
        assertTrue(random.doubles < 5000, "A large explicit threshold must still use aggregate rejection sampling");
    }

    @Test
    void fairBatchesPackRandomWordsIncludingFullWordAndShiftBoundaries() {
        for (int n : new int[]{2, 3, 16, 31, 32, 33, 63, 64, 65, 96, 128, 256, 383, 384}) {
            var random = new RandomGenerator() {
                @Override
                public long nextLong() {
                    return -1;
                }

                @Override
                public int nextInt() {
                    return -1;
                }

                @Override
                public double nextDouble() {
                    throw new AssertionError("Fair batches use a random word");
                }
            };
            assertEquals(n, BinomialSampler.sample(random, n, 0.5, AUTOMATIC));
            var cursor = new BinomialSampler.Cursor(random, n, 0.5, AUTOMATIC);
            int words = (n + Long.SIZE - 1) / Long.SIZE;
            for (int word = 1; word < words; word++) assertFalse(cursor.advance(new RecipeWorkBudget(1)));
            assertTrue(cursor.advance(new RecipeWorkBudget(1)));
            assertEquals(n, cursor.result());
        }
    }

    @Test
    void smallCountsAndBtrdTransitionMatchExactBinomialCumulativeProbabilities() {
        // Independent combinatorial probabilities cover the old small-n Poisson bias and both
        // BTRD sides of its mean boundary, including a complemented skewed distribution.
        for (int n : new int[]{2, 4, 5, 6, 7, 20, 32, 65, 100, 384, 385}) {
            for (double p : new double[]{0.1, 0.49, 0.5, 0.8}) {
                int samples = 100_000;
                int[] histogram = new int[n + 1];
                var random = new Random(0xCAD13L + n);
                for (int draw = 0; draw < samples; draw++) {
                    histogram[BinomialSampler.sample(random, n, p, AUTOMATIC)]++;
                }
                BigInteger coefficient = BigInteger.ONE;
                double expectedCdf = 0;
                long observed = 0;
                for (int k = 0; k < n; k++) {
                    double mass = coefficient.doubleValue() * Math.pow(p, k) * Math.pow(1 - p, n - k);
                    expectedCdf += mass;
                    observed += histogram[k];
                    double cdf = Math.min(1, expectedCdf);
                    double tolerance = 6 * Math.sqrt(cdf * (1 - cdf) / samples) + 3.0 / samples;
                    assertEquals(cdf, (double) observed / samples, tolerance, "n=" + n + ", p=" + p + ", k=" + k);
                    coefficient = coefficient.multiply(BigInteger.valueOf(n - k)).divide(BigInteger.valueOf(k + 1L));
                }
            }
        }
    }

    @Test
    void millionAndMaximumParallelismMatchBinomialMeanAndVarianceWithBoundedRandomWork() {
        for (int n : new int[]{1_000_000, Integer.MAX_VALUE}) {
            for (double p : new double[]{1.0e-6, 0.01, 0.25, 0.5, 0.99, 1 - 1.0e-6}) {
                var random = new CountingRandom(0xBAA13L);
                int samples = 50_000;
                double expectedMean = n * p;
                double expectedVariance = expectedMean * (1 - p);
                double sum = 0;
                double squareSum = 0;
                for (int draw = 0; draw < samples; draw++) {
                    int value = BinomialSampler.sample(random, n, p, AUTOMATIC);
                    assertTrue(value >= 0 && value <= n);
                    double offset = value - expectedMean;
                    sum += offset;
                    squareSum += offset * offset;
                }
                double meanOffset = sum / samples;
                double variance = squareSum / samples - meanOffset * meanOffset;
                assertEquals(0, meanOffset, 6 * Math.sqrt(expectedVariance / samples));
                assertEquals(expectedVariance, variance, expectedVariance * 0.04);
                assertTrue(random.doubles < 5L * samples, "Large draws must not scale RNG work with n");
            }
        }
    }

    @Test
    void millionParallelSamplesRetainTheBinomialFourDeviationTails() {
        int samples = 1_000_000;
        int tails = 0;
        var random = new Random(0x71A15L);
        for (int draw = 0; draw < samples; draw++) {
            int value = BinomialSampler.sample(random, 1_000_000, 0.5, AUTOMATIC);
            if (Math.abs(value - 500_000) >= 2000) tails++;
        }
        // Independent binomial PMF summation from k=502000 to n, doubled by symmetry.
        // A finite Gaussian lookup cannot reliably represent this ~0.00636% tail mass.
        double probability = 6.360933749542204e-5;
        assertEquals(samples * probability, tails, 6 * Math.sqrt(samples * probability * (1 - probability)));
    }

    @Test
    void budgetSlicesPreserveResultsAndFollowingRandomStateAcrossAllKernels() {
        for (int n : new int[]{1, 2, 5, 32, 64, 65, 128, 384, 385, 1024, 1_000_000, Integer.MAX_VALUE}) {
            for (double p : new double[]{1.0e-6, 0.01, 0.25, 0.49, 0.5, 0.75, 0.99}) {
                for (int seed = 0; seed < 32; seed++) {
                    var ordinary = new Random(seed);
                    int expected = BinomialSampler.sample(ordinary, n, p, AUTOMATIC);
                    long following = ordinary.nextLong();
                    var sliced = new CountingRandom(seed);
                    var cursor = new BinomialSampler.Cursor(sliced, n, p, AUTOMATIC);
                    int slices = 0;
                    while (true) {
                        long calls = sliced.doubles;
                        assertFalse(cursor.advance(new RecipeWorkBudget(0)));
                        assertEquals(calls, sliced.doubles, "Waiting must not consume random values");
                        if (cursor.advance(new RecipeWorkBudget(1))) break;
                        assertTrue(++slices < 1000, "An exhausted slice must retain progress");
                    }
                    assertEquals(expected, cursor.result(), "n=" + n + ", p=" + p + ", seed=" + seed);
                    assertTrue(cursor.advance(new RecipeWorkBudget(0)));
                    assertEquals(following, sliced.nextLong(), "Slices must not restart or redraw");
                }
            }
        }
    }

    @Test
    void extremeProposalsAreRejectedBeforeIntNarrowingAndContinueOnTheNextSlice() {
        double[] draws = {0.99, Math.nextDown(1.0), 0.2};
        var ordinary = new DoubleSequence(draws);
        int expected = BinomialSampler.sample(ordinary, Integer.MAX_VALUE, 0.25, AUTOMATIC);
        assertTrue(expected > 500_000_000 && expected < 600_000_000);
        assertEquals(3, ordinary.index);
        var random = new DoubleSequence(draws);
        var cursor = new BinomialSampler.Cursor(random, Integer.MAX_VALUE, 0.25, AUTOMATIC);
        assertFalse(cursor.advance(new RecipeWorkBudget(1))); // Parameter setup.
        assertEquals(0, random.index);
        assertFalse(cursor.advance(new RecipeWorkBudget(1))); // Out-of-support proposal.
        assertEquals(2, random.index);
        assertTrue(cursor.advance(new RecipeWorkBudget(1)));
        assertEquals(expected, cursor.result());
        assertEquals(3, random.index);
    }

    @Test
    void complementedLargeProbabilitiesShareSeededDraws() {
        var lower = new Random(0xB180L);
        var upper = new Random(0xB180L);
        for (int draw = 0; draw < 1000; draw++) {
            int low = BinomialSampler.sample(lower, Integer.MAX_VALUE, 0.25, AUTOMATIC);
            int high = BinomialSampler.sample(upper, Integer.MAX_VALUE, 0.75, AUTOMATIC);
            assertEquals(Integer.MAX_VALUE, (long) low + high);
        }
    }

    @Test
    void inversionUsesOneUniformAtRareMillionProbabilitiesAndRetainsTinyNonzeroChances() {
        double[] uniforms = {0.1, 0.5, 0.9, 0.99, 0.999};
        int[] quantiles = {0, 1, 2, 4, 5};
        for (int index = 0; index < uniforms.length; index++) {
            var random = new DoubleSequence(uniforms[index]);
            assertEquals(quantiles[index], BinomialSampler.sample(random, 1_000_000, 1.0e-6, AUTOMATIC));
            assertEquals(1, random.index);
        }
        assertEquals(1, BinomialSampler.sample(new DoubleSequence(1 - 1.0e-8),
            Integer.MAX_VALUE, 0x1.0p-54, AUTOMATIC));
        var tail = new DoubleSequence(Math.nextDown(1.0));
        int last = BinomialSampler.sample(tail, Integer.MAX_VALUE, 1.0e-9, AUTOMATIC);
        assertTrue(last >= 20 && last < 64, "CDF roundoff must not turn a rare draw into a linear tail walk");
        assertEquals(1, tail.index);
    }

    @Test
    void finiteBoundaryProbabilitiesNeedNoRandomWorkAndNonfiniteValuesAreRejected() {
        var noDraws = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new AssertionError("A boundary probability needs no RNG call");
            }
        };
        for (int n : new int[]{-1, 0, 1, Integer.MAX_VALUE}) {
            assertEquals(0, BinomialSampler.sample(noDraws, n, -1, AUTOMATIC));
            assertEquals(Math.max(0, n), BinomialSampler.sample(noDraws, n, 2, AUTOMATIC));
            var cursor = new BinomialSampler.Cursor(noDraws, n, 2, AUTOMATIC);
            assertTrue(cursor.advance(new RecipeWorkBudget(0)));
            assertEquals(Math.max(0, n), cursor.result());
        }
        for (double p : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> BinomialSampler.sample(noDraws, 0, p, AUTOMATIC));
            assertThrows(IllegalArgumentException.class, () -> new BinomialSampler.Cursor(noDraws, 0, p, AUTOMATIC));
        }
    }

    private static class CountingRandom extends Random {
        private long doubles;

        private CountingRandom(long seed) {
            super(seed);
        }

        @Override
        public double nextDouble() {
            doubles++;
            return super.nextDouble();
        }
    }

    private static class DoubleSequence implements RandomGenerator {
        private final double[] values;
        private int index;

        private DoubleSequence(double... values) {
            this.values = values;
        }

        @Override
        public long nextLong() {
            throw new AssertionError("Unexpected integer draw");
        }

        @Override
        public double nextDouble() {
            return values[index++];
        }
    }
}
