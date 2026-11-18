package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchStartContext;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PN's combined-source effective levels and contextual start checks against frozen definitions.
 */
public final class ResearchEligibility {
    private final ResearchRegistry registry;

    public ResearchEligibility(ResearchRegistry registry) {
        this.registry = registry;
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research eligibility requires a frozen registry");
        }
    }

    private static void requireTeamId(String teamId) {
        if (teamId.isBlank() || !teamId.equals(teamId.trim())) {
            throw new IllegalArgumentException("Research team id must be canonical non-blank text");
        }
    }

    /**
     * Current members contribute their personal maximum; old direct levels remain readable for
     * saved worlds and unfinished legacy transactions. Blacklists and prerequisites filter the view. Conditions run for each structurally eligible target.
     * Resources and membership must be captured by the server owner before calling this method.
     * Supplied membership sets and blacklists remain borrowed read-only while any view retains them.
     */
    public TeamResearchView calculate(String teamId, Collection<UUID> currentMembers,
                                      Map<UUID, PlayerResearchState> playerStates,
                                      Object2IntMap<ResourceLocation> teamOwnedResearch, int networkCenterLevel,
                                      Set<ResourceLocation> blacklistedResearchIds,
                                      Reference2DoubleMap<NetworkResourceType> availableResources) {
        requireTeamId(teamId);
        if (networkCenterLevel < 0) {
            throw new IllegalArgumentException("Negative network center level");
        }
        var structuralView = calculateStructural(teamId, currentMembers, playerStates, teamOwnedResearch, blacklistedResearchIds);
        var startable = new Object2IntOpenHashMap<ResourceLocation>();
        var elements3 = registry.all();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var definition = elements3.get(index3);
            int completed = structuralView.completedLevel(definition.id());
            if (completed >= definition.maxLevel()) {
                continue;
            }
            int target = completed + 1;
            if (structurallyEligible(structuralView, definition.id(), target)
                && definition.startCondition().canStart(new ResearchStartContext(definition.id(), target, null,
                teamId, structuralView.sharedLevels(), availableResources, networkCenterLevel))) {
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
        var members = currentMembers instanceof Set<UUID> set ? set : new ObjectOpenHashSet<>(currentMembers);
        var raw = new Object2IntOpenHashMap<ResourceLocation>(members.size());
        for (var member : members) {
            var state = playerStates.get(member);
            for (var entry : state.completedLevelsSnapshot().object2IntEntrySet()) {
                var id = entry.getKey();
                if (entry.getIntValue() > raw.getInt(id)) {
                    raw.put(id, entry.getIntValue());
                }
            }
        }
        for (var entry : teamOwnedResearch.object2IntEntrySet()) {
            var id = entry.getKey();
            if (entry.getIntValue() > raw.getInt(id)) {
                raw.put(id, entry.getIntValue());
            }
        }
        var effective = new Object2IntOpenHashMap<ResourceLocation>();
        var elements = registry.all();
        for (int index = 0; index < elements.size(); index++) {
            var definition = elements.get(index);
            resolveEffective(definition.id(), raw, blacklistedResearchIds, effective);
        }
        effective.object2IntEntrySet().removeIf(entry -> entry.getIntValue() == 0);
        return new TeamResearchView(registry, teamId, members, effective, Object2IntMaps.emptyMap(), blacklistedResearchIds);
    }

    /**
     * Rechecks a real player start with the captured membership and supplied current context. The
     * team-wide candidate cache is never used as permission, nor substituted for the player predicate.
     */
    public boolean canStart(TeamResearchView view, ResourceLocation researchId, int targetLevel, UUID requestedBy,
                            Reference2DoubleMap<NetworkResourceType> availableResources, int networkCenterLevel) {
        requireView(view);
        if (!view.members().contains(requestedBy)) {
            throw new IllegalArgumentException("Research requester is not a captured member of team " + view.teamId());
        }
        if (networkCenterLevel < 0) {
            throw new IllegalArgumentException("Negative network center level");
        }
        if (!structurallyEligible(view, researchId, targetLevel)) {
            return false;
        }
        var definition = registry.require(researchId);
        return definition.startCondition().canStart(new ResearchStartContext(researchId, targetLevel, requestedBy,
            view.teamId(), view.sharedLevels(), availableResources, networkCenterLevel));
    }

    /**
     * Existing queue retention checks rules only; an admission condition is not a continuation gate.
     */
    public boolean structurallyEligible(TeamResearchView view, ResourceLocation researchId, int targetLevel) {
        requireView(view);
        var definition = registry.require(researchId);
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
        var elements2 = stage.mutexRules();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var rule = elements2.get(index2);
            if (view.completedLevel(rule.researchId()) >= rule.minLevel()) {
                return false;
            }
        }
        return true;
    }

    private int resolveEffective(ResourceLocation id, Object2IntMap<ResourceLocation> raw,
                                 Set<ResourceLocation> blacklist, Object2IntOpenHashMap<ResourceLocation> cache) {
        if (cache.containsKey(id)) {
            return cache.getInt(id);
        }
        var definition = registry.require(id);
        int candidate = definition.specialOnly() || blacklist.contains(id) ? 0 : raw.getInt(id);
        while (candidate > 0) {
            var stage = definition.stageForLevel(candidate);
            boolean satisfied = true;
            for (var prerequisite : stage.prerequisites().object2IntEntrySet()) {
                if (resolveEffective(prerequisite.getKey(), raw, blacklist, cache) < prerequisite.getIntValue()) {
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
    }

    private void requireView(TeamResearchView view) {
        if (view.registry() != registry) {
            throw new IllegalArgumentException("Research view belongs to another definition registry");
        }
    }

}
