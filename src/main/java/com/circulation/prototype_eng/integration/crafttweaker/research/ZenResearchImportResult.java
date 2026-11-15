package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportEvaluation;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/**
 * Callback-owned additive research and return item. Stop modifying the result once returned.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchImportResult")
@SuppressWarnings("unused")
public final class ZenResearchImportResult {
    private final IItemStack output;
    private final int tier;
    private final Object2IntOpenHashMap<ResourceLocation> entries = new Object2IntOpenHashMap<>();

    private ZenResearchImportResult(IItemStack output, int tier) {
        this.output = output;
        this.tier = tier;
    }

    @ZenMethod
    public static ZenResearchImportResult create(IItemStack output, int tier) {
        return new ZenResearchImportResult(output, tier);
    }

    @ZenMethod
    public ZenResearchImportResult research(String id, int levels) {
        var key = new ResourceLocation(id);
        int previous = entries.getInt(key);
        if (levels < 1 || previous > Integer.MAX_VALUE - levels)
            throw new IllegalArgumentException("Invalid additive research level");
        entries.addTo(key, levels);
        return this;
    }

    ResearchItemImportEvaluation evaluate(ResearchRegistry registry) {
        return new ResearchItemImportEvaluation(new ResearchDiskData(registry, tier, entries), CraftTweakerMC.getItemStack(output));
    }
}
