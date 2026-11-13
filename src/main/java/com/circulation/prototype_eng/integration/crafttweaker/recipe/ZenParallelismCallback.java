package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/**
 * Pure selection from the base suggestion; positive final quantities follow normal admission costs.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.ParallelismCallback")
@FunctionalInterface
public interface ZenParallelismCallback {
    int select(int suggested);
}
