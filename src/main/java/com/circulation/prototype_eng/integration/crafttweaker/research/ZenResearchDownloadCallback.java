package com.circulation.prototype_eng.integration.crafttweaker.research;

import crafttweaker.api.item.IItemStack;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

/** Pure candidate output; return null to refuse. The existing owner performs the transaction. */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchDownloadCallback")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
@FunctionalInterface
public interface ZenResearchDownloadCallback { IItemStack createOutput(ZenResearchDownloadContext context); }
