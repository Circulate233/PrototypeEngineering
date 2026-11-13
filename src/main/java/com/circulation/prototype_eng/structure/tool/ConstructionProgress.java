package com.circulation.prototype_eng.structure.tool;

import java.util.function.IntFunction;

/**
 * Tracks completed positions while retaining the current position during a material wait.
 */
final class ConstructionProgress {
    private final boolean[] complete;
    private int cursor;
    private int done;
    private boolean waiting;

    ConstructionProgress(int size) {
        complete = new boolean[size];
    }

    void advance(int budget, IntFunction<Step> operation) {
        waiting = false;
        for (int visits = 0, attempts = 0; visits < complete.length && attempts < budget && done < complete.length; visits++) {
            int index = cursor;
            if (!complete[index]) {
                attempts++;
                var result = operation.apply(index);
                if (result == Step.WAIT) {
                    waiting = true;
                    break;
                }
                if (result == Step.COMPLETE) {
                    complete[index] = true;
                    done++;
                }
            }
            if (++cursor == complete.length) cursor = 0;
        }
    }

    int done() {
        return done;
    }

    boolean waiting() {
        return waiting;
    }

    enum Step {COMPLETE, RETRY, WAIT}
}
