package com.circulation.prototype_eng.integration.crafttweaker.network;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/**
 * Called once per concrete recipe object by the Java extension publication, outside its monitor.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.network.NetworkRecipeCallback")
@SuppressWarnings("unused") // Script parameter conversion delegates to public Java contracts.
@FunctionalInterface
public interface ZenNetworkRecipeCallback {
    void extend(ZenNetworkRecipeContext recipe, ZenNetworkRequirements requirements);
}
