package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/**
 * Uses the same process-local selection contract as Java recipes; arbitrary callbacks stay unknown.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.ParallelismSelection")
@SuppressWarnings("unused") // Compiled script and Java addon entry points.
public record ZenParallelismSelection(RecipeParallelismSelection value) {
    public ZenParallelismSelection {
        Objects.requireNonNull(value);
    }

    @ZenMethod
    public static ZenParallelismSelection fixed(int parallelism) {
        return new ZenParallelismSelection(RecipeParallelismSelection.fixed(parallelism));
    }

    @ZenMethod
    public static ZenParallelismSelection create(ZenParallelismCallback callback) {
        Objects.requireNonNull(callback);
        return new ZenParallelismSelection(process -> {
            int selected = callback.select(RecipeParallelism.effective(process));
            if (selected < 1) throw new IllegalArgumentException("Selected parallelism must be positive");
            process.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, selected);
        });
    }
}
