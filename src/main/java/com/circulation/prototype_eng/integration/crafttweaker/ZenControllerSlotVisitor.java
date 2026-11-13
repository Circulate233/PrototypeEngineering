package com.circulation.prototype_eng.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import stanhebben.zenscript.annotations.ZenClass;

/**
 * Receives available controller slots in index order.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.ControllerSlotVisitor")
@FunctionalInterface
public interface ZenControllerSlotVisitor {
    void visit(int slot, IItemStack stack);
}
