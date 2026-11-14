package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.requirement.MachineRecipeRequirements;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;

import java.util.function.LongSupplier;

/**
 * Common runtime controller responsibilities; physical attachment and the original scheduler remain shared.
 */
public abstract class RecipeController extends MachineInstance {
    protected RecipeController() {
    }

    protected RecipeController(MachineType type) {
        super(type);
    }

    @Override
    protected ExecutionMode defaultExecutionMode() {
        return ExecutionMode.PLANNED_PARALLEL;
    }

    @Override
    protected ControllerExecutionPolicy defaultExecutionPolicy() {
        return ControllerExecutionPolicy.FULL_ASYNC;
    }

    @Override
    public abstract RecipeScanner createRecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds);

    @Override
    public boolean supportsStableFixedInputLowerBounds() {
        var scanners = recipeScanners();
        if (scanners.isEmpty()) return false;
        for (int index = 0; index < scanners.size(); index++) {
            var scanner = scanners.get(index);
            if (!scanner.supportsStableFixedInputLowerBounds()) return false;
        }
        var elements = components().assemblyBindings();
        for (int index2 = 0; index2 < elements.size(); index2++) {
            var binding = elements.get(index2);
            if (binding.component() instanceof MachineRecipeRequirements) return false;
        }
        return true;
    }
}
