package com.circulation.prototype_eng.api.recipe;


/**
 * Inventory-only candidate and canonical input quantity ceiling; full admission remains authoritative.
 */
public record RecipeInventoryMatch(MachineRecipe recipe, int maximumParallelism) {
    public RecipeInventoryMatch {
        if (maximumParallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
    }

    /**
     * Native quantities saturate; a Long.MAX_VALUE balance does not give a finite quantity ceiling.
     */
    public static int limit(long available, long unit, int maximum) {
        if (available < 0 || unit <= 0 || maximum < 0) throw new IllegalArgumentException("Invalid inventory quantity");
        return available == Long.MAX_VALUE ? maximum : (int) Math.min(maximum, available / unit);
    }
}
