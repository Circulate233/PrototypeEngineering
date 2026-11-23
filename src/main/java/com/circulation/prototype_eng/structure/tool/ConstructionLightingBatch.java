package com.circulation.prototype_eng.structure.tool;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import net.minecraft.util.math.BlockPos;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class ConstructionLightingBatch<W> {
    private static final long INITIAL_CHECK_NANOS = 50_000L;
    private static final long MIN_CHECK_NANOS = 5_000L;
    private final Reference2ObjectLinkedOpenHashMap<W, LongLinkedOpenHashSet> worlds = new Reference2ObjectLinkedOpenHashMap<>();
    private final BiConsumer<W, BlockPos> checkLight;
    private final LongSupplier nanoTime;
    private int pending;
    private long nanosPerCheck = INITIAL_CHECK_NANOS;

    ConstructionLightingBatch(BiConsumer<W, BlockPos> checkLight, LongSupplier nanoTime) {
        this.checkLight = checkLight;
        this.nanoTime = nanoTime;
    }

    void add(W world, BlockPos pos) {
        if (worlds.computeIfAbsent(world, _ -> new LongLinkedOpenHashSet()).add(pos.toLong())) ++pending;
    }

    boolean hasPending() {
        return pending != 0;
    }

    long reservedNanos() {
        return nanosPerCheck * pending;
    }

    void flush(BooleanSupplier available) {
        if (!hasPending() || !available.getAsBoolean()) return;
        long start = nanoTime.getAsLong();
        int before = pending;
        var entries = worlds.reference2ObjectEntrySet().fastIterator();
        while (entries.hasNext() && pending > 0) {
            var entry = entries.next();
            var positions = entry.getValue();
            while (!positions.isEmpty() && available.getAsBoolean()) {
                --pending;
                checkLight.accept(entry.getKey(), BlockPos.fromLong(positions.removeFirstLong()));
            }
            if (!positions.isEmpty()) break;
        }
        int checked = before - pending;
        if (checked > 0) {
            long actual = (nanoTime.getAsLong() - start) / checked;
            nanosPerCheck = Math.clamp((nanosPerCheck + actual) / 2, MIN_CHECK_NANOS, ConstructionBudget.TICK_NANOS);
        }
    }

    void clear(W world) {
        var positions = worlds.remove(world);
        if (positions != null) pending -= positions.size();
    }

    void clear() {
        worlds.clear();
        pending = 0;
        nanosPerCheck = INITIAL_CHECK_NANOS;
    }
}
