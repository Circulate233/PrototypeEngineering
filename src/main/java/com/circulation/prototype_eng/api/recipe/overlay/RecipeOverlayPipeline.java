package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;

/**
 * Explicit host entry point; process creation never invokes this pipeline automatically. Acquisition
 * applies descriptors in order, including repeated descriptors, and returns their local undo boundary.
 */
public final class RecipeOverlayPipeline {
    private RecipeOverlayPipeline() { }

    /** Include this transaction in the host's stage when its writes must remain compensatable. */
    public static RequirementTransaction acquire(RecipeProcess process, List<RecipeOverlayModifierDescriptor> descriptors) {
        Objects.requireNonNull(process);
        var instructions = new ObjectArrayList<>(descriptors);
        for (var descriptor : instructions) { Objects.requireNonNull(descriptor); }
        if (instructions.isEmpty()) { return RequirementTransaction.SUCCESS; }
        var transaction = new OverlayTransaction();
        try {
            for (int ordinal = 0; ordinal < instructions.size(); ordinal++) {
                var descriptor = instructions.get(ordinal);
                var context = new RecipeOverlayContext(process, descriptor.id(), ordinal, transaction.changes);
                try {
                    descriptor.modifier().apply(context);
                } finally {
                    context.close();
                }
            }
            return transaction;
        } catch (RuntimeException | Error failure) {
            try {
                transaction.rollback();
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /** Applies persistent layers now and relinquishes undo; callers choose the invocation timing. */
    public static void apply(RecipeProcess process, List<RecipeOverlayModifierDescriptor> descriptors) {
        var transaction = acquire(process, descriptors);
        transaction.commit();
        transaction.complete();
    }

    private static final class OverlayTransaction implements RequirementTransaction {
        private final ObjectArrayList<RecipeOverlays.Change> changes = new ObjectArrayList<>();
        private boolean finished;

        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public void commit() { }
        @Override public void complete() { finish(false); }
        @Override public void rollback() { finish(true); }

        private void finish(boolean undo) {
            if (finished) { return; }
            Throwable failure = null;
            for (int index = changes.size() - 1; index >= 0; index--) {
                try {
                    if (undo) { changes.get(index).rollback(); }
                    else { changes.get(index).complete(); }
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) { failure = cleanup; }
                    else if (failure != cleanup) { failure.addSuppressed(cleanup); }
                }
            }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
            finished = true;
            changes.clear();
        }
    }
}
