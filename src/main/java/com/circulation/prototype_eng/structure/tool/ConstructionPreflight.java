package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import java.util.Iterator;
import java.util.List;
import java.util.function.ToLongFunction;

public final class ConstructionPreflight {
    private ConstructionPreflight() {
    }

    public static Result allocate(List<GenericStack> positions, ToLongFunction<GenericStack> available) {
        var stock = new Stock();
        for (int index = 0; index < positions.size(); index++) stock.require(positions.get(index));
        var missing = new AEKey2LongMap.OpenHashMap();
        for (var entries = stock.requirements(); entries.hasNext(); ) {
            var required = stock.requirement(entries.next());
            long absent = stock.reserve(required, available.applyAsLong(required));
            if (absent > 0) missing.addTo(required.what(), absent);
        }
        var admitted = new IntArrayList(positions.size());
        for (int index = 0; index < positions.size(); index++) {
            if (stock.admit(positions.get(index))) admitted.add(index);
        }
        return new Result(admitted, missing);
    }

    public record Result(IntList positions, AEKey2LongMap missing) {
    }

    static final class Stock {
        private final AEKeyLinkedOpenHashMap totals = new AEKeyLinkedOpenHashMap(4);
        private final AEKey2LongMap budget = new AEKey2LongMap.OpenHashMap();

        void require(GenericStack resource) {
            long amount = resource.amount();
            var key = resource.what();
            if (amount <= 0 || totals.getLong(key) > Long.MAX_VALUE - amount)
                throw new IllegalArgumentException("Construction material amount overflow");
            totals.addTo(key, amount);
        }

        boolean isEmpty() {
            return totals.isEmpty();
        }

        Iterator<AEKey> requirements() {
            return totals.keySet().iterator();
        }

        GenericStack requirement(AEKey key) {
            return new GenericStack(key, totals.getLong(key));
        }

        long reserve(GenericStack required, long available) {
            if (available < 0 || available > required.amount()) throw new IllegalArgumentException("Invalid construction material availability");
            budget.put(required.what(), available);
            return required.amount() - available;
        }

        boolean admit(GenericStack resource) {
            var key = resource.what();
            long amount = resource.amount();
            if (budget.getLong(key) < amount) return false;
            budget.addTo(key, -amount);
            return true;
        }
    }
}
