package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;

import java.util.List;
import java.util.Objects;

/** START availability checks shared by all parallels, without consuming or retaining resources. */
public record ResourcePresenceRequirement(ResourceRequirement resources) {
    public ResourcePresenceRequirement {
        Objects.requireNonNull(resources, "Presence resources");
        if (!resources.inputsPerTick().isEmpty() || !resources.outputsPerTick().isEmpty() || !resources.outputs().isEmpty()
            || resources.randomOutputs() != null || !resources.chance().equals(ResourceChance.ALWAYS) || resources.voidExcessOutputs())
            throw new IllegalArgumentException("Resource presence accepts only unconditional START inputs");
    }

    public ResourcePresenceRequirement(List<GenericStack> inputs) {
        this(new ResourceRequirement(inputs, List.of()));
    }
}
