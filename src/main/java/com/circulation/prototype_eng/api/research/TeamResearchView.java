package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable ordinary research projection for one captured team membership and frozen definition
 * registry. This read model does not grant permission, reserve resources or persist source levels.
 */
public final class TeamResearchView {
    private final ResearchRegistry registry;
    private final String teamId;
    private final ObjectSet<UUID> members;
    private final Object2IntMap<ResourceLocation> sharedLevels;
    private final Object2IntMap<ResourceLocation> startableResearchTargets;
    private final ObjectSet<ResourceLocation> blacklist;

    /**
     * Copies a calculated projection; authoritative source capture belongs to ResearchEligibility.
     */
    public TeamResearchView(ResearchRegistry registry, String teamId, Collection<UUID> members,
                            Object2IntMap<ResourceLocation> sharedLevels,
                            Object2IntMap<ResourceLocation> startableResearchTargets,
                            Collection<ResourceLocation> blacklist) {
        this.registry = Objects.requireNonNull(registry);
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research view requires a frozen registry");
        }
        this.teamId = Objects.requireNonNull(teamId);
        if (teamId.isBlank() || !teamId.equals(teamId.trim())) {
            throw new IllegalArgumentException("Research team id must be canonical non-blank text");
        }
        var memberCopy = new ObjectOpenHashSet<UUID>(members.size());
        for (var member : Objects.requireNonNull(members)) {
            if (!memberCopy.add(Objects.requireNonNull(member))) {
                throw new IllegalArgumentException("Duplicate research team member " + member);
            }
        }
        this.members = ObjectSets.unmodifiable(memberCopy);
        var blacklistCopy = new ObjectOpenHashSet<ResourceLocation>(blacklist.size());
        for (var id : Objects.requireNonNull(blacklist)) {
            registry.require(Objects.requireNonNull(id));
            blacklistCopy.add(id);
        }
        this.blacklist = ObjectSets.unmodifiable(blacklistCopy);
        this.sharedLevels = immutableLevels(sharedLevels);
        var targets = new Object2IntOpenHashMap<ResourceLocation>(startableResearchTargets.size());
        for (var entry : Objects.requireNonNull(startableResearchTargets).object2IntEntrySet()) {
            var id = Objects.requireNonNull(entry.getKey());
            var definition = registry.require(id);
            int target = entry.getIntValue();
            int completed = this.sharedLevels.getInt(id);
            if (definition.specialOnly() || this.blacklist.contains(id) || completed >= definition.maxLevel()
                || target != completed + 1) {
                throw new IllegalArgumentException("Invalid projected start target for " + id + ": " + target);
            }
            targets.put(id, target);
        }
        this.startableResearchTargets = targets;
    }

    public ResearchRegistry registry() {
        return registry;
    }

    public String teamId() {
        return teamId;
    }

    public ObjectSet<UUID> members() {
        return members;
    }

    public Object2IntMap<ResourceLocation> sharedLevels() {
        return sharedLevels;
    }

    public Object2IntMap<ResourceLocation> startableResearchTargets() {
        return startableResearchTargets;
    }

    public ObjectSet<ResourceLocation> blacklistedResearchIds() {
        return blacklist;
    }

    public int completedLevel(ResourceLocation id) {
        return sharedLevels.getInt(Objects.requireNonNull(id));
    }

    public boolean isStartable(ResourceLocation id) {
        return startableResearchTargets.containsKey(Objects.requireNonNull(id));
    }

    public int startableTargetLevel(ResourceLocation id) {
        return startableResearchTargets.getInt(Objects.requireNonNull(id));
    }

    private Object2IntMap<ResourceLocation> immutableLevels(Object2IntMap<ResourceLocation> source) {
        var copy = new Object2IntOpenHashMap<ResourceLocation>(source.size());
        for (var entry : Objects.requireNonNull(source).object2IntEntrySet()) {
            var id = Objects.requireNonNull(entry.getKey());
            var definition = registry.require(id);
            int level = entry.getIntValue();
            if (level < 0 || level > definition.maxLevel()
                || level > 0 && (definition.specialOnly() || blacklist.contains(id))) {
                throw new IllegalArgumentException("Invalid effective research level for " + id + ": " + level);
            }
            if (level > 0) {
                copy.put(id, level);
            }
        }
        return copy;
    }
}
