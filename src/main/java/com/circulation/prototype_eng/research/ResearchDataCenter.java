package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * PN database rules with one atomic content publication and monotonic owner-side revision.
 */
public final class ResearchDataCenter implements DataCenterStorage {
    private final ResearchRegistry registry;
    private final Runnable ownerCheck;
    private final Runnable changed;
    private State state;

    public ResearchDataCenter(UUID id, int level, int capacity, ResearchRegistry registry, Runnable ownerCheck, Runnable changed) {
        this(registry, ownerCheck, changed,
            new State(new DataCenterResearchSnapshot(id, level, capacity, 0, new Object2IntOpenHashMap<>()), Map.of()));
    }

    private ResearchDataCenter(ResearchRegistry registry, Runnable ownerCheck, Runnable changed, State state) {
        this.registry = registry;
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Data-center storage requires frozen research");
        }
        this.ownerCheck = ownerCheck;
        this.changed = changed;
        this.state = state;
    }

    /**
     * Restore validated saved contents; collections remain borrowed read-only and revisions restart at zero.
     */
    public static ResearchDataCenter restore(DataCenterResearchSnapshot saved, ResearchRegistry registry,
                                             Runnable ownerCheck, Runnable changed) {
        var contents = restoredContents(saved);
        return new ResearchDataCenter(registry, ownerCheck, changed, new State(contents, Map.of()));
    }


    /**
     * Restore validated component state, retaining every unresolved import receipt.
     */
    public static ResearchDataCenter restore(State saved, ResearchRegistry registry, Runnable ownerCheck, Runnable changed) {
        var contents = restoredContents(saved.contents());
        return new ResearchDataCenter(registry, ownerCheck, changed,
            contents == saved.contents() ? saved : new State(contents, saved.receipts()));
    }

    private static DataCenterResearchSnapshot restoredContents(DataCenterResearchSnapshot saved) {
        return saved.revision() == 0 ? saved : new DataCenterResearchSnapshot(saved.dataCenterId(), saved.level(), saved.capacity(), 0, saved.storedResearch());
    }

    /**
     * Validates external saved data before the decoder publishes it to a storage owner.
     */
    public static void validateSaved(State saved, ResearchRegistry registry) {
        validateContents(saved.contents(), registry);
        for (var entry : saved.receipts().entrySet()) {
            var receipt = entry.getValue();
            if (!entry.getKey().equals(receipt.operationId())
                || !receipt.before().dataCenterId().equals(saved.contents().dataCenterId())
                || receipt.before().level() != saved.contents().level() || receipt.before().capacity() != saved.contents().capacity())
                throw new IllegalArgumentException("Database receipt identity or metadata differs");
            validateContents(receipt.before(), registry);
            validateContents(receipt.after(), registry);
            for (var imported : receipt.entries().keySet()) {
                if (saved.contents().storedResearch().getInt(imported) < receipt.after().storedResearch().getInt(imported))
                    throw new IllegalArgumentException("Database contents lost a committed disk import");
            }
        }
    }

    public State persistentState() {
        ownerCheck.run();
        return state;
    }

    @Nullable
    public ResearchDiskCommit receipt(UUID operationId) {
        ownerCheck.run();
        return state.receipts().get(operationId);
    }

    /**
     * Atomic content/receipt primitive. Durability is a separate physical-host responsibility.
     * A rejected return has no effect; failures after publication retain the entire new state.
     */
    public Result commitDisk(ResearchDiskCommit commit, TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        var existing = state.receipts().get(commit.operationId());
        if (existing != null) {
            if (!existing.equals(commit)) throw new IllegalStateException("Conflicting database import receipt");
            return Result.UNCHANGED;
        }
        requireCloud(cloud);
        var before = state;
        if (!current.getAsBoolean() || state != before || !commit.matchesBefore(before.contents())) {
            return Result.STALE_CONTEXT;
        }
        if (!commit.teamId().equals(cloud.teamId()))
            throw new IllegalArgumentException("Disk import belongs to another team");
        var data = new ResearchDiskData(registry, commit.diskTier(), commit.entries());
        var prepared = ResearchDiskImport.prepare(commit.operationId(), commit.actor(), commit.teamId(), data, before.contents(), cloud);
        if (!prepared.result().accepted()) {
            return prepared.result().storageResult();
        }
        if (!commit.equals(prepared.commit()))
            throw new IllegalArgumentException("Disk import differs from validated intent");
        if (!current.getAsBoolean() || state != before) return Result.STALE_CONTEXT;
        var receipts = new Object2ObjectOpenHashMap<>(before.receipts());
        receipts.put(commit.operationId(), commit);
        var contents = new DataCenterResearchSnapshot(before.contents().dataCenterId(), before.contents().level(), before.contents().capacity(),
            Math.incrementExact(before.contents().revision()), commit.after().storedResearch());
        state = new State(contents, receipts);
        changed.run();
        return Result.UPDATED;
    }

    /**
     * Remove only exact terminal evidence; callers must still reliably persist a missing receipt.
     */
    public void removeDiskReceipt(ResearchDiskCommit commit, BooleanSupplier current) {
        ownerCheck.run();
        var before = state;
        if (!commit.before().dataCenterId().equals(before.contents().dataCenterId()))
            throw new IllegalArgumentException("Disk receipt belongs to another database");
        if (!current.getAsBoolean() || state != before)
            throw new IllegalStateException("Database import binding is stale");
        var existing = before.receipts().get(commit.operationId());
        if (existing == null) return;
        if (!existing.equals(commit)) throw new IllegalStateException("Conflicting database import receipt");
        var receipts = new Object2ObjectOpenHashMap<>(before.receipts());
        receipts.remove(commit.operationId());
        var snapshot = before.contents();
        state = new State(new DataCenterResearchSnapshot(snapshot.dataCenterId(), snapshot.level(), snapshot.capacity(),
            Math.incrementExact(snapshot.revision()), snapshot.storedResearch()), receipts);
        changed.run();
    }

    @Override
    public DataCenterResearchSnapshot snapshot() {
        ownerCheck.run();
        return state.contents();
    }

    @Override
    public boolean current(DataCenterResearchSnapshot snapshot) {
        ownerCheck.run();
        return state.contents() == snapshot;
    }

    @Override
    public Result downloadResearch(ResourceLocation researchId, int requestedLevel, TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = state.contents();
        if (!current.getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        var definition = registry.require(researchId);
        if (definition.specialOnly()) {
            return Result.SPECIAL_ONLY_REJECTED;
        }
        if (definition.deliveryPolicy() != ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
            return Result.ITEM_ONLY_REJECTED;
        }
        int target;
        if (definition.maxLevel() > 1) {
            target = cloud.completedLevel(researchId);
            if (target < 1) {
                return Result.MISSING_CLOUD_RESEARCH_LEVEL;
            }
        } else {
            definition.stageForLevel(requestedLevel);
            target = requestedLevel;
            if (cloud.completedLevel(researchId) < target) {
                return Result.MISSING_CLOUD_RESEARCH_LEVEL;
            }
        }
        definition.stageForLevel(target);
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        var result = store(definition, target, before, replacement, cloud);
        return result.accepted() ? publish(before, replacement, current, result == Result.UPDATED) : result;
    }

    @Override
    public Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = state.contents();
        if (!current.getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        var result = storeFromDisk(entries, before, cloud, replacement);
        return result.accepted() ? publish(before, replacement, current, result == Result.UPDATED) : result;
    }

    /**
     * Applies a disk batch to a caller-owned zero-default candidate map; no live storage is written.
     */
    public static Result storeFromDisk(Object2IntMap<ResourceLocation> entries, DataCenterResearchSnapshot before,
                                       TeamResearchView cloud, Object2IntOpenHashMap<ResourceLocation> replacement) {
        boolean updated = false;
        for (var entry : entries.object2IntEntrySet()) {
            var definition = cloud.registry().require(entry.getKey());
            var result = store(definition, entry.getIntValue(), before, replacement, cloud);
            if (!result.accepted()) {
                return result;
            }
            updated |= result == Result.UPDATED;
        }
        return updated ? Result.UPDATED : Result.UNCHANGED;
    }

    /**
     * Adds item-provided levels to a detached candidate. Every entry must fit before any content is
     * published; a full research cannot consume another input or produce its return item.
     */
    public static Result addFromItems(Object2IntMap<ResourceLocation> entries, DataCenterResearchSnapshot before,
                                      TeamResearchView cloud, Object2IntOpenHashMap<ResourceLocation> replacement) {
        for (var entry : entries.object2IntEntrySet()) {
            var definition = cloud.registry().require(entry.getKey());
            int previous = replacement.getInt(entry.getKey());
            int increment = entry.getIntValue();
            if (increment > definition.maxLevel() - previous) return Result.MAX_LEVEL_REACHED;
            var result = validateStore(definition, previous + increment, before, replacement, cloud);
            if (!result.accepted()) return result;
            replacement.addTo(entry.getKey(), increment);
        }
        return Result.UPDATED;
    }

    @Override
    public Result removeResearch(ResourceLocation researchId, BooleanSupplier current) {
        ownerCheck.run();
        var before = state.contents();
        if (!current.getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        if (!before.storedResearch().containsKey(researchId)) return Result.UNCHANGED;
        for (var receipt : state.receipts().values())
            if (receipt.entries().containsKey(researchId)) return Result.BUSY;
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        replacement.removeInt(researchId);
        return publish(before, replacement, current, true);
    }

    @Override
    public Result synchronizeLoopingResearch(TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = state.contents();
        if (!current.getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        Object2IntOpenHashMap<ResourceLocation> replacement = null;
        for (var entry : before.storedResearch().object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            int target = cloud.completedLevel(entry.getKey());
            if (!definition.specialOnly() && definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM
                && definition.maxLevel() > 1 && target > entry.getIntValue()
                && definition.researchTierForLevel(target) <= before.level()) {
                if (replacement == null) replacement = new Object2IntOpenHashMap<>(before.storedResearch());
                replacement.put(entry.getKey(), target);
            }
        }
        if (replacement == null) return Result.UNCHANGED;
        for (var entry : replacement.object2IntEntrySet()) {
            if (entry.getIntValue() > before.storedResearch().getInt(entry.getKey())
                && mutexBlocked(registry.require(entry.getKey()), entry.getIntValue(), replacement, cloud.sharedLevels(), registry)) {
                return Result.MUTEX_BLOCKED;
            }
        }
        return publish(before, replacement, current, true);
    }

    private static Result store(ResearchDefinition definition, int target, DataCenterResearchSnapshot before,
                                Object2IntOpenHashMap<ResourceLocation> replacement, TeamResearchView cloud) {
        var result = validateStore(definition, target, before, replacement, cloud);
        if (!result.accepted()) return result;
        if (target > replacement.getInt(definition.id())) {
            replacement.put(definition.id(), target);
            return Result.UPDATED;
        }
        return Result.UNCHANGED;
    }

    private static Result validateStore(ResearchDefinition definition, int target, DataCenterResearchSnapshot before,
                                        Object2IntOpenHashMap<ResourceLocation> replacement, TeamResearchView cloud) {
        if (definition.researchTierForLevel(target) > before.level()) {
            return Result.DATA_CENTER_LEVEL_TOO_LOW;
        }
        if (mutexBlocked(definition, target, replacement, cloud.sharedLevels(), cloud.registry())) {
            return Result.MUTEX_BLOCKED;
        }
        if (!replacement.containsKey(definition.id()) && replacement.size() >= before.capacity()) {
            return Result.CAPACITY_FULL;
        }
        return Result.UPDATED;
    }

    private static boolean mutexBlocked(ResearchDefinition candidate, int target, Object2IntMap<ResourceLocation> stored,
                                        Object2IntMap<ResourceLocation> cloud, ResearchRegistry registry) {
        var elements = candidate.mutexRulesForLevel(target);
        for (int index = 0; index < elements.size(); index++) {
            var rule = elements.get(index);
            if (Math.max(stored.getInt(rule.researchId()), cloud.getInt(rule.researchId())) >= rule.minLevel()) {
                return true;
            }
        }
        for (var entry : stored.object2IntEntrySet()) {
            if (entry.getKey().equals(candidate.id()) || entry.getIntValue() < 1) {
                continue;
            }
            var elements2 = registry.require(entry.getKey()).mutexRulesForLevel(Math.max(entry.getIntValue(), cloud.getInt(entry.getKey())));
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var rule = elements2.get(index2);
                if (rule.researchId().equals(candidate.id()) && target >= rule.minLevel()) {
                    return true;
                }
            }
        }
        for (var entry : cloud.object2IntEntrySet()) {
            if (entry.getKey().equals(candidate.id()) || stored.containsKey(entry.getKey()) || entry.getIntValue() < 1)
                continue;
            var rules = registry.require(entry.getKey()).mutexRulesForLevel(entry.getIntValue());
            for (int index = 0; index < rules.size(); index++) {
                var rule = rules.get(index);
                if (rule.researchId().equals(candidate.id()) && target >= rule.minLevel()) return true;
            }
        }
        return false;
    }

    private static void validateContents(DataCenterResearchSnapshot saved, ResearchRegistry registry) {
        var levels = saved.storedResearch();
        for (var entry : levels.object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            if (definition.researchTierForLevel(entry.getIntValue()) > saved.level()
                || mutexBlocked(definition, entry.getIntValue(), levels, levels, registry)) {
                throw new IllegalArgumentException("Saved data-center research violates tier or mutex: " + entry.getKey());
            }
        }
    }

    private Result publish(DataCenterResearchSnapshot before, Object2IntMap<ResourceLocation> replacement, BooleanSupplier current, boolean updated) {
        if (!current.getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        if (!updated) return Result.UNCHANGED;
        long revision = Math.incrementExact(state.contents().revision());
        state = new State(new DataCenterResearchSnapshot(state.contents().dataCenterId(), state.contents().level(), state.contents().capacity(), revision, replacement), state.receipts());
        changed.run();
        return Result.UPDATED;
    }

    private void requireCloud(TeamResearchView cloud) {
        if (cloud.registry() != registry) {
            throw new IllegalArgumentException("Data-center cloud has another research registry");
        }
    }

    /**
     * Contents and receipts are completed data borrowed read-only for this publication's lifetime.
     */
    public record State(DataCenterResearchSnapshot contents, Map<UUID, ResearchDiskCommit> receipts) {
    }
}
