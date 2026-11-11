package com.circulation.prototype_eng.integration.crafttweaker.research;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchStartCondition")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
@FunctionalInterface
public interface ZenResearchStartCondition { boolean canStart(ZenResearchStartContext context); }
