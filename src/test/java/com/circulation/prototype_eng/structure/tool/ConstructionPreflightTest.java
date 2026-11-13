package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.ints.IntList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionPreflightTest {
    private static final TestResources.KeyType ITEMS = new TestResources.KeyType("builder_items");
    private static final TestResources.KeyType FLUIDS = new TestResources.KeyType("builder_fluids");
    private static final TestResources.Key STONE = new TestResources.Key(ITEMS, "stone");
    private static final TestResources.Key GLASS = new TestResources.Key(ITEMS, "glass");
    private static final TestResources.Key WATER = new TestResources.Key(FLUIDS, "water");

    @Test
    void mixedStocksAllocateWholePositionsInOrderAndReportExactRemainder() {
        var queries = new AtomicInteger();
        var result = ConstructionPreflight.allocate(List.of(new GenericStack(WATER, 1000), new GenericStack(STONE, 1),
            new GenericStack(WATER, 1000), new GenericStack(GLASS, 1), new GenericStack(WATER, 1000)), required -> {
            queries.incrementAndGet();
            return required.what().equals(WATER) ? 2500 : required.what().equals(STONE) ? 1 : 0;
        });
        assertEquals(3, queries.get());
        assertEquals(IntList.of(0, 1, 2), result.positions());
        assertEquals(500, result.missing().getLong(WATER));
        assertEquals(1, result.missing().getLong(GLASS));
        assertEquals(0, result.missing().getLong(STONE));
    }

    @Test
    void partialBucketCannotPayForAFluidPositionOrBorrowAnotherMaterial() {
        var result = ConstructionPreflight.allocate(List.of(new GenericStack(WATER, 1000), new GenericStack(GLASS, 1)),
            required -> required.what().equals(WATER) ? 999 : 0);
        assertTrue(result.positions().isEmpty());
        assertEquals(1, result.missing().getLong(WATER));
        assertEquals(1, result.missing().getLong(GLASS));
    }

    @Test
    void combinedLocalAndNetworkBudgetIsConsumedOncePerKey() {
        var result = ConstructionPreflight.allocate(List.of(new GenericStack(STONE, 1), new GenericStack(STONE, 1),
            new GenericStack(STONE, 1)), required -> {
            assertEquals(3, required.amount());
            return 1 + 1; // One local item and one ME item.
        });
        assertEquals(IntList.of(0, 1), result.positions());
        assertEquals(1, result.missing().getLong(STONE));
    }

}
