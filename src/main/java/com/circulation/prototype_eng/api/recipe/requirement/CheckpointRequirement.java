package com.circulation.prototype_eng.api.recipe.requirement;

import java.util.Objects;

/**
 * Runs one nested requirement's complete lifecycle when the process crosses a scaled or unscaled
 * time. Zero runs during START, including zero-duration recipes. The process's committed timeline
 * owns retry/once semantics; this definition has no independent persistence or cancellation state.
 */
public record CheckpointRequirement(int time, RecipeRequirement<?> requirement, boolean scaleWithProcess) {
    public CheckpointRequirement {
        if (time < 0) {
            throw new IllegalArgumentException("Negative checkpoint time");
        }
        Objects.requireNonNull(requirement);
    }

    public CheckpointRequirement(int time, RecipeRequirement<?> requirement) {
        this(time, requirement, true);
    }
}
