package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
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
    public static void register(String id, IItemStack material, String researchId, int researchLevel, int levelCost,
                                IItemStack outputTemplate, ZenResearchDownloadCallback callback) {
        var key = CraftTweakerMC.getItemStack(material);
        var template = CraftTweakerMC.getItemStack(outputTemplate);
        DefinitionRegistrations.downloads(event -> event.registry().register(new ResourceLocation(id), key,
            new ResourceLocation(researchId), researchLevel, levelCost, template, context -> {
                var output = callback.createOutput(new ZenResearchDownloadContext(context));
                return output == null ? ItemStack.EMPTY : CraftTweakerMC.getItemStack(output);
            }));
    }

    @ZenMethod
    public static void register(String id, IItemStack material, String researchId, int researchLevel, int levelCost,
                                IItemStack outputTemplate) {
        var key = CraftTweakerMC.getItemStack(material);
        var template = CraftTweakerMC.getItemStack(outputTemplate);
        DefinitionRegistrations.downloads(event -> event.registry().register(new ResourceLocation(id), key,
            new ResourceLocation(researchId), researchLevel, levelCost, template));
    }

}
