package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import net.minecraft.util.ResourceLocation;

/**
 * START acquisition is visible to later requirements in the same ordered stage, as in PM.
 * END removes modifiers only at completion so final output checks retain the effective values.
 * Compensation touches only this requirement's modifier, never another requirement's bases or effects.
 */
public final class AttributeRequirementSystem implements RecipeRequirementSystem<AttributeRequirement> {
    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<AttributeRequirement> requirement) {
        return requirement.payload().applyAtStart() ? acquire(process, requirement, true) : RequirementTransaction.SUCCESS;
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<AttributeRequirement> requirement) {
        return requirement.payload().removeAtEnd() ? acquire(process, requirement, false) : RequirementTransaction.SUCCESS;
    }

    private static RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<AttributeRequirement> requirement,
                                                   boolean apply) {
        var attributes = process.attributes();
        var payload = requirement.payload();
        String modifierId = "req:" + process.requirementPath() + ":" + payload.attribute();
        boolean added = apply && attributes.addModifier(payload.attribute(), new RequirementModifier(modifierId, payload.modifier()));
        return new AttributeChange(process, payload.attribute(), modifierId, apply, added);
    }

    private record AttributeChange(RecipeProcess process, ResourceLocation attribute, String modifierId,
                                   boolean start, boolean added)
        implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
        }

        @Override
        public void complete() {
            if (!start) {
                // Infallible lifecycle cleanup, only after every business application has succeeded.
                process.attributes().removeModifier(attribute, modifierId);
            }
        }

        @Override
        public void rollback() {
            if (added) {
                process.attributes().removeModifier(attribute, modifierId);
            }
        }
    }

    /** Preserve external modifier behavior while assigning the requirement's stable internal id. */
    private record RequirementModifier(String id, AttributeModifier delegate) implements AttributeModifier {
        @Override
        public Operation operation() {
            return delegate.operation();
        }

        @Override
        public double apply(double base, double current) {
            return delegate.apply(base, current);
        }
    }
}
