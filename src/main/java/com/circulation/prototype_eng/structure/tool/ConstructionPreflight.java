package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Allocates one immutable plan against one simulated stock budget, retaining exact per-position quantities.
 */
public final class ConstructionPreflight {
    private ConstructionPreflight() {
    }

    /**
     * The callback must return the obtainable amount in [0, requested amount] without consuming stock.
     */
    public static Result allocate(List<GenericStack> positions, ToLongFunction<GenericStack> available) {
        AEKey2LongMap totals = new AEKeyLinkedOpenHashMap(positions.size());
        for (var resource : positions) {
            if (resource.amount() <= 0 || totals.getLong(resource.what()) > Long.MAX_VALUE - resource.amount())
                throw new IllegalArgumentException("Construction material amount overflow");
            totals.addTo(resource.what(), resource.amount());
        }
        AEKey2LongMap budget = new AEKey2LongMap.OpenHashMap(totals.size());
        AEKey2LongMap missing = new AEKey2LongMap.OpenHashMap(totals.size());
        totals.forEachEntry((key, amount) -> {
            long supply = available.applyAsLong(new GenericStack(key, amount));
            budget.put(key, supply);
            if (supply < amount) missing.addTo(key, amount - supply);
        });
        var admitted = new IntArrayList(positions.size());
        for (int index = 0; index < positions.size(); index++) {
            var resource = positions.get(index);
            if (budget.getLong(resource.what()) < resource.amount()) continue;
            budget.addTo(resource.what(), -resource.amount());
            admitted.add(index);
        }
        return new Result(admitted, missing);
    }

    public record Result(IntList positions, AEKey2LongMap missing) {
    }
}
