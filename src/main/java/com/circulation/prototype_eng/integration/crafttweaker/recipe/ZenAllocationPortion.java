package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;


/**
 * Existing Java portion value; validation and source acquisition belong to InputAllocation.
 */
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.AllocationPortion")
public record ZenAllocationPortion(InputAllocation.Portion value) {

    @ZenMethod
    public static ZenAllocationPortion create(int source, long amount) {
        return new ZenAllocationPortion(new InputAllocation.Portion(source, amount));
    }
}
