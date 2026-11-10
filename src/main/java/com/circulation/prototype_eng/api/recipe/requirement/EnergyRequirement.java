package com.circulation.prototype_eng.api.recipe.requirement;

import java.util.Objects;

/**
 * PM's start input, tick input/output and end output, kept as independent long FE quantities.
 */
public record EnergyRequirement(long input, long inputPerTick, long outputPerTick, long output,
                                boolean voidExcessOutputs, InputAllocation inputAllocation) {
    public EnergyRequirement {
        Objects.requireNonNull(inputAllocation);
        if (input < 0 || inputPerTick < 0 || outputPerTick < 0 || output < 0) {
            throw new IllegalArgumentException("Negative energy requirement");
        }
    }

    public EnergyRequirement(long input, long inputPerTick, long outputPerTick, long output, boolean voidExcessOutputs) {
        this(input, inputPerTick, outputPerTick, output, voidExcessOutputs, InputAllocation.SPLITTABLE);
    }

    public EnergyRequirement withInputAllocation(InputAllocation allocation) {
        return new EnergyRequirement(input, inputPerTick, outputPerTick, output, voidExcessOutputs, allocation);
    }
}
