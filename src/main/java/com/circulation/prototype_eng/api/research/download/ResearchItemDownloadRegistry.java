package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCarrier;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;


/**
 * Loading-thread exact material registration, atomically frozen before runtime download evaluation.
 */
@SuppressWarnings("unused") // Addons register keys; server consumers evaluate the frozen registry.
public final class ResearchItemDownloadRegistry {
    public static final ResearchItemDownloadRegistry INSTANCE = new ResearchItemDownloadRegistry();
    private final Object2ObjectOpenHashMap<AEItemKey, ObjectList<ResearchItemDownloadRecipe>> registrations = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<ResourceLocation, ResearchItemDownloadRecipe> ids = new Object2ObjectOpenHashMap<>();
    private final ObjectArrayList<ResearchItemDownloadRecipe> recipes = new ObjectArrayList<>();
    @Nullable
    private volatile Frozen frozen;

    /**
     * Full item, metadata and NBT identity. Keys are immutable and retained directly.
     */
    public void register(ResearchItemDownloadRecipe recipe) {
        requireLoading();
        if (ids.containsKey(recipe.id())) {
            throw new IllegalArgumentException("Duplicate research item download recipe: " + recipe.id());
        }
        var candidates = registrations.computeIfAbsent(recipe.material(), _ -> new ObjectArrayList<>());
        for (int index = 0; index < candidates.size(); index++) {
            if (Objects.equals(candidates.get(index).researchId(), recipe.researchId()))
                throw new IllegalArgumentException("Duplicate research download material and research");
        }
        ids.put(recipe.id(), recipe);
        candidates.add(recipe);
        recipes.add(recipe);
    }

    /**
     * Stack count does not form part of registration identity.
     */
    public void register(ResourceLocation id, ItemStack material, @Nullable ResourceLocation researchId,
                         int researchLevel, int levelCost, ItemStack outputTemplate, ResearchItemDownloadEvent event) {
        var key = AEItemKey.of(material);
        if (key == null) throw new IllegalArgumentException("Download material must be nonempty");
        register(new ResearchItemDownloadRecipe(id, key, researchId, researchLevel, levelCost, outputTemplate, event));
    }

    public void register(ResourceLocation id, ItemStack material, ResourceLocation researchId,
                         int researchLevel, int levelCost, ItemStack outputTemplate) {
        register(id, material, researchId, researchLevel, levelCost, outputTemplate, _ -> outputTemplate);
    }

    public boolean isFrozen() {
        return frozen != null;
    }

    public void freeze(ResearchRegistry research) {
        requireLoading();
        if (!research.isFrozen()) {
            throw new IllegalStateException("Research must freeze before item download events");
        }
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            if (recipe.researchId() != null)
                research.require(recipe.researchId()).stageForLevel(recipe.researchLevel());
        }
        frozen = new Frozen(registrations, research);
    }

    /**
     * Frozen definitions borrowed read-only, with constant-time indexed access.
     */
    public ObjectList<ResearchItemDownloadRecipe> recipes() {
        requireFrozen();
        return recipes;
    }

    @Nullable
    public ResearchItemDownloadRecipe find(AEItemKey material, ResourceLocation researchId) {
        var candidates = requireFrozen().events.get(material);
        if (candidates == null) return null;
        ResearchItemDownloadRecipe general = null;
        for (int index = 0; index < candidates.size(); index++) {
            var recipe = candidates.get(index);
            if (researchId.equals(recipe.researchId())) return recipe;
            if (recipe.researchId() == null) general = recipe;
        }
        return general;
    }

    /**
     * Invokes one matching callback on the server owner thread. The context carries the matched
     * recipe's fixed level; failures propagate and successful outputs remain borrowed read-only.
     */
    public ResearchItemDownloadEvaluation evaluate(ResearchItemDownloadContext context) {
        var recipe = find(context.materialKey(), context.research().id());
        return evaluate(recipe, context);
    }

    /**
     * Uses an already selected definition; null selects the built-in blank-disk conversion.
     */
    public ResearchItemDownloadEvaluation evaluate(@Nullable ResearchItemDownloadRecipe recipe, ResearchItemDownloadContext context) {
        if (recipe == null) {
            var material = context.material();
            if (!(material.getItem() instanceof ResearchDiskCarrier carrier))
                return ResearchItemDownloadEvaluation.noEvent();
            var definition = context.research();
            int tier = carrier.researchDiskTier(material);
            if (carrier.hasResearchDiskData(material) || definition.specialOnly() || definition.researchTierForLevel(1) > tier)
                return ResearchItemDownloadEvaluation.rejected();
            var data = new ResearchDiskData(requireFrozen().research, tier, Object2IntMaps.singleton(definition.id(), 1));
            return ResearchItemDownloadEvaluation.success(carrier.writeResearchDisk(material, data), 1, 1);
        }
        var output = recipe.event().createOutput(context);
        if (output.isEmpty()) {
            return ResearchItemDownloadEvaluation.rejected();
        }
        return ResearchItemDownloadEvaluation.success(output, recipe.researchLevel(), recipe.levelCost());
    }

    private void requireLoading() {
        if (frozen != null) {
            throw new IllegalStateException("Research item download registration has ended");
        }
    }

    private Frozen requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Research item download registry is not ready");
        }
        return current;
    }

    private record Frozen(Object2ObjectOpenHashMap<AEItemKey, ObjectList<ResearchItemDownloadRecipe>> events,
                          ResearchRegistry research) {
    }
}
