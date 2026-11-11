package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.item.ItemStack;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchDownloads")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchDownloads {
 private ZenResearchDownloads() {}
 @ZenMethod public static void register(IItemStack material,ZenResearchDownloadCallback callback) {
  var key=CraftTweakerMC.getItemStack(Objects.requireNonNull(material)).copy(); Objects.requireNonNull(callback);
  DefinitionRegistrations.downloads(event -> event.registry().register(key, context -> {
   var output=callback.createOutput(new ZenResearchDownloadContext(context));
   return output==null ? ItemStack.EMPTY : CraftTweakerMC.getItemStack(output).copy();
  }));
 }
}
