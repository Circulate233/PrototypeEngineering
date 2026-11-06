package com.circulation.prototype_eng.api.recipe.process;

import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.probability.ChanceMath;

/** PM's effective per-process parallelism and saturating quantity arithmetic. */
public final class RecipeParallelism {
    private RecipeParallelism() {
    }

    public static int effective(RecipeProcess process) {
        double raw = process.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        return Math.max(1, (int) Math.floor(raw));
    }

    /** Positive counts saturate at long capacity, as in PM; negative quantities are invalid. */
    public static long scaleCount(long base, int parallelism) {
        if (base < 0 || parallelism < 1) {
            throw new IllegalArgumentException("Negative quantity or nonpositive parallelism");
        }
        return ChanceMath.scaleCount(base, parallelism);
    }
}
