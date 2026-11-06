package com.circulation.prototype_eng.api.recipe.process;

/**
 * Optional lifecycle responsibility implemented by a process component instance. Called once after
 * requirement cancellation, in reverse component dependency order, for business FAILED or explicit
 * CANCELLED. The final state is already visible. Release only this component's owned lifetime effects;
 * do not execute END, refund committed history, create new lifetime effects or wait for another lane.
 * Every captured listener is attempted even when another throws. A fault forbids saving or resuming
 * the process. Restoring an aborted process must not reacquire effects or replay this callback.
 */
@FunctionalInterface
public interface ProcessAbortListener {
    void onAborted(RecipeProcess process);
}
