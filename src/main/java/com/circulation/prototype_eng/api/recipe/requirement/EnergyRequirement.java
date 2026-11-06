package com.circulation.prototype_eng.api.recipe.requirement;

/** PM's start input, tick input/output and end output, kept as independent long FE quantities. */
public record EnergyRequirement(long input, long inputPerTick, long outputPerTick, long output,
                                boolean voidExcessOutputs) {
    public EnergyRequirement {
        if (input < 0 || inputPerTick < 0 || outputPerTick < 0 || output < 0) {
            throw new IllegalArgumentException("Negative energy requirement");
        }
    }
}
