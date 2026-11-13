package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Immutable PN research definition. Continuous stages cover exactly 1..maxLevel.
 */
@SuppressWarnings("unused")
public final class ResearchDefinition {
    private final ResourceLocation id;
    private final int maxLevel;
    private final boolean specialOnly;
    private final ResearchDownloadDeliveryPolicy deliveryPolicy;
    private final int itemDownloadLevelCost;
    private final ObjectList<ResearchLevelStage> stages;
    private final Object2IntMap<UnlockKey> unlockLevels;
    private final ResearchStartCondition startCondition;

    private ResearchDefinition(Builder builder) {
        id = builder.id;
        if (builder.maxLevel < 1) {
            throw new IllegalArgumentException("Research must explicitly declare positive maxLevel: " + id);
        }
        maxLevel = builder.maxLevel;
        specialOnly = builder.specialOnly;
        deliveryPolicy = Objects.requireNonNull(builder.deliveryPolicy, "Research download delivery policy");
        if (builder.itemDownloadLevelCost > maxLevel) {
            throw new IllegalArgumentException("Item download level cost exceeds maxLevel: " + id);
        }
        itemDownloadLevelCost = builder.itemDownloadLevelCost;
        if (builder.stages.isEmpty()) {
            throw new IllegalArgumentException("Research must declare stages: " + id);
        }
        long expectedFirst = 1;
        for (var stage : builder.stages) {
            if (stage.firstLevel() != expectedFirst) {
                throw new IllegalArgumentException("Research stages must be contiguous at level " + expectedFirst + ": " + id);
            }
            expectedFirst = (long) stage.lastLevel() + 1;
        }
        if (expectedFirst != (long) maxLevel + 1) {
            throw new IllegalArgumentException("Research stages must end at maxLevel: " + id);
        }
        stages = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.stages));
        for (var level : builder.unlockLevels.values()) {
            if (level > maxLevel) {
                throw new IllegalArgumentException("Unlock level exceeds maxLevel: " + id);
            }
        }
        unlockLevels = Object2IntMaps.unmodifiable(new Object2IntOpenHashMap<>(builder.unlockLevels));
        startCondition = builder.startCondition;
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    public ResourceLocation id() {
        return id;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public boolean specialOnly() {
        return specialOnly;
    }

    public ResearchDownloadDeliveryPolicy deliveryPolicy() {
        return deliveryPolicy;
    }

    public int itemDownloadLevelCost() {
        return itemDownloadLevelCost;
    }

    public ObjectList<ResearchLevelStage> stages() {
        return stages;
    }

    public ResearchStartCondition startCondition() {
        return startCondition;
    }

    public ResearchLevelStage stageForLevel(int level) {
        if (level < 1 || level > maxLevel) {
            throw new IllegalArgumentException("Research level outside 1.." + maxLevel + ": " + id);
        }
        int low = 0;
        int high = stages.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            var stage = stages.get(middle);
            if (level < stage.firstLevel()) {
                high = middle - 1;
            } else if (level > stage.lastLevel()) {
                low = middle + 1;
            } else {
                return stage;
            }
        }
        throw new IllegalStateException("Validated research stage coverage changed: " + id);
    }

    public int researchTierForLevel(int level) {
        return stageForLevel(level).researchTier();
    }

    public Object2IntMap<ResourceLocation> prerequisitesForLevel(int level) {
        return stageForLevel(level).prerequisites();
    }

    public ObjectList<ResearchMutexRule> mutexRulesForLevel(int level) {
        return stageForLevel(level).mutexRules();
    }

    public Reference2DoubleMap<NetworkResourceType> totalResourceDemandForLevel(int level) {
        return stageForLevel(level).resourceDemandForLevel(level);
    }

    public ObjectList<RecipeRequirement<?>> materialRequirementsForLevel(int level) {
        return stageForLevel(level).materialRequirements();
    }

    public ObjectList<RewardGroup> rewardGroupsForLevel(int level) {
        return stageForLevel(level).rewardsForLevel(level);
    }

    public boolean unlocks(ResearchUnlockQuery query) {
        Objects.requireNonNull(query);
        int required = unlockLevels.getInt(new UnlockKey(query.effectId(), query.targetId()));
        return required > 0 && query.completedLevel() >= required;
    }

    private record UnlockKey(ResourceLocation effectId, ResourceLocation targetId) {
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final ObjectArrayList<ResearchLevelStage> stages = new ObjectArrayList<>();
        private final Object2IntOpenHashMap<UnlockKey> unlockLevels = new Object2IntOpenHashMap<>();
        private int maxLevel;
        private boolean specialOnly;
        private ResearchDownloadDeliveryPolicy deliveryPolicy;
        private int itemDownloadLevelCost = 1;
        private ResearchStartCondition startCondition = ResearchStartCondition.ALWAYS_ALLOWED;

        private Builder(ResourceLocation id) {
            this.id = Objects.requireNonNull(id);
        }

        public Builder maxLevel(int level) {
            if (level < 1) {
                throw new IllegalArgumentException("Research maxLevel must be positive");
            }
            maxLevel = level;
            return this;
        }

        public Builder specialOnly() {
            return specialOnly(true);
        }

        public Builder specialOnly(boolean special) {
            specialOnly = special;
            return this;
        }

        public Builder deliveryPolicy(ResearchDownloadDeliveryPolicy policy) {
            deliveryPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public Builder itemDownloadLevelCost(int cost) {
            if (cost < 0) {
                throw new IllegalArgumentException("Negative research item download level cost");
            }
            itemDownloadLevelCost = cost;
            return this;
        }

        public Builder stage(ResearchLevelStage stage) {
            stages.add(Objects.requireNonNull(stage));
            return this;
        }

        public Builder unlock(ResourceLocation effect, ResourceLocation target, int level) {
            var key = new UnlockKey(Objects.requireNonNull(effect), Objects.requireNonNull(target));
            if (level < 1 || unlockLevels.containsKey(key)) {
                throw new IllegalArgumentException("Invalid or duplicate research unlock");
            }
            unlockLevels.put(key, level);
            return this;
        }

        public Builder startCondition(ResearchStartCondition condition) {
            startCondition = Objects.requireNonNull(condition);
            return this;
        }

        public ResearchDefinition build() {
            return new ResearchDefinition(this);
        }
    }
}
