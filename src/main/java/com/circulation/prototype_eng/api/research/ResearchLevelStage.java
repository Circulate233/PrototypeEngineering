package com.circulation.prototype_eng.api.research;

import java.util.List;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * PN's inclusive level range, fixed prerequisites/materials and sparse exact-level reward choices.
 * Material definitions retain complete requirement payloads and must remain immutable, as for recipes.
 */
@SuppressWarnings("unused")
public final class ResearchLevelStage {
    private final int firstLevel;
    private final int lastLevel;
    private final int researchTier;
    private final Object2IntMap<ResourceLocation> prerequisites;
    private final ObjectList<ResearchMutexRule> mutexRules;
    private final ResearchResourceFormula resourceFormula;
    private final ObjectList<RecipeRequirement<?>> materialRequirements;
    private final String materialRevision;
    private final Consumer<RecipeProcessNbt.Builder> materialConfiguration;
    private final Int2ObjectMap<ObjectList<RewardGroup>> rewardsByLevel;

    private ResearchLevelStage(Builder builder) {
        firstLevel = builder.firstLevel;
        lastLevel = builder.lastLevel;
        if (builder.researchTier < 0) {
            throw new IllegalArgumentException("Research stage must explicitly declare its tier");
        }
        researchTier = builder.researchTier;
        resourceFormula = Objects.requireNonNull(builder.resourceFormula, "Research stage formula");
        resourceFormula.validateRange(firstLevel, lastLevel);
        prerequisites = ResearchSnapshots.levels(builder.prerequisites);
        mutexRules = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.mutexRules));
        var materials = new ObjectArrayList<RecipeRequirement<?>>();
        var materialIds = new ObjectOpenHashSet<ResourceLocation>();
        for (var requirement : builder.materialRequirements) {
            if (!materialIds.add(requirement.id())) {
                throw new IllegalArgumentException("Duplicate research material requirement " + requirement.id());
            }
            materials.add(requirement);
        }
        materialRequirements = ObjectLists.unmodifiable(materials);
        materialRevision = builder.materialRevision;
        materialConfiguration = builder.materialConfiguration;
        validateManualMaterials();
        var rewards = new Int2ObjectOpenHashMap<ObjectList<RewardGroup>>();
        for (var entry : builder.rewardsByLevel.int2ObjectEntrySet()) {
            var ids = new ObjectOpenHashSet<ResourceLocation>();
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

    public Object2IntMap<ResourceLocation> prerequisites() {
        return prerequisites;
    }

    public ObjectList<ResearchMutexRule> mutexRules() {
        return mutexRules;
    }

    public ResearchResourceFormula resourceFormula() {
        return resourceFormula;
    }

    public Object2LongMap<NetworkResourceType> resourceDemandForLevel(int level) {
        validateLevel(level);
        return resourceFormula.demandsAt(firstLevel, level);
    }

    public Object2LongMap<NetworkResourceType> segmentGrantLimits() {
        return resourceFormula.segmentGrantLimits();
    }

    public ObjectList<RecipeRequirement<?>> materialRequirements() {
        return materialRequirements;
    }

    /**
     * Loading-only binding; the registry invokes configuration once per definition and stage.
     */
    RecipeProcessNbt materialPersistence(MachineRecipe recipe) {
        // Explicit framing separates user behavior revision from this definition's stage interval.
        String revision = "research-material:" + firstLevel + ":" + lastLevel + ":"
            + materialRevision.length() + ":" + materialRevision;
        var builder = RecipeProcessNbt.builder(recipe, revision);
        materialConfiguration.accept(builder);
        return builder.build();
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

    /**
     * PN manual execution consumed static item inputs only. Reject unsupported complete payloads
     * explicitly instead of accepting then silently dropping their tick/output/chance effects.
     */
    private void validateManualMaterials() {
        if (researchTier != 0) {
            return;
        }
        if (!resourceFormula.baseDemands().isEmpty()) {
            throw new IllegalArgumentException("Tier-zero research cannot declare numeric resources");
        }
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
                || inputAllocation != InputAllocation.SPLITTABLE
                || !onlyItems(inputs)) {
                throw new IllegalArgumentException("Tier-zero research supports only static AEItemKey START inputs with default source allocation: " + requirement.id());
            }
        }
    }

    private static boolean onlyItems(List<GenericStack> inputs) {
        for (var stack : inputs) if (!(stack.what() instanceof AEItemKey)) return false;
        return true;
    }

    public static final class Builder {
        private final int firstLevel;
        private final int lastLevel;
        private final Object2IntOpenHashMap<ResourceLocation> prerequisites = new Object2IntOpenHashMap<>();
        private final ObjectArrayList<ResearchMutexRule> mutexRules = new ObjectArrayList<>();
        private final ObjectArrayList<RecipeRequirement<?>> materialRequirements = new ObjectArrayList<>();
        private final Int2ObjectOpenHashMap<ObjectArrayList<RewardGroup>> rewardsByLevel = new Int2ObjectOpenHashMap<>();
        private int researchTier = -1;
        private ResearchResourceFormula resourceFormula;
        private String materialRevision = "1";
        private Consumer<RecipeProcessNbt.Builder> materialConfiguration = _ -> {
        };

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

        /**
         * Explicit revision and existing process codec extensions for positive-tier material work.
         * Change revision whenever Java behavior or payloads change incompatibly. Configuration runs
         * only at registry freeze, never per segment, target level or restored process.
         */
        public Builder materialPersistence(String revision, Consumer<RecipeProcessNbt.Builder> configure) {
            if (Objects.requireNonNull(revision).isBlank()) {
                throw new IllegalArgumentException("Blank material behavior revision");
            }
            materialRevision = revision;
            materialConfiguration = Objects.requireNonNull(configure);
            return this;
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
