package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;

/** Exact accepted-process notifications; observers must synchronize facts and never access World from workers. */
public interface ResourceRecipeObserver {
    default Object observerIdentity() {
        return this;
    }

    default void attached(RecipeProcess process) {}

    default void detached(RecipeProcess process) {}

    /**
     * Exact completed input quantities from this observer's physical sources, published only after the
     * whole stage succeeds and its storage scopes exit. Probes and rolled-back stages never publish.
     * The immutable amounts include actual resolved keys and final quantities, not canonical recipe estimates.
     * RecipeProcess.committedInputs(stage) already contains all sources, including ordinary hatches.
     * When this observer's aggregate exceeds long for a key, several exact positive chunks are sent
     * instead of truncating amounts or rejecting the underlying valid stage; the whole-stage view is null.
     */
    default void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {}

    /** Runs after the complete stage has settled; speculative probes never publish this notification. */
    default void stageCommitted(RecipeProcess process, RecipeStage stage) {}
}
