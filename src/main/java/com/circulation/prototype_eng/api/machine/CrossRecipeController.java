package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * One search continues across different recipes, lazily creating process slots until the shared allowance is spent.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public class CrossRecipeController extends RecipeController {
    private boolean batchTickClaimed;
    private double batchSpeed = 1;

    public CrossRecipeController() {
    }

    public CrossRecipeController(MachineType type) {
        super(type);
    }

    public double batchSpeed() {
        return batchSpeed;
    }

    void batchSpeed(double speed) {
        batchSpeed = speed;
    }

    /**
     * Component pre phases all run before normal work; each physical machine tick grants one cohort step.
     */
    public void beginBatchTick() {
        batchTickClaimed = false;
    }

    public boolean claimBatchTick() {
        if (batchTickClaimed) return false;
        batchTickClaimed = true;
        return true;
    }

    @Override
    public MachineWorkSlots createRecipeHost(List<WorkSlotDefinition> definitions, Set<ResourceLocation> groups) {
        return MachineWorkSlots.crossRecipe(this, definitions, groups);
    }

    @Override
    public RecipeScanner createRecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
        if (host.owner() != this) throw new IllegalArgumentException("Foreign scanner host");
        return RecipeScanner.crossRecipe(host, catalog, seeds);
    }
}
