package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.recipe.process.ProcessAbortListener;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;

/** One terminal cleanup wave; no participant failure may skip later owned effects. */
public final class RecipeAbortCleanup {
    private RecipeAbortCleanup() { }

    public static void run(RecipeProcess process) {
        if (process.state().lifecycle() != RecipeProcess.Lifecycle.FAILED && process.state().lifecycle() != RecipeProcess.Lifecycle.CANCELLED) {
            throw new IllegalArgumentException("Abort cleanup requires a failed or cancelled process");
        }
        // Capture before any cleanup callback mutates component assembly.
        var bindings = process.components().orderedBindings();
        var requirements = process.recipe().orderedRequirements();
        Throwable failure = null;
        for (int i = requirements.size() - 1; i >= 0; i--) {
            try {
                requirements.get(i).cancel(process);
            } catch (RuntimeException | Error cleanup) {
                failure = accumulate(failure, cleanup);
            }
        }
        for (int i = bindings.size() - 1; i >= 0; i--) {
            if (bindings.get(i).component() instanceof ProcessAbortListener listener) {
                try {
                    listener.onAborted(process);
                } catch (RuntimeException | Error cleanup) {
                    failure = accumulate(failure, cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private static Throwable accumulate(Throwable primary, Throwable cleanup) {
        if (primary == null) { return cleanup; }
        if (primary != cleanup) { primary.addSuppressed(cleanup); }
        return primary;
    }
}
