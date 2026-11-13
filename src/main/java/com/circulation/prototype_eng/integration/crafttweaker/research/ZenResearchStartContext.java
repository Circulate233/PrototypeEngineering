package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.ResearchStartContext;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResource;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/**
 * Detached eligibility facts; this view grants no mutation or reservation capability.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchStartContext")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public record ZenResearchStartContext(ResearchStartContext value) {
    public ZenResearchStartContext {
        Objects.requireNonNull(value);
    }

    @ZenGetter("researchId")
    public String researchId() {
        return value.researchId().toString();
    }

    @ZenGetter("targetLevel")
    public int targetLevel() {
        return value.targetLevel();
    }

    @ZenGetter("playerId")
    public String playerId() {
        return value.playerId() == null ? "" : value.playerId().toString();
    }

    @ZenGetter("teamId")
    public String teamId() {
        return value.teamId() == null ? "" : value.teamId();
    }

    @ZenGetter("networkCenterLevel")
    public int networkCenterLevel() {
        return value.networkCenterLevel();
    }

    @ZenGetter("teamHasNetworkCenter")
    public boolean teamHasNetworkCenter() {
        return value.teamHasNetworkCenter();
    }

    @ZenMethod
    public int sharedLevel(String researchId) {
        return value.teamSharedResearchLevels().getInt(new ResourceLocation(researchId));
    }

    @ZenMethod
    public double available(ZenComputingResource resource) {
        return value.availableResources().getDouble(resource.value());
    }
}
