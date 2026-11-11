package com.circulation.prototype_eng.integration.crafttweaker.structure;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/** Pure callback over detached facts. It may be called concurrently for separate controllers. */
@ZenRegister
@ZenClass("mods.prototype_eng.structure.Validator")
@FunctionalInterface
public interface ZenStructureValidator {
    boolean validate(ZenStructureFacts facts);
}
