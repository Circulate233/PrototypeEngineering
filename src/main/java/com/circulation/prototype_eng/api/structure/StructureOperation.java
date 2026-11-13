package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One retained structure computation. The owner transfers it to at most one worker and reads its
 * result only after that worker physically exits. False means that this operation needs another
 * work slice; it does not authorize publication or renew a deadline. External atomic callbacks
 * remain cooperative and are invoked once after an available work unit.
 */
public abstract class StructureOperation<T> {
    private boolean complete;
    @Nullable
    private T value;

    /**
     * Existing external virtual operations keep their exact behavior at this cooperative boundary.
     */
    public static <T> StructureOperation<T> call(Supplier<? extends T> action) {
        Objects.requireNonNull(action);
        return new StructureOperation<>() {
            @Override
            public boolean advance(RecipeWorkBudget work) {
                Objects.requireNonNull(work);
                if (complete()) return true;
                if (!work.take()) return false;
                finish(action.get());
                return true;
            }
        };
    }

    public abstract boolean advance(RecipeWorkBudget work);

    public final boolean complete() {
        return complete;
    }

    @Nullable
    public final T result() {
        if (!complete) throw new IllegalStateException("Structure operation has not completed");
        return value;
    }

    protected final void finish(@Nullable T result) {
        if (complete) throw new IllegalStateException("Structure operation completed twice");
        value = result;
        complete = true;
    }

    /**
     * Synchronous convenience uses the same cursor, including the host's first complete check.
     */
    @Nullable
    public final T run() {
        if (!advance(new RecipeWorkBudget(Long.MAX_VALUE)))
            throw new IllegalStateException("Structure operation exceeds the synchronous work range");
        return result();
    }
}
