package com.circulation.prototype_eng.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/**
 * Returns the number of controller item slots available at the joined server boundary.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.ControllerInventorySize")
@FunctionalInterface
public interface ZenControllerInventorySize {
    int slots(ZenControllerContext controller);
}
