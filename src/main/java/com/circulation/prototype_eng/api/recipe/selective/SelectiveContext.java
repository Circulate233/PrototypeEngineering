package com.circulation.prototype_eng.api.recipe.selective;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import net.minecraft.util.ResourceLocation;

/**
 * One modifier application's real process context. Mutating operations are valid only during apply.
 * Attribute and requirement overlay effects intended for rollback must go through the tracked puts;
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

    /**
     * Adds to process speed through this application's tracked local ownership.
     */
    default void addProcessSpeed(String modifierId, double amount) {
        putProcessAttributeModifier(StandardMachineAttributes.PROCESS_SPEED, SelectiveModifiers.speedAddition(modifierId, amount));
    }

    /**
     * Multiplies current process speed by the factor (e.g. 0.8 slower, 1.2 faster).
     * Zero and negative factors are allowed; the process clamps tick progress to nonnegative speed.
     */
    default void mulProcessSpeed(String modifierId, double factor) {
        putProcessAttributeModifier(StandardMachineAttributes.PROCESS_SPEED, SelectiveModifiers.speedMultiplier(modifierId, factor));
    }

    /**
     * Root target shorthand; only this selection's layer writes are undone by its cleanup.
     */
    default <C, D> void putRequirementOverlay(RecipeRequirement<C> target, String layerId, RecipeOverlayType<C, D> type, D data) {
        putRequirementOverlay(target, target.path(null), layerId, type, data);
    }

    /**
     * Exact nested target path; writes remain effective through END commit and clear at complete.
     */
    <C, D> void putRequirementOverlay(RecipeRequirement<C> target, RequirementPath path, String layerId,
                                      RecipeOverlayType<C, D> type, D data);
}
