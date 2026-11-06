package com.circulation.prototype_eng.recipe.probability;

import com.circulation.prototype_eng.api.recipe.probability.BinomialSampler;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinomialSamplerTest {
    @Test
    void explicitExactPolicyAndItsThresholdChangeTheSmallSampleStrategy() {
        assertEquals(0, BinomialSampler.sample(new ConstantDouble(0.5), 5, 0.4, new ProbabilityTuning(true, 5)));
        assertEquals(2, BinomialSampler.sample(new ConstantDouble(0.5), 5, 0.4, new ProbabilityTuning(true, 4)));
        assertThrows(IllegalArgumentException.class, () -> new ProbabilityTuning(true, -1));
    }

    @Test
    void complementedProbabilitiesShareSeededDrawsAtExtremeParallelism() {
        var lower = new Random(0xB180L);
        var upper = new Random(0xB180L);
        var approximate = new ProbabilityTuning(false, 0);
        for (int sample = 0; sample < 64; sample++) {
            int low = BinomialSampler.sample(lower, Integer.MAX_VALUE, 0.25, approximate);
            int high = BinomialSampler.sample(upper, Integer.MAX_VALUE, 0.75, approximate);
            assertTrue(low >= 0 && high >= 0);
            assertEquals(Integer.MAX_VALUE, (long) low + high);
        }
    }

    @Test
    void exactPoissonAndTableStrategiesStayNearTheirExpectedMoments() {
        checkMoments(20, 0.25, new ProbabilityTuning(true, 20), 0.25);
        checkMoments(10000, 0.0005, new ProbabilityTuning(false, 0), 0.25);
        checkMoments(1024, 0.3, new ProbabilityTuning(false, 0), 1.5);
    }

    @Test
    void finiteBoundaryProbabilitiesClampAndPoissonCannotExceedTrialCount() {
        var approximate = new ProbabilityTuning(false, 0);
        var noDraws = new ConstantDouble(0.5);
        assertEquals(0, BinomialSampler.sample(noDraws, 100, -1, approximate));
        assertEquals(100, BinomialSampler.sample(noDraws, 100, 2, approximate));
        assertEquals(3, BinomialSampler.sample(new ConstantDouble(0.999999), 3, 0.4, approximate));
        for (double probability : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> BinomialSampler.sample(noDraws, 0, probability, approximate));
        }
    }

    private static void checkMoments(int trials, double probability, ProbabilityTuning tuning, double meanTolerance) {
        var random = new Random(0xBAA13L);
        int samples = 10000;
        double sum = 0;
        double sumSquares = 0;
        for (int sample = 0; sample < samples; sample++) {
            int successes = BinomialSampler.sample(random, trials, probability, tuning);
            assertTrue(successes >= 0 && successes <= trials);
            sum += successes;
            sumSquares += (double) successes * successes;
        }
        double mean = sum / samples;
        double variance = sumSquares / samples - mean * mean;
        double expectedMean = trials * probability;
        double expectedVariance = expectedMean * (1 - probability);
        assertEquals(expectedMean, mean, meanTolerance);
        // PM deliberately uses approximations; broad moment bounds catch broken math without exactness claims.
        assertEquals(expectedVariance, variance, expectedVariance * 0.3);
    }

    private record ConstantDouble(double value) implements RandomGenerator {
        @Override public long nextLong() { throw new AssertionError("Unexpected integer draw"); }
        @Override public double nextDouble() { return value; }
    }
}
