package com.circulation.prototype_eng.integration.crafttweaker.research;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/** Return the shared Java Decision name; throwing aborts the actual server attempt. */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchPermissionCallback")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
@FunctionalInterface
public interface ZenResearchPermissionCallback { String can(String action,ZenResearchPermissionContext context); }
