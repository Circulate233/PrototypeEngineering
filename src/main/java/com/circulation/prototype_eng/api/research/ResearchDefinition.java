package com.circulation.prototype_eng.api.research;

import ae2.api.stacks.AEKey;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;


/**
 * Immutable PN research definition. Continuous stages cover exactly 1..maxLevel.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
@SuppressWarnings("unused")
public final class ResearchDefinition {
    private final ResourceLocation id;
    private final LocalizationEnum displayName;
    private final @Nullable AEKey icon;
    private final int maxLevel;
    private final boolean specialOnly;
    private final ResearchDownloadDeliveryPolicy deliveryPolicy;
    private final ObjectList<ResearchLevelStage> stages;
    private final Object2IntMap<UnlockKey> unlockLevels;
    private final ResearchStartCondition startCondition;

    private ResearchDefinition(Builder builder) {
        id = builder.id;
        displayName = builder.displayName == null ? id::toString : builder.displayName;
        icon = builder.icon;
        if (builder.maxLevel < 1) {
            throw new IllegalArgumentException("Research must explicitly declare positive maxLevel: " + id);
        }
        maxLevel = builder.maxLevel;
        specialOnly = builder.specialOnly;
        deliveryPolicy = builder.deliveryPolicy;
        if (builder.stages.isEmpty()) {
            throw new IllegalArgumentException("Research must declare stages: " + id);
        }
        long expectedFirst = 1;
        for (int index = 0; index < builder.stages.size(); index++) {
            var stage = builder.stages.get(index);
            if (stage.firstLevel() != expectedFirst) {
                throw new IllegalArgumentException("Research stages must be contiguous at level " + expectedFirst + ": " + id);
            }
            expectedFirst = (long) stage.lastLevel() + 1;
        }
        if (expectedFirst != (long) maxLevel + 1) {
            throw new IllegalArgumentException("Research stages must end at maxLevel: " + id);
        }
        stages = builder.stages;
        for (var level : builder.unlockLevels.values()) {
            if (level > maxLevel) {
                throw new IllegalArgumentException("Unlock level exceeds maxLevel: " + id);
            }
        }
        unlockLevels = builder.unlockLevels;
        startCondition = builder.startCondition;
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    public ResourceLocation id() {
        return id;
    }

    public LocalizationEnum displayName() {
        return displayName;
    }

    public @Nullable AEKey icon() {
        return icon;
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
        int required = unlockLevels.getInt(new UnlockKey(query.effectId(), query.targetId()));
        return required > 0 && query.completedLevel() >= required;
    }

    private record UnlockKey(ResourceLocation effectId, ResourceLocation targetId) {
    }

    /**
     * Transfers its completed collections on build; do not reuse or mutate the builder afterward.
     */
    public static final class Builder {
        private final ResourceLocation id;
        private @Nullable LocalizationEnum displayName;
        private @Nullable AEKey icon;
        private final ObjectArrayList<ResearchLevelStage> stages = new ObjectArrayList<>();
        private final Object2IntOpenHashMap<UnlockKey> unlockLevels = new Object2IntOpenHashMap<>();
        private int maxLevel;
        private boolean specialOnly;
        private ResearchDownloadDeliveryPolicy deliveryPolicy;
        private ResearchStartCondition startCondition = ResearchStartCondition.ALWAYS_ALLOWED;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public Builder displayName(LocalizationEnum name) {
            displayName = name;
            return this;
        }

        public Builder icon(AEKey resource) {
            icon = resource;
            return this;
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
            deliveryPolicy = policy;
            return this;
        }

        public Builder stage(ResearchLevelStage stage) {
            stages.add(stage);
            return this;
        }

        public Builder unlock(ResourceLocation effect, ResourceLocation target, int level) {
            var key = new UnlockKey(effect, target);
            if (level < 1 || unlockLevels.containsKey(key)) {
                throw new IllegalArgumentException("Invalid or duplicate research unlock");
            }
            unlockLevels.put(key, level);
            return this;
        }

        public Builder startCondition(ResearchStartCondition condition) {
            startCondition = condition;
            return this;
        }

        public ResearchDefinition build() {
            return new ResearchDefinition(this);
        }
    }
}
