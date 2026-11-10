package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementAttributeState;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * START acquisition is visible to later requirements in the same ordered stage, as in PM.
 * END removes modifiers only at completion so final output checks retain the effective values.
 * Compensation touches only this requirement's modifier, never another requirement's bases or effects.
 */
public final class AttributeRequirementSystem implements RecipeRequirementSystem<AttributeRequirement> {
    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<AttributeRequirement> requirement) {
        if (!requirement.payload().applyAtStart()) {
            return RequirementTransaction.SUCCESS;
        }
        var state = RequirementAttributeState.getOrCreate(process);
        var payload = requirement.payload();
        return new StartChange(state, state.install(process.requirementPath(), payload.attribute(), payload.modifier()));
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<AttributeRequirement> requirement) {
        if (!requirement.payload().removeAtEnd()) {
            return RequirementTransaction.SUCCESS;
        }
        var state = process.components().get(RequirementAttributeState.TYPE);
        return state == null ? RequirementTransaction.SUCCESS : new EndChange(state, state.ownedAt(process.requirementPath()));
    }

    private record StartChange(RequirementAttributeState state,
                               @Nullable RequirementAttributeState.Installation installation)
        implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
            if (installation != null) {
                state.remove(installation);
            }
        }
    }

    private record EndChange(RequirementAttributeState state,
                             List<RequirementAttributeState.Installation> installations)
        implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }

        @Override
        public void complete() {
            Throwable failure = null;
            for (int index = installations.size() - 1; index >= 0; index--) {
                try {
                    state.remove(installations.get(index));
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) {
                        failure = cleanup;
                    } else if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
