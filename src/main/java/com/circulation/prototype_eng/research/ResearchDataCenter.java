package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** PN database rules with one atomic content publication and monotonic owner-side revision. */
public final class ResearchDataCenter implements DataCenterStorage {
    private final ResearchRegistry registry;
    private final Runnable ownerCheck;
    private final Runnable changed;
    private DataCenterResearchSnapshot contents;

    public ResearchDataCenter(UUID id, int level, int capacity, ResearchRegistry registry, Runnable ownerCheck, Runnable changed) {
        this.registry = Objects.requireNonNull(registry);
        if (!registry.isFrozen()) { throw new IllegalStateException("Data-center storage requires frozen research"); }
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
        this.changed = Objects.requireNonNull(changed);
        contents = new DataCenterResearchSnapshot(id, level, capacity, 0, new Object2IntOpenHashMap<>());
    }

    /** Restore complete validated saved contents into a fresh backend; runtime revisions start in this new session. */
    public static ResearchDataCenter restore(DataCenterResearchSnapshot saved, ResearchRegistry registry,
                                            Runnable ownerCheck, Runnable changed) {
        var restored = new ResearchDataCenter(saved.dataCenterId(), saved.level(), saved.capacity(), registry, ownerCheck, changed);
        var levels = new Object2IntOpenHashMap<>(saved.storedResearch());
        restored.validateContents(levels);
        restored.contents = new DataCenterResearchSnapshot(saved.dataCenterId(), saved.level(), saved.capacity(), 0, levels);
        return restored;
    }

    @Override public DataCenterResearchSnapshot snapshot() { ownerCheck.run(); return contents; }
    @Override public boolean current(DataCenterResearchSnapshot snapshot) { ownerCheck.run(); return contents == snapshot; }

    @Override public Result downloadResearch(ResourceLocation researchId, int requestedLevel, TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = contents;
        if (!Objects.requireNonNull(current).getAsBoolean() || contents != before) return Result.STALE_CONTEXT;
        var definition = registry.require(researchId);
        if (definition.specialOnly()) { return Result.SPECIAL_ONLY_REJECTED; }
        if (definition.deliveryPolicy() != ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) { return Result.ITEM_ONLY_REJECTED; }
        int target;
        if (definition.maxLevel() > 1) {
            target = cloud.completedLevel(researchId);
            if (target < 1) { return Result.MISSING_CLOUD_RESEARCH_LEVEL; }
        } else {
            definition.stageForLevel(requestedLevel);
            target = requestedLevel;
            if (cloud.completedLevel(researchId) < target) { return Result.MISSING_CLOUD_RESEARCH_LEVEL; }
        }
        definition.stageForLevel(target);
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        var result = store(definition, target, replacement, cloud.sharedLevels());
        return result.accepted() ? publish(before, replacement, current) : result;
    }

    @Override public Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = contents;
        if (!Objects.requireNonNull(current).getAsBoolean() || contents != before) return Result.STALE_CONTEXT;
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        for (var entry : Objects.requireNonNull(entries).object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            definition.stageForLevel(entry.getIntValue());
            var result = store(definition, entry.getIntValue(), replacement, cloud.sharedLevels());
            if (!result.accepted()) { return result; }
        }
        return publish(before, replacement, current);
    }

    @Override public Result synchronizeLoopingResearch(TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = contents;
        if (!Objects.requireNonNull(current).getAsBoolean() || contents != before) return Result.STALE_CONTEXT;
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        for (var entry : before.storedResearch().object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            int target = cloud.completedLevel(entry.getKey());
            if (!definition.specialOnly() && definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM
                && definition.maxLevel() > 1 && target > entry.getIntValue()
                && definition.researchTierForLevel(target) <= contents.level()) {
                replacement.put(entry.getKey(), target);
            }
        }
        var visible = new Object2IntOpenHashMap<>(cloud.sharedLevels());
        for (var entry : replacement.object2IntEntrySet()) {
            if (entry.getIntValue() > visible.getInt(entry.getKey())) { visible.put(entry.getKey(), entry.getIntValue()); }
        }
        for (var entry : replacement.object2IntEntrySet()) {
            if (entry.getIntValue() > contents.storedResearch().getInt(entry.getKey())
                && mutexBlocked(registry.require(entry.getKey()), entry.getIntValue(), visible)) {
                return Result.MUTEX_BLOCKED;
            }
        }
        return publish(before, replacement, current);
    }

    private Result store(ResearchDefinition definition, int target, Object2IntOpenHashMap<ResourceLocation> replacement,
                         Object2IntMap<ResourceLocation> cloud) {
        if (definition.researchTierForLevel(target) > contents.level()) { return Result.DATA_CENTER_LEVEL_TOO_LOW; }
        var visible = new Object2IntOpenHashMap<>(cloud);
        for (var entry : replacement.object2IntEntrySet()) {
            if (entry.getIntValue() > visible.getInt(entry.getKey())) { visible.put(entry.getKey(), entry.getIntValue()); }
        }
        if (mutexBlocked(definition, target, visible)) { return Result.MUTEX_BLOCKED; }
        if (!replacement.containsKey(definition.id()) && replacement.size() >= contents.capacity()) { return Result.CAPACITY_FULL; }
        if (target > replacement.getInt(definition.id())) { replacement.put(definition.id(), target); return Result.UPDATED; }
        return Result.UNCHANGED;
    }

    private boolean mutexBlocked(ResearchDefinition candidate, int target, Object2IntMap<ResourceLocation> visible) {
        for (var rule : candidate.mutexRulesForLevel(target)) {
            if (visible.getInt(rule.researchId()) >= rule.minLevel()) { return true; }
        }
        for (var entry : visible.object2IntEntrySet()) {
            if (entry.getKey().equals(candidate.id()) || entry.getIntValue() < 1) { continue; }
            for (var rule : registry.require(entry.getKey()).mutexRulesForLevel(entry.getIntValue())) {
                if (rule.researchId().equals(candidate.id()) && target >= rule.minLevel()) { return true; }
            }
        }
        return false;
    }

    private void validateContents(Object2IntMap<ResourceLocation> levels) {
        for (var entry : levels.object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            if (definition.researchTierForLevel(entry.getIntValue()) > contents.level()
                || mutexBlocked(definition, entry.getIntValue(), levels)) {
                throw new IllegalArgumentException("Saved data-center research violates tier or mutex: " + entry.getKey());
            }
        }
    }

    private Result publish(DataCenterResearchSnapshot before, Object2IntMap<ResourceLocation> replacement, BooleanSupplier current) {
        if (!current.getAsBoolean() || contents != before) return Result.STALE_CONTEXT;
        if (contents.storedResearch().equals(replacement)) { return Result.UNCHANGED; }
        long revision = Math.incrementExact(contents.revision());
        contents = new DataCenterResearchSnapshot(contents.dataCenterId(), contents.level(), contents.capacity(), revision, replacement);
        changed.run();
        return Result.UPDATED;
    }

    private void requireCloud(TeamResearchView cloud) {
        if (Objects.requireNonNull(cloud).registry() != registry) { throw new IllegalArgumentException("Data-center cloud has another research registry"); }
    }
}
