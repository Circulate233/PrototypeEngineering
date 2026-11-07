package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;

import com.circulation.prototype_eng.api.storage.StorageContext;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * External start/tick/end extension. Acquisition may reserve/apply reversible changes. It must
 * return a transaction for ALL effects, including partial acquisition and every reservation, or undo
 * its own changes and release its own tokens before throwing. Business commit remains reversible
 * until the stage coordinator completes ALL requirements; a requirement must not complete its own
 * resource/FE tokens. Immutable definitions/systems can be shared; process state cannot be shared
 * between concurrent owners without an explicit reservation/ownership protocol.
 */
public interface RecipeRequirementSystem<C> {
    /**
     * Optional pure preparation at this requirement's actual arrival, after preceding modifiers and
     * overlays. Capture has no effects; only the returned computation runs on a worker. Opaque legacy
     * callbacks retain their owner execution. Null selects the ordinary start/tick/end callback.
     */
    default @Nullable RequirementPreparation prepare(RecipeProcess process,
                                                     RecipeRequirement<C> requirement,
                                                     RecipeStage stage) {
        return null;
    }
    /** Immutable structural children used to bind nested saved state; this does not execute them. */
    default List<RecipeRequirement<?>> children(C payload) { return List.of(); }

    /** Declare before acquisition when this system can return an ordered continuation. */
    default boolean continuedAcquisition(C payload) { return false; }

    /**
     * Additional physical domains this definition or its callbacks may read/write in a staged
     * sequence. Declare a stable superset before acquisition; the process's captured resource/FE
     * hatches and structural children are included automatically. Declaration must have no effects
     * and must not acquire storage locks. Undeclared domains fail before access, without replay.
     */
    default List<StorageContext> storageContexts(RecipeProcess process, RecipeRequirement<C> requirement) { return List.of(); }

    default RequirementTransaction start(RecipeProcess process, RecipeRequirement<C> requirement) { return RequirementTransaction.SUCCESS; }
    default RequirementTransaction tick(RecipeProcess process, RecipeRequirement<C> requirement) { return RequirementTransaction.SUCCESS; }
    default RequirementTransaction end(RecipeProcess process, RecipeRequirement<C> requirement) { return RequirementTransaction.SUCCESS; }

    /**
     * Releases this requirement's lifetime effects after business failure or explicit cancellation.
     * May run before START; consult owned state rather than assuming acquisition occurred. No END or
     * historical IO refund. Wrappers propagate only to active children, preserving their full path.
     * This terminal cleanup cannot block or return a transaction; faults are aggregated and fatal.
     */
    default void cancel(RecipeProcess process, RecipeRequirement<C> requirement) { }
}
