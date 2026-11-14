package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.util.ResourceLocation;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable ordinary research projection for one captured team membership and frozen definition
 * registry. Suppliers and readers must not mutate any retained collection.
 * This read model does not grant permission, reserve resources or persist source levels.
 */
public final class TeamResearchView {
    private final ResearchRegistry registry;
    private final String teamId;
    private final Set<UUID> members;
    private final Object2IntMap<ResourceLocation> sharedLevels;
    private final Object2IntMap<ResourceLocation> startableResearchTargets;
    private final Set<ResourceLocation> blacklist;

    /**
     * Retains a completed projection from ResearchEligibility without copying it.
     */
    public TeamResearchView(ResearchRegistry registry, String teamId, Set<UUID> members,
                            Object2IntMap<ResourceLocation> sharedLevels,
                            Object2IntMap<ResourceLocation> startableResearchTargets,
                            Set<ResourceLocation> blacklist) {
        this.registry = registry;
        this.teamId = teamId;
        this.members = members;
        this.blacklist = blacklist;
        this.sharedLevels = sharedLevels;
        this.startableResearchTargets = startableResearchTargets;
    }

    public ResearchRegistry registry() {
        return registry;
    }

    public String teamId() {
        return teamId;
    }

    public Set<UUID> members() {
        return members;
    }

    public Object2IntMap<ResourceLocation> sharedLevels() {
        return sharedLevels;
    }

    public Object2IntMap<ResourceLocation> startableResearchTargets() {
        return startableResearchTargets;
    }

    public Set<ResourceLocation> blacklistedResearchIds() {
        return blacklist;
    }

    public int completedLevel(ResourceLocation id) {
        return sharedLevels.getInt(id);
    }

    public boolean isStartable(ResourceLocation id) {
        return startableResearchTargets.containsKey(id);
    }

    public int startableTargetLevel(ResourceLocation id) {
        return startableResearchTargets.getInt(id);
    }

}
