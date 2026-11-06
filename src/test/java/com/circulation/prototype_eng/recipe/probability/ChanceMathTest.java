package com.circulation.prototype_eng.recipe.probability;

import com.circulation.prototype_eng.api.recipe.probability.ChanceMath;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChanceMathTest {
    @Test
    void overOneHundredPercentAddsGuaranteedRoundsAndIndependentFractionalTrials() {
        var exact = new ProbabilityTuning(true, 5);
        var random = new DoubleSequence(0.1, 0.9, 0.2, 0.8, 0.6);
        assertEquals(12, ChanceMath.sampleTimes(random, 5, 250, exact));
        assertEquals(15, ChanceMath.maxTimes(5, 250));
        assertEquals(5, ChanceMath.sampleTimes(new NoRandom(), 5, 100, exact));
        assertEquals(0, ChanceMath.sampleTimes(new NoRandom(), 5, 0, exact));
    }

    @Test
    void allSampledCountsFitTheirBoundIncludingHugePercentagesAndParallelism() {
        var random = new Random(0x6CA18L);
        for (int parallels : new int[]{1, 7, 1025, Integer.MAX_VALUE}) {
            for (double chance : new double[]{0.1, 99.9, 100, 100.1, 250, 1.0e15,
                Math.nextDown(0x1.0p63 * 100), 0x1.0p63 * 100, Double.MAX_VALUE}) {
                long bound = ChanceMath.maxTimes(parallels, chance);
                assertTrue(bound > 0);
                for (int sample = 0; sample < 32; sample++) {
                    long times = ChanceMath.sampleTimes(random, parallels, chance);
                    assertTrue(times >= 0 && times <= bound);
                }
            }
        }
        assertEquals(Long.MAX_VALUE, ChanceMath.maxTimes(1, Double.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, ChanceMath.sampleTimes(new NoRandom(), 1, Double.MAX_VALUE));
        assertEquals(2L * Integer.MAX_VALUE, ChanceMath.sampleTimes(new NoRandom(), Integer.MAX_VALUE, 200));
    }

    @Test
    void repeatedQuantitiesSaturateWithoutWrappingAndZeroTimesNeedsNoResources() {
        long exact = Long.MAX_VALUE / 3;
        assertEquals(Long.MAX_VALUE - 1, ChanceMath.scaleCount(exact, 3));
        assertEquals(Long.MAX_VALUE, ChanceMath.scaleCount(exact + 1, 3));
        assertEquals(Long.MAX_VALUE, ChanceMath.scaleCount(2, Long.MAX_VALUE));
        assertEquals(0, ChanceMath.scaleCount(Long.MAX_VALUE, 0));
        assertEquals(0, ChanceMath.scaleCount(0, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ChanceMath.scaleCount(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> ChanceMath.scaleCount(0, -1));
    }

    @Test
    void nonfiniteChanceCannotTurnAnInvalidRequirementIntoAFreeOperation() {
        for (double chance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> ChanceMath.sampleTimes(new NoRandom(), 0, chance));
            assertThrows(IllegalArgumentException.class, () -> ChanceMath.maxTimes(0, chance));
        }
        assertEquals(0, ChanceMath.sampleTimes(new NoRandom(), 7, -1));
        assertEquals(0, ChanceMath.maxTimes(7, -1));
    }

    private static final class DoubleSequence implements RandomGenerator {
        private final double[] values;
        private int index;

        private DoubleSequence(double... values) {
            this.values = values;
        }

        @Override public long nextLong() { throw new AssertionError("Unexpected integer draw"); }
        @Override public double nextDouble() { return values[index++]; }
    }

    private static final class NoRandom implements RandomGenerator {
        @Override public long nextLong() { throw new AssertionError("No random draw is needed"); }
    }
}
