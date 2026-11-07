package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Resource IO payload, combining PM item/fluid IO with AE2's extensible key types.
 * This is one built-in requirement, not the recipe model or a replacement resource type system.
 * Counts describe one execution, scaled by effective parallelism and chance at acquisition.
 * Fuzzy/dynamic groups resolve and lock one key per group at START; weighted outputs run at END.
 */
public record ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                                  List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                                  boolean voidExcessOutputs, ResourceChance chance,
                                  @Nullable RandomOutputPool randomOutputs, List<FuzzyInputGroup> fuzzyInputs,
                                  List<DynamicInputGroup> dynamicInputs) {
    public ResourceRequirement {
        inputs = canonicalStacks(inputs);
        inputsPerTick = canonicalStacks(inputsPerTick);
        outputsPerTick = canonicalStacks(outputsPerTick);
        outputs = canonicalStacks(outputs);
        Objects.requireNonNull(chance);
        fuzzyInputs = copyGroups(fuzzyInputs);
        dynamicInputs = copyGroups(dynamicInputs);
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                               List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                               boolean voidExcessOutputs, ResourceChance chance, @Nullable RandomOutputPool randomOutputs) {
        this(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs, chance, randomOutputs,
            ObjectLists.emptyList(), ObjectLists.emptyList());
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                               List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                               boolean voidExcessOutputs) {
        this(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs, ResourceChance.ALWAYS, null);
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> outputs) {
        this(inputs, ObjectLists.emptyList(), ObjectLists.emptyList(), outputs, false);
    }

    /**
     * Freezes and validates IO once; built-in overlays can reuse these privately owned lists.
     */
    public static List<GenericStack> canonicalStacks(List<GenericStack> stacks) {
        if (stacks instanceof CanonicalList<?>) {
            return stacks;
        }
        var result = new ObjectArrayList<GenericStack>(stacks.size());
        for (var stack : stacks) {
            Objects.requireNonNull(stack);
            Objects.requireNonNull(stack.what());
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Resource requirements need positive quantities");
            }
            result.add(stack);
        }
        return new CanonicalList<>(result);
    }

    private static <T> List<T> copyGroups(List<T> groups) {
        if (groups instanceof CanonicalList<?>) {
            return groups;
        }
        var result = new ObjectArrayList<T>(groups.size());
        for (var group : groups) { result.add(Objects.requireNonNull(group)); }
        return new CanonicalList<>(result);
    }

    private static final class CanonicalList<T> extends ObjectLists.UnmodifiableList<T> {
        private CanonicalList(ObjectArrayList<T> owned) {
            super(owned);
        }
    }
}
