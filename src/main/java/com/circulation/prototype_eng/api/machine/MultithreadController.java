package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;

import java.util.function.LongSupplier;

/** Each declared logical thread owns an independent recipe search and shares the machine's parallel allowance. */
public class MultithreadController extends RecipeController {
    public MultithreadController() { }
    public MultithreadController(MachineType type) { super(type); }
    @Override public RecipeScanner createRecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
        if (host.owner() != this) throw new IllegalArgumentException("Foreign scanner host");
        return RecipeScanner.multithreaded(host, catalog, seeds);
    }
}
