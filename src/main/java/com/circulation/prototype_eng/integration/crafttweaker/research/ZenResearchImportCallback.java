package com.circulation.prototype_eng.integration.crafttweaker.research;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/**
 * Side-effect-free item import evaluator. Null leaves the input and database unchanged.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchImportCallback")
@SuppressWarnings("unused")
@FunctionalInterface
public interface ZenResearchImportCallback {
    ZenResearchImportResult createOutput(ZenResearchImportContext context);
}
