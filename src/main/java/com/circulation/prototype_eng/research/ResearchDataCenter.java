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

import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** PN database rules with one atomic content publication and monotonic owner-side revision. */
public final class ResearchDataCenter implements DataCenterStorage {
    private final ResearchRegistry registry;
    private final Runnable ownerCheck;
    private final Runnable changed;
    private State state;

    public ResearchDataCenter(UUID id, int level, int capacity, ResearchRegistry registry, Runnable ownerCheck, Runnable changed) {
        this.registry = Objects.requireNonNull(registry);
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Data-center storage requires frozen research");
        }
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
        this.changed = Objects.requireNonNull(changed);
        state = new State(new DataCenterResearchSnapshot(id, level, capacity, 0, new Object2IntOpenHashMap<>()), Map.of());
    }

    /**
     * Restore complete validated saved contents into a fresh backend; runtime revisions start in this new session.
     */
    public static ResearchDataCenter restore(DataCenterResearchSnapshot saved, ResearchRegistry registry,
                                             Runnable ownerCheck, Runnable changed) {
        var restored = new ResearchDataCenter(saved.dataCenterId(), saved.level(), saved.capacity(), registry, ownerCheck, changed);
        var levels = new Object2IntOpenHashMap<>(saved.storedResearch());
        restored.validateContents(levels);
        restored.state = new State(new DataCenterResearchSnapshot(saved.dataCenterId(), saved.level(), saved.capacity(), 0, levels), Map.of());
        return restored;
    }


    /** Restore the complete atomic component state, retaining every unresolved import receipt. */
    public static ResearchDataCenter restore(State saved, ResearchRegistry registry, Runnable ownerCheck, Runnable changed) {
        var restored = restore(saved.contents(), registry, ownerCheck, changed);
        for (var receipt : saved.receipts().values()) {
            new ResearchDiskData(registry, receipt.diskTier(), receipt.entries());
            restored.validateContents(receipt.before().storedResearch());
            restored.validateContents(receipt.after().storedResearch());
        }
        restored.state = new State(restored.snapshot(), saved.receipts());
        return restored;
    }

    public State persistentState() {
        ownerCheck.run();
        return state;
    }

    @Nullable
    public ResearchDiskCommit receipt(UUID operationId) {
        ownerCheck.run();
        return state.receipts().get(Objects.requireNonNull(operationId));
    }

    /**
     * Atomic content/receipt primitive. Durability is a separate physical-host responsibility.
     * A rejected return has no effect; failures after publication retain the entire new state.
     */
    public Result commitDisk(ResearchDiskCommit commit, TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        Objects.requireNonNull(commit);
        var existing = state.receipts().get(commit.operationId());
        if (existing != null) {
            if (!existing.equals(commit)) throw new IllegalStateException("Conflicting database import receipt");
            return Result.UNCHANGED;
        }
        requireCloud(cloud);
        var before = state;
        if (!Objects.requireNonNull(current).getAsBoolean() || state != before || !commit.matchesBefore(before.contents())) {
            return Result.STALE_CONTEXT;
        }
        if (!commit.teamId().equals(cloud.teamId())) throw new IllegalArgumentException("Disk import belongs to another team");
        var data = new ResearchDiskData(registry, commit.diskTier(), commit.entries());
        var prepared = ResearchDiskImport.prepare(commit.operationId(), commit.actor(), commit.teamId(), data, before.contents(), cloud);
        if (!prepared.result().accepted()) {
            return Objects.requireNonNull(prepared.result().storageResult());
        }
        if (!Objects.requireNonNull(prepared.commit()).equals(commit)) throw new IllegalArgumentException("Disk import differs from validated intent");
        if (!current.getAsBoolean() || state != before) return Result.STALE_CONTEXT;
        var receipts = new Object2ObjectOpenHashMap<>(before.receipts());
        receipts.put(commit.operationId(), commit);
        var contents = new DataCenterResearchSnapshot(before.contents().dataCenterId(), before.contents().level(), before.contents().capacity(),
            Math.incrementExact(before.contents().revision()), commit.after().storedResearch());
        state = new State(contents, receipts);
        changed.run();
        return Result.UPDATED;
    }

    /** Remove only exact terminal evidence; callers must still reliably persist a missing receipt. */
    public void removeDiskReceipt(ResearchDiskCommit commit, BooleanSupplier current) {
        ownerCheck.run();
        Objects.requireNonNull(commit);
        var before = state;
        if (!commit.before().dataCenterId().equals(before.contents().dataCenterId()))
            throw new IllegalArgumentException("Disk receipt belongs to another database");
        if (!Objects.requireNonNull(current).getAsBoolean() || state != before) throw new IllegalStateException("Database import binding is stale");
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

    /** One publication and serialization boundary for contents and all unresolved operation receipts. */
    public record State(DataCenterResearchSnapshot contents, Map<UUID, ResearchDiskCommit> receipts) {
        public State {
            Objects.requireNonNull(contents);
            var stable = new Object2ObjectOpenHashMap<UUID, ResearchDiskCommit>();
            for (var entry : Objects.requireNonNull(receipts).entrySet()) {
                var commit = Objects.requireNonNull(entry.getValue());
                if (!Objects.requireNonNull(entry.getKey()).equals(commit.operationId())
                    || !commit.before().dataCenterId().equals(contents.dataCenterId())
                    || commit.before().level() != contents.level() || commit.before().capacity() != contents.capacity()) {
                    throw new IllegalArgumentException("Database receipt identity or metadata differs");
                }
                for (var stored : commit.after().storedResearch().object2IntEntrySet()) {
                    if (contents.storedResearch().getInt(stored.getKey()) < stored.getIntValue()) {
                        throw new IllegalArgumentException("Database contents lost a committed disk import");
                    }
                }
                stable.put(entry.getKey(), commit);
            }
            receipts = Object2ObjectMaps.unmodifiable(stable);
        }
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
        if (!Objects.requireNonNull(current).getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
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
        var result = store(definition, target, replacement, cloud.sharedLevels());
        return result.accepted() ? publish(before, replacement, current) : result;
    }

    @Override
    public Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = state.contents();
        if (!Objects.requireNonNull(current).getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        for (var entry : Objects.requireNonNull(entries).object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            definition.stageForLevel(entry.getIntValue());
            var result = store(definition, entry.getIntValue(), replacement, cloud.sharedLevels());
            if (!result.accepted()) {
                return result;
            }
        }
        return publish(before, replacement, current);
    }

    @Override
    public Result synchronizeLoopingResearch(TeamResearchView cloud, BooleanSupplier current) {
        ownerCheck.run();
        requireCloud(cloud);
        var before = state.contents();
        if (!Objects.requireNonNull(current).getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        var replacement = new Object2IntOpenHashMap<>(before.storedResearch());
        for (var entry : before.storedResearch().object2IntEntrySet()) {
            var definition = registry.require(entry.getKey());
            int target = cloud.completedLevel(entry.getKey());
            if (!definition.specialOnly() && definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM
                && definition.maxLevel() > 1 && target > entry.getIntValue()
                && definition.researchTierForLevel(target) <= state.contents().level()) {
                replacement.put(entry.getKey(), target);
            }
        }
        var visible = new Object2IntOpenHashMap<>(cloud.sharedLevels());
        for (var entry : replacement.object2IntEntrySet()) {
            if (entry.getIntValue() > visible.getInt(entry.getKey())) {
                visible.put(entry.getKey(), entry.getIntValue());
            }
        }
        for (var entry : replacement.object2IntEntrySet()) {
            if (entry.getIntValue() > state.contents().storedResearch().getInt(entry.getKey())
                && mutexBlocked(registry.require(entry.getKey()), entry.getIntValue(), visible)) {
                return Result.MUTEX_BLOCKED;
            }
        }
        return publish(before, replacement, current);
    }

    private Result store(ResearchDefinition definition, int target, Object2IntOpenHashMap<ResourceLocation> replacement,
                         Object2IntMap<ResourceLocation> cloud) {
        if (definition.researchTierForLevel(target) > state.contents().level()) {
            return Result.DATA_CENTER_LEVEL_TOO_LOW;
        }
        var visible = new Object2IntOpenHashMap<>(cloud);
        for (var entry : replacement.object2IntEntrySet()) {
            if (entry.getIntValue() > visible.getInt(entry.getKey())) {
                visible.put(entry.getKey(), entry.getIntValue());
            }
        }
        if (mutexBlocked(definition, target, visible)) {
            return Result.MUTEX_BLOCKED;
        }
        if (!replacement.containsKey(definition.id()) && replacement.size() >= state.contents().capacity()) {
            return Result.CAPACITY_FULL;
        }
        if (target > replacement.getInt(definition.id())) {
            replacement.put(definition.id(), target);
            return Result.UPDATED;
        }
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
            if (definition.researchTierForLevel(entry.getIntValue()) > state.contents().level()
                || mutexBlocked(definition, entry.getIntValue(), levels)) {
                throw new IllegalArgumentException("Saved data-center research violates tier or mutex: " + entry.getKey());
            }
        }
    }

    private Result publish(DataCenterResearchSnapshot before, Object2IntMap<ResourceLocation> replacement, BooleanSupplier current) {
        if (!current.getAsBoolean() || state.contents() != before) return Result.STALE_CONTEXT;
        if (state.contents().storedResearch().equals(replacement)) {
            return Result.UNCHANGED;
        }
        long revision = Math.incrementExact(state.contents().revision());
        state = new State(new DataCenterResearchSnapshot(state.contents().dataCenterId(), state.contents().level(), state.contents().capacity(), revision, replacement), state.receipts());
        changed.run();
        return Result.UPDATED;
    }

    private void requireCloud(TeamResearchView cloud) {
        if (Objects.requireNonNull(cloud).registry() != registry) { throw new IllegalArgumentException("Data-center cloud has another research registry"); }
    }
}
