package com.circulation.prototype_eng.api.research;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * A continuous level range with single-station work ticks and total material and network demands.
 * Returned collections belong to the definition and must not be modified.
 */
@SuppressWarnings("unused")
public final class ResearchLevelStage {
    private final int firstLevel;
    private final int lastLevel;
    private final int researchTier;
    private final long durationTicks;
    private final Object2IntMap<ResourceLocation> prerequisites;
    private final ObjectList<ResearchMutexRule> mutexRules;
    private final ResearchResourceFormula resourceFormula;
    private final ObjectList<RecipeRequirement<?>> materialRequirements;
    private final AEKey2LongMap materialTotals;
    private final Int2ObjectMap<ObjectList<RewardGroup>> rewardsByLevel;

    private ResearchLevelStage(Builder builder) {
        firstLevel = builder.firstLevel;
        lastLevel = builder.lastLevel;
        if (builder.researchTier < 0) {
            throw new IllegalArgumentException("Research stage must explicitly declare its tier");
        }
        researchTier = builder.researchTier;
        if (builder.durationTicks <= 0)
            throw new IllegalArgumentException("Research stage must declare positive duration ticks");
        durationTicks = builder.durationTicks;
        resourceFormula = Objects.requireNonNull(builder.resourceFormula, "Research stage formula");
        resourceFormula.validateRange(firstLevel, lastLevel, durationTicks);
        prerequisites = ResearchSnapshots.levels(builder.prerequisites);
        mutexRules = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.mutexRules));
        var materials = new ObjectArrayList<RecipeRequirement<?>>(builder.materialRequirements.size());
        var materialIds = new ObjectOpenHashSet<ResourceLocation>(builder.materialRequirements.size());
        for (var requirement : builder.materialRequirements) {
            if (!materialIds.add(requirement.id())) {
                throw new IllegalArgumentException("Duplicate research material requirement " + requirement.id());
            }
            materials.add(requirement);
        }
        materialRequirements = ObjectLists.unmodifiable(materials);
        materialTotals = compileMaterials();
        var rewards = new Int2ObjectOpenHashMap<ObjectList<RewardGroup>>(builder.rewardsByLevel.size());
        for (var entry : builder.rewardsByLevel.int2ObjectEntrySet()) {
            var ids = new ObjectOpenHashSet<ResourceLocation>(entry.getValue().size());
            for (var reward : entry.getValue()) {
                if (!ids.add(reward.id())) {
                    throw new IllegalArgumentException("Duplicate research reward group " + reward.id());
                }
            }
            rewards.put(entry.getIntKey(), ObjectLists.unmodifiable(new ObjectArrayList<>(entry.getValue())));
        }
        rewardsByLevel = ResearchSnapshots.sparse(rewards);
    }

    public static Builder builder(int firstLevel, int lastLevel) {
        return new Builder(firstLevel, lastLevel);
    }

    public int firstLevel() {
        return firstLevel;
    }

    public int lastLevel() {
        return lastLevel;
    }

    public int researchTier() {
        return researchTier;
    }

    public long durationTicks() {
        return durationTicks;
    }

    public AEKey2LongMap materialTotals() {
        return materialTotals;
    }

    public Object2IntMap<ResourceLocation> prerequisites() {
        return prerequisites;
    }

    public ObjectList<ResearchMutexRule> mutexRules() {
        return mutexRules;
    }

    public ResearchResourceFormula resourceFormula() {
        return resourceFormula;
    }

    public Reference2DoubleMap<NetworkResourceType> resourceDemandForLevel(int level) {
        validateLevel(level);
        return resourceFormula.demandsAt(firstLevel, level, durationTicks);
    }

    public Reference2DoubleMap<NetworkResourceType> resourceLimitsForLevel(int level) {
        var limits = resourceDemandForLevel(level);
        for (var entry : limits.reference2DoubleEntrySet()) entry.setValue(entry.getDoubleValue() / durationTicks);
        return limits;
    }

    public ObjectList<RecipeRequirement<?>> materialRequirements() {
        return materialRequirements;
    }

    /**
     * Sparse exact-level choices, including rewards on an Integer.MAX_VALUE tail.
     */
    public Int2ObjectMap<ObjectList<RewardGroup>> rewardGroupsByLevel() {
        return rewardsByLevel;
    }

    public ObjectList<RewardGroup> rewardsForLevel(int level) {
        validateLevel(level);
        return rewardsByLevel.getOrDefault(level, ObjectLists.emptyList());
    }

    private void validateLevel(int level) {
        if (level < firstLevel || level > lastLevel) {
            throw new IllegalArgumentException("Research level outside stage " + firstLevel + ".." + lastLevel);
        }
    }

    private AEKey2LongMap compileMaterials() {
        if (researchTier == 0 && !resourceFormula.isEmpty()) {
            throw new IllegalArgumentException("Tier-zero research cannot declare numeric resources");
        }
        var totals = ImmutableResourceAmounts.builder();
        for (var requirement : materialRequirements) {
            if (requirement.type() != RecipeRequirements.RESOURCES
                || !(requirement.payload() instanceof ResourceRequirement(
                var inputs, var inputsPerTick, var outputsPerTick,
                var outputs, var voidExcess, var chance, var randomOutputs, var fuzzyInputs, var dynamicInputs,
                var inputAllocation
            ))
                || !inputsPerTick.isEmpty() || !outputsPerTick.isEmpty() || !outputs.isEmpty()
                || !chance.equals(ResourceChance.ALWAYS) || randomOutputs != null
                || !fuzzyInputs.isEmpty() || !dynamicInputs.isEmpty() || voidExcess
                || inputAllocation != InputAllocation.SPLITTABLE) {
                throw new IllegalArgumentException("Research supports only static total resource inputs: " + requirement.id());
            }
            for (var input : inputs) {
                if (researchTier == 0 && !(input.what() instanceof AEItemKey))
                    throw new IllegalArgumentException("Tier-zero research requires item inputs: " + requirement.id());
                totals.addTo(input.what(), input.amount());
            }
        }
        return totals.build();
    }

    public static final class Builder {
        private final int firstLevel;
        private final int lastLevel;
        private final Object2IntOpenHashMap<ResourceLocation> prerequisites = new Object2IntOpenHashMap<>();
        private final ObjectArrayList<ResearchMutexRule> mutexRules = new ObjectArrayList<>();
        private final ObjectArrayList<RecipeRequirement<?>> materialRequirements = new ObjectArrayList<>();
        private final Int2ObjectOpenHashMap<ObjectArrayList<RewardGroup>> rewardsByLevel = new Int2ObjectOpenHashMap<>();
        private int researchTier = -1;
        private long durationTicks;
        private ResearchResourceFormula resourceFormula;

        private Builder(int firstLevel, int lastLevel) {
            if (firstLevel < 1 || lastLevel < firstLevel) {
                throw new IllegalArgumentException("Invalid research stage range");
            }
            this.firstLevel = firstLevel;
            this.lastLevel = lastLevel;
        }

        public Builder researchTier(int tier) {
            if (tier < 0) {
                throw new IllegalArgumentException("Negative research tier");
            }
            researchTier = tier;
            return this;
        }

        public Builder durationTicks(long ticks) {
            if (ticks <= 0) throw new IllegalArgumentException("Research duration must be positive");
            durationTicks = ticks;
            return this;
        }

        public Builder prerequisite(ResourceLocation researchId, int minLevel) {
            Objects.requireNonNull(researchId);
            if (minLevel < 1 || prerequisites.containsKey(researchId)) {
                throw new IllegalArgumentException("Invalid or duplicate research prerequisite " + researchId);
            }
            prerequisites.put(researchId, minLevel);
            return this;
        }

        public Builder mutex(ResourceLocation researchId, int minLevel) {
            var rule = new ResearchMutexRule(researchId, minLevel);
            for (var existing : mutexRules) {
                if (existing.researchId().equals(researchId)) {
                    throw new IllegalArgumentException("Duplicate research mutex " + researchId);
                }
            }
            mutexRules.add(rule);
            return this;
        }

        public Builder resourceFormula(ResearchResourceFormula formula) {
            resourceFormula = Objects.requireNonNull(formula);
            return this;
        }

        public Builder materialRequirement(RecipeRequirement<?> requirement) {
            materialRequirements.add(Objects.requireNonNull(requirement));
            return this;
        }

        public Builder material(AEKey key, long total) {
            var id = new ResourceLocation("prototype_eng", "research_material_" + materialRequirements.size());
            return materialRequirement(RecipeRequirements.RESOURCES.requirement(id,
                new ResourceRequirement(List.of(new GenericStack(key, total)), List.of())));
        }

        public Builder rewardGroup(int level, RewardGroup reward) {
            if (level < firstLevel || level > lastLevel) {
                throw new IllegalArgumentException("Reward level outside research stage");
            }
            rewardsByLevel.computeIfAbsent(level, _ -> new ObjectArrayList<>()).add(Objects.requireNonNull(reward));
            return this;
        }

        public ResearchLevelStage build() {
            return new ResearchLevelStage(this);
        }
    }
}
