package com.circulation.prototype_eng.structure.tool;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * 跟踪完成位置；优先阶段尚有未完成位置时，普通位置保持等待。
 */
final class ConstructionProgress {
    private static final byte PENDING = 0, DEFERRED = 1, COMPLETE = 2;
    private final byte[] positions;
    private int firstRemaining;
    private int cursor;
    private int done;
    private boolean waiting;

    ConstructionProgress(int size) {
        positions = new byte[size];
    }

    ConstructionProgress(int size, IntPredicate first) {
        this(size);
        for (int index = 0; index < size; index++) {
            if (first.test(index)) firstRemaining++;
            else positions[index] = DEFERRED;
        }
    }

    boolean firstPass() {
        return firstRemaining > 0;
    }

    void advance(int budget, IntFunction<Step> operation) {
        waiting = false;
        for (int visits = 0, attempts = 0; visits < positions.length && attempts < budget && done < positions.length; visits++) {
            int index = cursor;
            boolean first = firstPass();
            if (positions[index] == PENDING || positions[index] == DEFERRED && !first) {
                attempts++;
                var result = operation.apply(index);
                if (result == Step.WAIT) {
                    waiting = true;
                    break;
                }
                if (result == Step.COMPLETE) {
                    positions[index] = COMPLETE;
                    done++;
                    if (first) firstRemaining--;
                } else if (result == Step.DEFER) {
                    positions[index] = DEFERRED;
                    firstRemaining--;
                }
            }
            if (++cursor == positions.length) cursor = 0;
        }
    }

    int done() {
        return done;
    }

    boolean waiting() {
        return waiting;
    }

    /**
     * DEFER 仅用于将优先阶段的位置移交普通阶段。
     */
    enum Step {COMPLETE, RETRY, WAIT, DEFER}
}
