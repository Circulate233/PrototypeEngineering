package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/**
 * Pure callback over detached facts; probes and later attempts may invoke it again.
 * For an untyped CT 718 anonymous callback, return arrays as: return [portion] as AllocationPortion[];
 */
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.AllocationCallback")
@FunctionalInterface
public interface ZenAllocationCallback {
    ZenAllocationPortion[] allocate(ZenAllocationContext context);
}
