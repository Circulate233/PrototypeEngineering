package com.circulation.prototype_eng.structure.tool;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;

import java.util.BitSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

final class ConstructionProgress {
    private final IntArrayFIFOQueue clearFluids = new IntArrayFIFOQueue();
    private final IntArrayFIFOQueue solids = new IntArrayFIFOQueue();
    private final IntArrayFIFOQueue fluids = new IntArrayFIFOQueue();
    private final BitSet cleared = new BitSet();
    private int done;
    private int unvisited;
    private long lastTick = Long.MIN_VALUE;
    private long fluidBarrierTick = Long.MIN_VALUE;
    private Phase pass = Phase.CLEAR_FLUIDS;
    private boolean waiting;
    private boolean missingSolid;

    void add(int index, boolean clearFirst, boolean placeFluidLast) {
        if (clearFirst) clearFluids.enqueue(index);
        (placeFluidLast ? fluids : solids).enqueue(index);
    }

    Phase nextPhase() {
        return !clearFluids.isEmpty() ? Phase.CLEAR_FLUIDS : !solids.isEmpty() ? Phase.SOLIDS
            : fluids.isEmpty() ? Phase.DONE : missingSolid ? Phase.SKIP_FLUIDS : Phase.FLUIDS;
    }

    void missSolid() {
        missingSolid = true;
    }

    boolean advance(long tick, Phase phase, BooleanSupplier available, IntFunction<Step> operation) {
        var queue = switch (phase) {
            case CLEAR_FLUIDS -> clearFluids;
            case SOLIDS -> solids;
            case FLUIDS, SKIP_FLUIDS -> fluids;
            case DONE -> throw new IllegalStateException("No construction positions remaining");
        };
        if (phase == Phase.FLUIDS && tick == fluidBarrierTick) return false;
        if (tick != lastTick || phase != pass) {
            lastTick = tick;
            pass = phase;
            unvisited = queue.size();
            waiting = false;
        }
        if (waiting || unvisited == 0) return false;
        while (unvisited > 0 && available.getAsBoolean()) {
            int index = queue.firstInt();
            if (phase != Phase.CLEAR_FLUIDS && cleared.get(index)) {
                queue.dequeueInt();
                cleared.clear(index);
            } else {
                var result = operation.apply(index);
                if (result == Step.WAIT) {
                    waiting = true;
                    return false;
                }
                queue.dequeueInt();
                switch (result) {
                    case COMPLETE -> {
                        if (phase == Phase.CLEAR_FLUIDS) cleared.set(index);
                        done++;
                    }
                    case RETRY -> queue.enqueue(index);
                    case CONTINUE -> {
                    }
                    case WAIT -> throw new IllegalStateException("Waiting must preserve the current position");
                }
            }
            --unvisited;
        }
        if (!queue.isEmpty()) return unvisited > 0;
        var next = nextPhase();
        if (next == Phase.FLUIDS && phase != Phase.FLUIDS) {
            fluidBarrierTick = tick;
            return false;
        }
        return next != Phase.DONE;
    }

    int done() {
        return done;
    }

    boolean waiting() {
        return waiting;
    }

    enum Phase {CLEAR_FLUIDS, SOLIDS, FLUIDS, SKIP_FLUIDS, DONE}

    enum Step {COMPLETE, RETRY, WAIT, CONTINUE}
}
