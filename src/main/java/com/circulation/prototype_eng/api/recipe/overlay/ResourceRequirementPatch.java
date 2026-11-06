package com.circulation.prototype_eng.api.recipe.overlay;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Immutable IO overrides: null inherits the base field, while an empty list explicitly clears it. */
public record ResourceRequirementPatch(@Nullable List<GenericStack> inputs,
                                       @Nullable List<GenericStack> inputsPerTick,
                                       @Nullable List<GenericStack> outputsPerTick,
                                       @Nullable List<GenericStack> outputs,
                                       @Nullable Boolean voidExcessOutputs) {
    public ResourceRequirementPatch {
        inputs = copy(inputs);
        inputsPerTick = copy(inputsPerTick);
        outputsPerTick = copy(outputsPerTick);
        outputs = copy(outputs);
    }

    /** Keeps probability, random outputs and group/lock definitions unchanged. */
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
            base.chance(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs());
    }

    private static @Nullable List<GenericStack> copy(@Nullable List<GenericStack> stacks) {
        if (stacks == null) {
            return null;
        }
        var copy = new ObjectArrayList<GenericStack>(stacks.size());
        for (var stack : stacks) {
            Objects.requireNonNull(stack, "stack");
            Objects.requireNonNull(stack.what(), "resource key");
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Resource overlay quantities must be positive");
            }
            copy.add(stack);
        }
        return ObjectLists.unmodifiable(copy);
    }
}
