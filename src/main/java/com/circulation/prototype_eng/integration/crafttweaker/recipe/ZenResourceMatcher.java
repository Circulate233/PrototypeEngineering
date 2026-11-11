package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Pure matcher SAM. Captured script state must remain stable for its bound recipe revision. */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.ResourceMatcher")
@FunctionalInterface
public interface ZenResourceMatcher {
    @ZenMethod
    boolean matches(ZenResource candidate, ZenResource pattern);
}
