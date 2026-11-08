package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementContinuation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import java.util.function.Consumer;

/**
 * Keeps pure work at the original ordered continuation boundary, under its captured path/tick.
 */
public class PreparedRequirement implements RequirementTransaction, RequirementContinuation {
    @Nullable
    private final RecipePureComputation computation;
    private final Supplier<RequirementTransaction> acquire;
    private final Runnable retainFacts;
    private final Consumer<RecipePureComputation> withdrawFacts;
    private final boolean ownerWorkMetered;
    private final boolean captureOnlyPreparation;
    private boolean entered;
    private Resolution terminal = Resolution.REVERSIBLE;

    public PreparedRequirement(RequirementPreparation binding) {
        Objects.requireNonNull(binding);
        computation = binding.computation();
        acquire = binding::acquire;
        retainFacts = binding::retainPureFacts;
        withdrawFacts = binding::withdrawPureFacts;
        ownerWorkMetered = binding.ownerWorkMetered();
        captureOnlyPreparation = binding.captureOnlyPreparation();
    }

    /**
     * Opaque callbacks are deferred at the same cursor, and only the owner invokes them once.
     */
    public PreparedRequirement(Supplier<RequirementTransaction> acquire) {
        this.acquire = Objects.requireNonNull(acquire);
        computation = null;
        retainFacts = () -> {
        };
        withdrawFacts = _ -> {
        };
        ownerWorkMetered = false;
        captureOnlyPreparation = false;
    }

    @Override
    public boolean ownerWorkMetered() {
        // The entered wrapper's terminal null step does no binding work and consumes no owner
        // budget itself. The framework must charge that close rather than drain a deep stack free.
        return !entered && ownerWorkMetered;
    }

    @Override
    public boolean captureOnlyPreparation() {
        return !entered && captureOnlyPreparation;
    }

    @Override
    public void retainPureFacts() {
        retainFacts.run();
    }

    @Override
    public void withdrawPureFacts(RecipePureComputation expected) {
        withdrawFacts.accept(expected);
    }

    @Override
    public boolean preparationPending() {
        return true;
    }

    @Override
    public ProcessResult result() {
        return ProcessResult.SUCCESS;
    }

    @Override
    public RequirementContinuation continuation() {
        return this;
    }

    @Override
    public @Nullable RecipePureComputation computation() {
        return entered ? null : computation;
    }

    @Override
    public @Nullable RequirementTransaction acquireNext() {
        if (terminal != Resolution.REVERSIBLE) {
            throw new IllegalStateException("Prepared requirement is terminal");
        }
        if (entered) {
            return null;
        }
        entered = true;
        return Objects.requireNonNull(acquire.get());
    }

    @Override
    public void commit() {
    }

    @Override
    public void complete() {
        terminal = Resolution.COMPLETED;
    }

    @Override
    public void rollback() {
        terminal = Resolution.ROLLED_BACK;
    }

    @Override
    public Resolution resolution() {
        return terminal;
    }

    @Override public boolean completeWithinBudget(RecipeWorkBudget work) {
        if (!work.take()) return false;
        terminal = Resolution.COMPLETED;
        return true;
    }
    @Override public boolean rollbackWithinBudget(RecipeWorkBudget work) {
        if (!work.take()) return false;
        terminal = Resolution.ROLLED_BACK;
        return true;
    }
}
