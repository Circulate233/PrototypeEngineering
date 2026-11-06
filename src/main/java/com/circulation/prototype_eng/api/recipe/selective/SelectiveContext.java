package com.circulation.prototype_eng.api.recipe.selective;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import net.minecraft.util.ResourceLocation;

/**
 * One modifier application's real process context. Mutating operations are valid only during apply.
 * Attribute effects intended for rollback or eventual persistence must go through the tracked put;
 * direct writes to other state through process()/machine() are not automatically compensated.
 */
public interface SelectiveContext {
    MachineInstance machine();
    RecipeProcess process();
    RequirementPath selectionPath();
    int candidateIndex();
    ResourceLocation modifierId();
    int modifierOrdinal();

    default String selectionId() {
        return selectionPath().toString();
    }

    /**
     * Adds a namespaced local modifier, or replaces a modifier already owned by this application.
     * Unknown existing local IDs are rejected. Compensation removes only this selection's additions.
     */
    void putProcessAttributeModifier(ResourceLocation attribute, AttributeModifier modifier);
}
