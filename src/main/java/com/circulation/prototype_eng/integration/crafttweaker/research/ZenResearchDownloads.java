package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchDownloads")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchDownloads {
    private ZenResearchDownloads() {
    }

    /**
     * Registration items and callback outputs are borrowed read-only.
     */
    @ZenMethod
    public static void register(IItemStack material, ZenResearchDownloadCallback callback) {
        var key = CraftTweakerMC.getItemStack(material);
        DefinitionRegistrations.downloads(event -> event.registry().register(key, context -> {
            var output = callback.createOutput(new ZenResearchDownloadContext(context));
            return output == null ? ItemStack.EMPTY : CraftTweakerMC.getItemStack(output);
        }));
    }
}
