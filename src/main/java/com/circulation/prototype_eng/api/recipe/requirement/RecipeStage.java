package com.circulation.prototype_eng.api.recipe.requirement;

public enum RecipeStage {
    START, TICK, END;

    /** Shared declaration order; callers must not modify this array. */
    public static final RecipeStage[] VALUES = values();
}
