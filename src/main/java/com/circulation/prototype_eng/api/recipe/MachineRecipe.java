package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Immutable recipe definition: inputs once, FE per successful work tick, outputs once. */
public record MachineRecipe(ResourceLocation id, List<GenericStack> inputs, List<GenericStack> outputs,
                            int durationTicks, long energyPerTick) {
    public MachineRecipe {
        Objects.requireNonNull(id, "id");
        if (durationTicks < 1 || energyPerTick < 0) {
            throw new IllegalArgumentException("Recipe duration must be positive and FE/t must not be negative");
        }
        inputs = normalize(inputs);
        outputs = normalize(outputs);
    }

    private static List<GenericStack> normalize(List<GenericStack> stacks) {
        var amounts = new Object2LongLinkedOpenHashMap<AEKey>();
        for (GenericStack stack : stacks) {
            Objects.requireNonNull(stack, "resource stack");
            Objects.requireNonNull(stack.what(), "resource key");
            if (stack.amount() <= 0) { throw new IllegalArgumentException("Recipe amounts must be positive"); }
            amounts.put(stack.what(), Math.addExact(amounts.getLong(stack.what()), stack.amount()));
        }
        var result = new ObjectArrayList<GenericStack>(amounts.size());
        for (var entry : amounts.object2LongEntrySet()) {
            result.add(new GenericStack(entry.getKey(), entry.getLongValue()));
        }
        return ObjectLists.unmodifiable(result);
    }
}
