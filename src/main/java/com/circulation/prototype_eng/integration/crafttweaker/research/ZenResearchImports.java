package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchImports")
@SuppressWarnings("unused")
public final class ZenResearchImports {
    private ZenResearchImports() {
    }

    @ZenMethod
    public static void register(String id, IItemStack material, String research, int levels, IItemStack output) {
        DefinitionRegistrations.downloads(event -> event.imports().register(new ResourceLocation(id),
            CraftTweakerMC.getItemStack(material), new ResourceLocation(research), levels, CraftTweakerMC.getItemStack(output)));
    }

    @ZenMethod
    public static void register(String id, IItemStack material, String research, int levels, IItemStack output,
                                ZenResearchImportCallback callback) {
        DefinitionRegistrations.downloads(event -> event.imports().register(new ResourceLocation(id),
            CraftTweakerMC.getItemStack(material), new ResourceLocation(research), levels, CraftTweakerMC.getItemStack(output), context -> {
                var result = callback.createOutput(new ZenResearchImportContext(context));
                return result == null ? null : result.evaluate(context.registry());
            }));
    }

}
