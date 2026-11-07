package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;

/**
 * A continuation that can reject one candidate without rejecting its enclosing stage. The executor
 * establishes a savepoint before a candidate starts, including all of that candidate's nested tails.
 * It calls rejectAlternative only after every candidate effect is confirmed rolled back. Neither
 * method may acquire resources or replay callbacks; the next acquireNext starts a new candidate.
 */
public interface RequirementAlternatives extends RequirementContinuation {
    /** Whether the next acquisition begins a new independently rejectable candidate. */
    boolean startsAlternative();

    /** Advances the cursor after the rejected candidate's exact compensation has completed. */
    void rejectAlternative(ProcessResult rejected);
}
