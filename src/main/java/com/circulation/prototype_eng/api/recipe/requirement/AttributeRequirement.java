package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import net.minecraft.util.ResourceLocation;


/**
 * PM's configurable START application and END removal of a process-local modifier.
 */
public record AttributeRequirement(ResourceLocation attribute, AttributeModifier modifier,
                                   boolean applyAtStart, boolean removeAtEnd) {

    public AttributeRequirement(ResourceLocation attribute, AttributeModifier modifier) {
        this(attribute, modifier, true, true);
    }
}
