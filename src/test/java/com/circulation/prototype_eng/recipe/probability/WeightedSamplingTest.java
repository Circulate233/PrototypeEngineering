package com.circulation.prototype_eng.recipe.probability;

import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling.Weighted;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedSamplingTest {
    @Test
    void entriesAreRemovedIndividuallyAndNonpositiveWeightsAreIgnored() {
        var candidates = List.of(new Weighted<>("same", 1), new Weighted<>("same", 3),
            new Weighted<>("other", 2), new Weighted<>("ignored", 0), new Weighted<>("negative", -1));
        var result = WeightedSampling.sampleWithoutReplacement(new Random(17), candidates, 10);
        assertEquals(3, result.size());
        assertEquals(2, result.stream().filter("same"::equals).count());
        assertEquals(1, result.stream().filter("other"::equals).count());
        assertEquals(List.of("same", "same", "other", "ignored", "negative"),
            candidates.stream().map(Weighted::value).toList());
    }

    @Test
    void integerLotteryCrossesLargeWeightBoundariesWithoutOverflow() {
        var candidates = List.of(new Weighted<>("first", Integer.MAX_VALUE), new Weighted<>("second", Integer.MAX_VALUE));
        var result = WeightedSampling.sampleWithoutReplacement(new LongSequence(Integer.MAX_VALUE, 0), candidates, 2);
        assertEquals(List.of("second", "first"), result);
    }

    @Test
    void firstDrawTracksRelativeWeightsAndSeededSelectionIsRepeatable() {
        var candidates = List.of(new Weighted<>(0, 1), new Weighted<>(1, 3), new Weighted<>(2, 6));
        var random = new Random(0x5A191L);
        var repeat = new Random(0x5A191L);
        int[] counts = new int[3];
        for (int sample = 0; sample < 20000; sample++) {
            var selected = WeightedSampling.sampleWithoutReplacement(random, candidates, 1);
            assertEquals(selected, WeightedSampling.sampleWithoutReplacement(repeat, candidates, 1));
            counts[selected.getFirst()]++;
        }
        assertEquals(2000, counts[0], 400);
        assertEquals(6000, counts[1], 400);
        assertEquals(12000, counts[2], 400);
    }

    private static final class LongSequence implements RandomGenerator {
        private final long[] values;
        private int index;

        private LongSequence(long... values) {
            this.values = values;
        }

        @Override public long nextLong() { throw new AssertionError("A bounded draw is required"); }
        @Override public long nextLong(long bound) {
            long value = values[index++];
            assertTrue(value >= 0 && value < bound);
            return value;
        }
    }
}
