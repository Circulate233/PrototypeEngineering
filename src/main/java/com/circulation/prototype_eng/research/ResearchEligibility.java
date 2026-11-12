package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchStartContext;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * PN's combined-source effective levels and contextual start checks against frozen definitions.
 */
public final class ResearchEligibility {
    private final ResearchRegistry registry;

    public ResearchEligibility(ResearchRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research eligibility requires a frozen registry");
        }
    }

    private static void requireTeamId(String teamId) {
        Objects.requireNonNull(teamId);
        if (teamId.isBlank() || !teamId.equals(teamId.trim())) {
            throw new IllegalArgumentException("Research team id must be canonical non-blank text");
        }
    }

    /**
     * Current members contribute their personal maximum; item-only definitions use the team's own
     * source exclusively. Blacklist and staged prerequisites affect the resulting view across both
     * sources without erasing stored levels. Conditions run for each structurally eligible target.
     * Resources and membership must be captured by the server owner before calling this method.
     */
    public TeamResearchView calculate(String teamId, Collection<UUID> currentMembers,
                                      Map<UUID, PlayerResearchState> playerStates,
                                      Object2IntMap<ResourceLocation> teamOwnedResearch, int networkCenterLevel,
                                      Set<ResourceLocation> blacklistedResearchIds,
                                      Object2LongMap<NetworkResourceType> availableResources) {
        requireTeamId(teamId);
        if (networkCenterLevel < 0) {
            throw new IllegalArgumentException("Negative network center level");
        }
        var resources = NetworkResourceModifier.copyResources(availableResources);
        var structuralView = calculateStructural(teamId, currentMembers, playerStates, teamOwnedResearch, blacklistedResearchIds);
        var startable = new Object2IntOpenHashMap<ResourceLocation>();
        for (var definition : registry.all()) {
            int completed = structuralView.completedLevel(definition.id());
            if (completed >= definition.maxLevel()) {
                continue;
            }
            int target = completed + 1;
            if (structurallyEligible(structuralView, definition.id(), target)
                && definition.startCondition().canStart(new ResearchStartContext(definition.id(), target, null,
                teamId, structuralView.sharedLevels(), resources, networkCenterLevel))) {
                startable.put(definition.id(), target);
            }
        }
        return new TeamResearchView(registry, teamId, structuralView.members(), structuralView.sharedLevels(),
            startable, structuralView.blacklistedResearchIds());
    }

    /**
     * Captures source levels and structural rules for server admission and queue retention without running start conditions.
     */
    public TeamResearchView calculateStructural(String teamId, Collection<UUID> currentMembers,
                                                Map<UUID, PlayerResearchState> playerStates,
                                                Object2IntMap<ResourceLocation> teamOwnedResearch,
                                                Set<ResourceLocation> blacklistedResearchIds) {
        requireTeamId(teamId);
        var members = new ObjectOpenHashSet<UUID>(currentMembers.size());
        for (var member : Objects.requireNonNull(currentMembers)) {
            if (!members.add(Objects.requireNonNull(member))) {
                throw new IllegalArgumentException("Duplicate research team member " + member);
            }
        }
        for (var entry : Objects.requireNonNull(playerStates).entrySet()) {
            var player = Objects.requireNonNull(entry.getKey());
            var state = Objects.requireNonNull(entry.getValue());
            if (!player.equals(state.playerUuid())) {
                throw new IllegalArgumentException("Research state identity differs from player table key " + player);
            }
        }
        var blacklist = new ObjectOpenHashSet<ResourceLocation>(blacklistedResearchIds.size());
        for (var id : Objects.requireNonNull(blacklistedResearchIds)) {
            registry.require(Objects.requireNonNull(id));
            blacklist.add(id);
        }
        var raw = new Object2IntOpenHashMap<ResourceLocation>(members.size());
        for (var member : members) {
            var state = playerStates.get(member);
            if (state == null) {
                throw new IllegalArgumentException("Missing research state for member " + member);
            }
            for (var entry : state.completedLevelsSnapshot().object2IntEntrySet()) {
                var id = Objects.requireNonNull(entry.getKey());
                var definition = requireSource(id, entry.getIntValue(), ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
                if (entry.getIntValue() > raw.getInt(definition.id())) {
                    raw.put(id, entry.getIntValue());
                }
            }
        }
        for (var entry : Objects.requireNonNull(teamOwnedResearch).object2IntEntrySet()) {
            var id = Objects.requireNonNull(entry.getKey());
            requireSource(id, entry.getIntValue(), ResearchDownloadDeliveryPolicy.ITEM_ONLY);
            if (raw.containsKey(id)) {
                throw new IllegalArgumentException("Research exists in both ordinary sources: " + id);
            }
            if (entry.getIntValue() > 0) {
                raw.put(id, entry.getIntValue());
            }
        }
        var effective = new Object2IntOpenHashMap<ResourceLocation>();
        var resolving = new ObjectOpenHashSet<ResourceLocation>();
        for (var definition : registry.all()) {
            resolveEffective(definition.id(), raw, blacklist, effective, resolving);
        }
        var emptyTargets = new Object2IntOpenHashMap<ResourceLocation>();
        return new TeamResearchView(registry, teamId, members, effective, emptyTargets, blacklist);
    }

    /**
     * Rechecks a real player start with the captured membership and supplied current context. The
     * team-wide candidate cache is never used as permission, nor substituted for the player predicate.
     */
    public boolean canStart(TeamResearchView view, ResourceLocation researchId, int targetLevel, UUID requestedBy,
                            Object2LongMap<NetworkResourceType> availableResources, int networkCenterLevel) {
        requireView(view);
        Objects.requireNonNull(requestedBy);
        if (!view.members().contains(requestedBy)) {
            throw new IllegalArgumentException("Research requester is not a captured member of team " + view.teamId());
        }
        if (networkCenterLevel < 0) {
            throw new IllegalArgumentException("Negative network center level");
        }
        var resources = NetworkResourceModifier.copyResources(availableResources);
        if (!structurallyEligible(view, researchId, targetLevel)) {
            return false;
        }
        var definition = registry.require(researchId);
        return definition.startCondition().canStart(new ResearchStartContext(researchId, targetLevel, requestedBy,
            view.teamId(), view.sharedLevels(), resources, networkCenterLevel));
    }

    /**
     * Existing queue retention checks rules only; an admission condition is not a continuation gate.
     */
    public boolean structurallyEligible(TeamResearchView view, ResourceLocation researchId, int targetLevel) {
        requireView(view);
        var definition = registry.require(Objects.requireNonNull(researchId));
        int completed = view.completedLevel(researchId);
        if (targetLevel < 1 || targetLevel > definition.maxLevel() || completed >= definition.maxLevel()
            || targetLevel != completed + 1 || definition.specialOnly() || view.blacklistedResearchIds().contains(researchId)) {
            return false;
        }
        var stage = definition.stageForLevel(targetLevel);
        for (var entry : stage.prerequisites().object2IntEntrySet()) {
            if (view.completedLevel(entry.getKey()) < entry.getIntValue()) {
                return false;
            }
        }
        for (var rule : stage.mutexRules()) {
            if (view.completedLevel(rule.researchId()) >= rule.minLevel()) {
                return false;
            }
        }
        return true;
    }

    private int resolveEffective(ResourceLocation id, Object2IntMap<ResourceLocation> raw,
                                 Set<ResourceLocation> blacklist, Object2IntOpenHashMap<ResourceLocation> cache,
                                 Set<ResourceLocation> resolving) {
        if (cache.containsKey(id)) {
            return cache.getInt(id);
        }
        if (!resolving.add(id)) {
            throw new IllegalStateException("Research prerequisite cycle at " + id);
        }
        try {
            var definition = registry.require(id);
            int candidate = definition.specialOnly() || blacklist.contains(id) ? 0 : raw.getInt(id);
            while (candidate > 0) {
                var stage = definition.stageForLevel(candidate);
                boolean satisfied = true;
                for (var prerequisite : stage.prerequisites().object2IntEntrySet()) {
                    if (resolveEffective(prerequisite.getKey(), raw, blacklist, cache, resolving) < prerequisite.getIntValue()) {
                        satisfied = false;
                        break;
                    }
                }
                if (satisfied) {
                    cache.put(id, candidate);
                    return candidate;
                }
                // Prerequisites are constant throughout this stage; skip its entire failed range.
                candidate = stage.firstLevel() - 1;
            }
            cache.put(id, 0);
            return 0;
        } finally {
            resolving.remove(id);
        }
    }

    private ResearchDefinition requireSource(ResourceLocation id, int level, ResearchDownloadDeliveryPolicy expected) {
        var definition = registry.require(id);
        if (definition.specialOnly() || definition.deliveryPolicy() != expected || level < 0 || level > definition.maxLevel()) {
            throw new IllegalArgumentException("Illegal ordinary research source or level for " + id + ": " + level);
        }
        return definition;
    }

    private void requireView(TeamResearchView view) {
        Objects.requireNonNull(view);
        if (view.registry() != registry) {
            throw new IllegalArgumentException("Research view belongs to another definition registry");
        }
    }

}
