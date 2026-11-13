package com.circulation.prototype_eng.api.recipe.overlay;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Immutable IO overrides: null inherits the base field, while an empty list explicitly clears it.
 * Non-null input lists are borrowed and must remain unchanged while this patch or its result is used.
 */
public record ResourceRequirementPatch(@Nullable List<GenericStack> inputs,
                                       @Nullable List<GenericStack> inputsPerTick,
                                       @Nullable List<GenericStack> outputsPerTick,
                                       @Nullable List<GenericStack> outputs,
                                       @Nullable Boolean voidExcessOutputs) {
    public ResourceRequirementPatch {
        inputs = validate(inputs);
        inputsPerTick = validate(inputsPerTick);
        outputsPerTick = validate(outputsPerTick);
        outputs = validate(outputs);
    }

    private static @Nullable List<GenericStack> validate(@Nullable List<GenericStack> stacks) {
        if (stacks == null) {
            return null;
        }
        ResourceRequirement.validateStacks(stacks);
        return stacks;
    }

    /**
     * Keeps probability, random outputs and group/lock definitions unchanged.
     */
    public ResourceRequirement apply(ResourceRequirement base) {
        Objects.requireNonNull(base, "base");
        if (inputs == null && inputsPerTick == null && outputsPerTick == null && outputs == null
            && voidExcessOutputs == null) {
            return base;
        }
        return new ResourceRequirement(inputs == null ? base.inputs() : inputs,
            inputsPerTick == null ? base.inputsPerTick() : inputsPerTick,
            outputsPerTick == null ? base.outputsPerTick() : outputsPerTick,
            outputs == null ? base.outputs() : outputs,
            voidExcessOutputs == null ? base.voidExcessOutputs() : voidExcessOutputs,
            base.chance(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs(), base.inputAllocation());
    }
}
