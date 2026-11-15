package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRequirement;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;


/**
 * ZenScript research stage definition; duration is measured in ticks.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchStage")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchStage {
    private final ResearchLevelStage.Builder builder;

    public ZenResearchStage(ResearchLevelStage.Builder builder) {
        this.builder = builder;
    }

    @ZenMethod
    public static ZenResearchStage create(int firstLevel, int lastLevel, int tier, long durationTicks, ZenResearchFormula formula) {
        return new ZenResearchStage(ResearchLevelStage.builder(firstLevel, lastLevel).researchTier(tier).durationTicks(durationTicks).resourceFormula(formula.value()));
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
    public ZenResearchStage reward(int level, ZenResearchReward reward) {
        builder.rewardGroup(level, reward.value());
        return this;
    }

    public ResearchLevelStage value() {
        return builder.build();
    }
}
