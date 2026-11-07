package com.circulation.prototype_eng.api.recipe.requirement;

/**
 * Owner-allocated local work units. A slice is used by one owner or worker, never concurrently.
 */
public class RecipeWorkBudget {
    private long remaining;

    public RecipeWorkBudget(long units) {
        if (units < 0) {
            throw new IllegalArgumentException("Negative recipe work allowance");
        }
        remaining = units;
    }

    public boolean take() {
        return take(1);
    }

    public boolean take(long units) {
        if (units < 0) {
            throw new IllegalArgumentException("Negative recipe work cost");
        }
        if (units > remaining) {
            return false;
        }
        remaining -= units;
        return true;
    }

    public long remaining() {
        return remaining;
    }

    /**
     * Reclaims only work that an exited child did not spend; the child is drained exactly once.
     */
    public void reclaim(RecipeWorkBudget child) {
        long unused = child.remaining;
        child.remaining = 0;
        remaining = Math.addExact(remaining, unused);
    }

    /**
     * Transfers a bounded portion; returning from a nested kernel never replenishes the parent.
     */
    public RecipeWorkBudget allocate(long maximum) {
        long units = Math.min(remaining, maximum);
        take(units);
        return new RecipeWorkBudget(units);
    }
}
