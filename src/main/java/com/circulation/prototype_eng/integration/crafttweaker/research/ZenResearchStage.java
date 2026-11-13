package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRequirement;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/**
 * Parameters delegate to the Java stage; addon stages retain their complete explicit codecs.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchStage")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchStage {
    private final ResearchLevelStage.Builder builder;

    public ZenResearchStage(ResearchLevelStage.Builder builder) {
        this.builder = Objects.requireNonNull(builder);
    }

    @ZenMethod
    public static ZenResearchStage create(int firstLevel, int lastLevel, int tier, ZenResearchFormula formula) {
        return new ZenResearchStage(ResearchLevelStage.builder(firstLevel, lastLevel).researchTier(tier).resourceFormula(formula.value()));
    }

    @ZenMethod
    public ZenResearchStage prerequisite(String id, int minimumLevel) {
        builder.prerequisite(new ResourceLocation(id), minimumLevel);
        return this;
    }

    @ZenMethod
    public ZenResearchStage mutex(String id, int minimumLevel) {
        builder.mutex(new ResourceLocation(id), minimumLevel);
        return this;
    }

    @ZenMethod
    public ZenResearchStage material(ZenRequirement requirement) {
        builder.materialRequirement(requirement.value());
        return this;
    }

    @ZenMethod
    public ZenResearchStage materialPersistence(String revision, boolean componentData) {
        builder.materialPersistence(revision, codec -> {
            if (componentData) codec.component(DataComponents.PROCESS, DataComponents.PROCESS_CODEC);
        });
        return this;
    }

    @ZenMethod
    public ZenResearchStage reward(int level, ZenResearchReward reward) {
        builder.rewardGroup(level, reward.value());
        return this;
    }

    public ResearchLevelStage value() {
        return builder.build();
    }
}
