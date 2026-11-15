package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCarrier;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Loading-thread import declarations and indexed owner-thread evaluation. Lists and templates are
 * retained read-only after freeze; no item or research is changed while choosing a recipe.
 */
public final class ResearchItemImportRegistry {
    public static final ResearchItemImportRegistry INSTANCE = new ResearchItemImportRegistry();
    private final ObjectArrayList<ResearchItemImportRecipe> recipes = new ObjectArrayList<>();
    private final ObjectOpenHashSet<ResourceLocation> ids = new ObjectOpenHashSet<>();
    private final Object2ObjectOpenHashMap<AEItemKey, ResearchItemImportRecipe> exact = new Object2ObjectOpenHashMap<>();
    private boolean frozen;

    public ObjectList<ResearchItemImportRecipe> recipes() {
        return recipes;
    }

    public void register(ResourceLocation id, ItemStack material, ResourceLocation researchId, int researchLevel,
                         ItemStack outputTemplate) {
        register(id, material, researchId, researchLevel, outputTemplate, context -> {
            var registry = context.registry();
            int tier = registry.require(researchId).researchTierForLevel(researchLevel);
            return new ResearchItemImportEvaluation(new ResearchDiskData(registry, tier,
                Object2IntMaps.singleton(researchId, researchLevel)), outputTemplate);
        });
    }

    public void register(ResourceLocation id, ItemStack material, ResourceLocation researchId, int researchLevel,
                         ItemStack outputTemplate, ResearchItemImportEvent event) {
        add(new ResearchItemImportRecipe(id, key(material), researchId, researchLevel, outputTemplate, event));
    }

    private void add(ResearchItemImportRecipe recipe) {
        if (frozen) throw new IllegalStateException("Research item import registration has ended");
        if (recipe.researchLevel() < 1 || recipe.outputTemplate().isEmpty()
            || recipe.outputTemplate().getCount() > Math.min(64, recipe.outputTemplate().getMaxStackSize()))
            throw new IllegalArgumentException("Research import must return one valid output stack");
        if (!ids.add(recipe.id())) throw new IllegalArgumentException("Duplicate research item import id");
        var previous = exact.putIfAbsent(recipe.material(), recipe);
        if (previous != null) throw new IllegalArgumentException("Research import input already has a conversion");
        recipes.add(recipe);
    }

    private static AEItemKey key(ItemStack material) {
        var key = AEItemKey.of(material);
        if (key == null) throw new IllegalArgumentException("Research import input must be nonempty");
        return key;
    }

    public void freeze(ResearchRegistry registry) {
        if (frozen || !registry.isFrozen())
            throw new IllegalStateException("Research import freeze requires frozen research");
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            registry.require(recipe.researchId()).stageForLevel(recipe.researchLevel());
        }
        frozen = true;
    }

    public @Nullable ResearchItemImportEvaluation evaluate(ResearchItemImportContext context) {
        if (!frozen) throw new IllegalStateException("Research item import registry is not ready");
        var material = context.material();
        var recipe = exact.get(AEItemKey.of(material));
        if (recipe != null) return recipe.event().createOutput(context);
        if (!(material.getItem() instanceof ResearchDiskCarrier carrier)) return null;
        var data = carrier.readResearchDisk(material, context.registry());
        if (data == null) return null;
        if (data.tier() != carrier.researchDiskTier(material))
            throw new IllegalArgumentException("Research disk payload tier differs from its physical item");
        var entries = new Object2IntOpenHashMap<ResourceLocation>(data.entries().size());
        for (var researchId : data.entries().keySet()) entries.put(researchId, 1);
        return new ResearchItemImportEvaluation(new ResearchDiskData(context.registry(), data.tier(), entries),
            carrier.emptyResearchDisk(material));
    }
}
