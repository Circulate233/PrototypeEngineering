package com.circulation.prototype_eng.integration.crafttweaker.research;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/** Must durably deduplicate the event key; return APPLIED, ALREADY_APPLIED or RETRY_LATER. */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchRewardCallback")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
@FunctionalInterface
public interface ZenResearchRewardCallback { String deliver(ZenResearchRewardEvent event); }
