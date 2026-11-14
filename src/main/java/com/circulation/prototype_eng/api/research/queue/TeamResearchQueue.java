package com.circulation.prototype_eng.api.research.queue;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-thread research queue. Progress is stored by research ID and target level and survives queue removal.
 * worldTime must use persistent server time, not a scheduler counter that resets on restart.
 */
@SuppressWarnings("unused")
public final class TeamResearchQueue {
    public static final int CAPACITY = 5;
    private final ResearchRegistry registry;
    private final String teamId;
    private final Thread ownerThread = Thread.currentThread();
    private final ObjectArrayList<Entry> entries = new ObjectArrayList<>(CAPACITY);
    private @Nullable ObjectList<Entry> displayEntries;
    private final Object2ObjectOpenHashMap<ResearchProgress.Target, ResearchProgress> progress = new Object2ObjectOpenHashMap<>();
    private long nextEntryId = 1;
    private long epoch;
    private long updatedAtWorldTime;
    private long lastGrantedEntryId = -1;
    private long lastGrantedWorldTime = -1;
    private @Nullable ReversibleTransaction pendingMutation;

    public TeamResearchQueue(ResearchRegistry registry, String teamId) {
        if (!registry.isFrozen()) throw new IllegalStateException("Research queue requires frozen definitions");
        if (teamId.isBlank()) throw new IllegalArgumentException("Research queue requires a team");
        this.registry = registry;
        this.teamId = teamId;
    }

    public static TeamResearchQueue restore(ResearchRegistry registry, Snapshot saved) {
        var queue = new TeamResearchQueue(registry, saved.teamId());
        var ids = new LongOpenHashSet();
        var targets = new ObjectOpenHashSet<ResearchProgress.Target>();
        for (var entry : saved.entries()) {
            var definition = registry.require(entry.key().researchId());
            definition.stageForLevel(entry.key().targetLevel());
            if (definition.specialOnly() || !ids.add(entry.key().queueEntryId()) || !targets.add(entry.key().target())
                || entry.key().queueEntryId() >= saved.nextEntryId() || entry.createdAtWorldTime() > saved.updatedAtWorldTime())
                throw new IllegalArgumentException("Invalid saved research queue entry");
        }
        for (var state : saved.progress()) {
            var work = new ResearchProgress(registry, state.target());
            work.restore(state);
            if (queue.progress.putIfAbsent(state.target(), work) != null)
                throw new IllegalArgumentException("Duplicate saved research progress");
        }
        for (var entry : saved.entries())
            if (!queue.progress.containsKey(entry.key().target()))
                throw new IllegalArgumentException("Queued research lost its progress");
        queue.entries.addAll(saved.entries());
        queue.nextEntryId = saved.nextEntryId();
        queue.epoch = saved.epoch();
        queue.updatedAtWorldTime = saved.updatedAtWorldTime();
        queue.lastGrantedEntryId = saved.lastGrantedEntryId();
        queue.lastGrantedWorldTime = saved.lastGrantedWorldTime();
        return queue;
    }

    public String teamId() {
        requireThread();
        return teamId;
    }

    public ResearchRegistry registry() {
        return registry;
    }

    public long epoch() {
        requireThread();
        return epoch;
    }

    public int size() {
        requireThread();
        return entries.size();
    }

    public boolean busy() {
        requireThread();
        return pendingMutation != null;
    }

    public boolean containsTarget(ResourceLocation id, int level) {
        requireThread();
        for (var entry : entries)
            if (entry.key().researchId().equals(id) && entry.key().targetLevel() == level) return true;
        return false;
    }

    /**
     * Borrowed read-only list, reused while queue order stays unchanged.
     */
    public ObjectList<Entry> queueSnapshot() {
        requireThread();
        if (displayEntries == null) displayEntries = new ObjectArrayList<>(entries);
        return displayEntries;
    }

    public Optional<Entry> activeHead() {
        requireThread();
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
    }

    public @Nullable ResearchProgress progress(ResourceLocation id, int level) {
        requireThread();
        return progress.get(new ResearchProgress.Target(id, level));
    }

    public @Nullable ResearchProgress activeProgress() {
        requireThread();
        return entries.isEmpty() ? null : progress.get(entries.getFirst().key().target());
    }

    /**
     * Returns current resource requirements read-only.
     */
    public Reference2DoubleMap<NetworkResourceType> resourceProgressSnapshot() {
        var active = activeProgress();
        return active == null ? Reference2DoubleMaps.emptyMap() : active.snapshot().resources();
    }

    public Entry enqueue(ResourceLocation researchId, int targetLevel, UUID requestedBy, long worldTime) {
        requireBoundary();
        var definition = registry.require(researchId);
        definition.stageForLevel(targetLevel);
        if (definition.specialOnly() || entries.size() >= CAPACITY || containsTarget(researchId, targetLevel))
            throw new IllegalStateException("Research target cannot enter the queue");
        var key = new Key(researchId, targetLevel, nextEntryId);
        nextEntryId = Math.incrementExact(nextEntryId);
        var entry = new Entry(key, requestedBy, worldTime);
        var target = key.target();
        if (!progress.containsKey(target)) progress.put(target, new ResearchProgress(registry, target));
        entries.add(entry);
        displayEntries = null;
        changed(worldTime);
        return entry;
    }

    public boolean move(Key key, int index, long worldTime) {
        requireBoundary();
        int previous = indexOf(key);
        if (previous < 0 || index < 0 || index >= entries.size()) return false;
        if (previous != index) {
            entries.add(index, entries.remove(previous));
            displayEntries = null;
            changed(worldTime);
        }
        return true;
    }

    public @Nullable Entry remove(Key key, long worldTime) {
        requireBoundary();
        int index = indexOf(key);
        if (index < 0) return null;
        var removed = entries.remove(index);
        displayEntries = null;
        changed(worldTime);
        return removed;
    }

    public void removeHead(Key key) {
        requireHead(key);
        remove(key, updatedAtWorldTime);
    }

    public void removeUnstarted(Key key) {
        Objects.requireNonNull(remove(key, updatedAtWorldTime));
    }

    public void completeHead(Key key) {
        removeHead(key);
        progress.remove(key.target());
    }

    public boolean advancedAt(long worldTime) {
        requireThread();
        return lastGrantedWorldTime == worldTime;
    }

    /**
     * Records work ticks and remaining materials with the world checkpoint after settling player receipts.
     */
    public void recordManualProgress(Key key, long elapsed, AEKey2LongMap remaining, long worldTime) {
        requireBoundary();
        requireHead(key);
        var work = Objects.requireNonNull(activeProgress());
        if (work.stage().researchTier() != 0)
            throw new IllegalArgumentException("Manual progress requires tier-zero research");
        work.restore(new ResearchProgress.Snapshot(key.target(), elapsed, remaining, Reference2DoubleMaps.emptyMap()));
        lastGrantedEntryId = key.queueEntryId();
        lastGrantedWorldTime = worldTime;
        changed(worldTime);
    }

    public Optional<SegmentPlan> planSegment(Key key, List<ResearchProgress.StationRate> stations,
                                             Reference2DoubleMap<NetworkResourceType> available,
                                             AEKey2LongMap materials, long worldTime) {
        requireBoundary();
        var head = requireHead(key);
        if (worldTime < lastGrantedWorldTime) throw new IllegalArgumentException("Research time moved backwards");
        if (worldTime == lastGrantedWorldTime) return Optional.empty();
        var work = Objects.requireNonNull(activeProgress());
        if (work.stage().researchTier() == 0)
            throw new IllegalStateException("Tier-zero research requires manual execution");
        var step = work.plan(stations, available, materials);
        return step == null ? Optional.empty() : Optional.of(new SegmentPlan(this, head, epoch, worldTime, step));
    }

    public GrantMutation prepareGrantSegment(SegmentPlan plan) {
        requireBoundary();
        validatePlan(plan);
        var mutation = new GrantMutation(this, plan);
        pendingMutation = mutation;
        return mutation;
    }

    public PruneMutation preparePrune(TeamResearchView view) {
        requireBoundary();
        if (view.registry() != registry || !view.teamId().equals(teamId))
            throw new IllegalArgumentException("Research view belongs to another team");
        var eligibility = new ResearchEligibility(registry);
        var removed = new ObjectArrayList<Entry>();
        for (var entry : entries)
            if (!eligibility.structurallyEligible(view, entry.key().researchId(), entry.key().targetLevel()))
                removed.add(entry);
        var mutation = new PruneMutation(this, removed, queueSnapshot());
        pendingMutation = mutation;
        return mutation;
    }

    public Snapshot snapshot() {
        requireBoundary();
        var saved = new ObjectArrayList<ResearchProgress.Snapshot>(progress.size());
        for (var work : progress.values()) saved.add(work.snapshot());
        return new Snapshot(teamId, queueSnapshot(), saved, nextEntryId, epoch, updatedAtWorldTime, lastGrantedEntryId, lastGrantedWorldTime);
    }

    private int indexOf(Key key) {
        for (int index = 0; index < entries.size(); index++) if (entries.get(index).key().equals(key)) return index;
        return -1;
    }

    private Entry requireHead(Key key) {
        if (entries.isEmpty() || !entries.getFirst().key().equals(key))
            throw new IllegalStateException("Research target is no longer the queue head");
        return entries.getFirst();
    }

    private void changed(long worldTime) {
        epoch = Math.incrementExact(epoch);
        updatedAtWorldTime = Math.max(updatedAtWorldTime, worldTime);
    }

    private void validatePlan(SegmentPlan plan) {
        requireHead(plan.key());
        if (plan.owner != this || plan.queueEpoch != epoch) throw new IllegalStateException("Research plan is stale");
    }

    private void requireThread() {
        if (Thread.currentThread() != ownerThread)
            throw new IllegalStateException("Research queue requires its owner thread");
    }

    private void requireBoundary() {
        requireThread();
        if (pendingMutation != null) throw new IllegalStateException("Research queue has an unsettled transaction");
    }

    public record Key(ResourceLocation researchId, int targetLevel, long queueEntryId) {
        public Key {
            Objects.requireNonNull(researchId);
            if (targetLevel < 1 || queueEntryId < 1) throw new IllegalArgumentException("Invalid queued research key");
        }

        public ResearchProgress.Target target() {
            return new ResearchProgress.Target(researchId, targetLevel);
        }
    }

    public record Entry(Key key, UUID requestedBy, long createdAtWorldTime) {
        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(requestedBy);
            if (createdAtWorldTime < 0) throw new IllegalArgumentException("Negative research request time");
        }
    }

    /**
     * The queue and target progress are completed snapshots and must not be modified.
     */
    public record Snapshot(String teamId, ObjectList<Entry> entries, ObjectList<ResearchProgress.Snapshot> progress,
                           long nextEntryId, long epoch, long updatedAtWorldTime, long lastGrantedEntryId,
                           long lastGrantedWorldTime) {
        public Snapshot {
            if (entries.size() > CAPACITY || nextEntryId < 1 || epoch < 0 || updatedAtWorldTime < 0
                || lastGrantedEntryId >= nextEntryId || lastGrantedWorldTime > updatedAtWorldTime || lastGrantedWorldTime < -1)
                throw new IllegalArgumentException("Invalid saved research queue counters");
        }

        public @Nullable Key activeHead() {
            return entries.isEmpty() ? null : entries.getFirst().key();
        }

        public Reference2DoubleMap<NetworkResourceType> resourceProgress() {
            var head = activeHead();
            if (head != null)
                for (var state : progress) if (state.target().equals(head.target())) return state.resources();
            return Reference2DoubleMaps.emptyMap();
        }
    }

    public static final class SegmentPlan {
        private final TeamResearchQueue owner;
        private final Entry head;
        private final long queueEpoch;
        private final long worldTime;
        private final ResearchProgress.Step step;

        private SegmentPlan(TeamResearchQueue owner, Entry head, long queueEpoch, long worldTime, ResearchProgress.Step step) {
            this.owner = owner;
            this.head = head;
            this.queueEpoch = queueEpoch;
            this.worldTime = worldTime;
            this.step = step;
        }

        public Key key() {
            return head.key();
        }

        public long queueEpoch() {
            return queueEpoch;
        }

        public long worldTime() {
            return worldTime;
        }

        public List<UUID> stationIds() {
            return step.stations();
        }

        public Reference2DoubleMap<NetworkResourceType> progressBefore() {
            return step.before().resources();
        }

        public Reference2DoubleMap<NetworkResourceType> progressCeiling() {
            return step.after().resources();
        }

        public Reference2DoubleMap<NetworkResourceType> grantedResources() {
            return step.grants();
        }

        public Reference2DoubleMap<NetworkResourceType> capacity() {
            return step.capacity();
        }

        public AEKey2LongMap payments() {
            return step.payments();
        }

        public boolean completesResearch() {
            return step.complete();
        }
    }

    public record GrantResult(Entry entry, List<UUID> stationIds,
                              Reference2DoubleMap<NetworkResourceType> grantedResources, boolean completedResearch) {
    }

    public static final class GrantMutation implements ReversibleTransaction {
        private final TeamResearchQueue owner;
        private final SegmentPlan plan;
        private final ResearchProgress work;
        private final long previousTime, previousGrant, previousGrantTime;
        private boolean committed;
        private Resolution resolution = Resolution.REVERSIBLE;

        private GrantMutation(TeamResearchQueue owner, SegmentPlan plan) {
            this.owner = owner;
            this.plan = plan;
            work = Objects.requireNonNull(owner.activeProgress());
            previousTime = owner.updatedAtWorldTime;
            previousGrant = owner.lastGrantedEntryId;
            previousGrantTime = owner.lastGrantedWorldTime;
        }

        public GrantResult result() {
            return new GrantResult(plan.head, plan.stationIds(), plan.grantedResources(), plan.completesResearch());
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }

        @Override
        public void commit() {
            owner.validatePlan(plan);
            work.apply(plan.step.after());
            if (plan.completesResearch()) {
                owner.entries.removeFirst();
                owner.displayEntries = null;
                owner.progress.remove(plan.key().target());
            }
            owner.lastGrantedEntryId = plan.key().queueEntryId();
            owner.lastGrantedWorldTime = plan.worldTime;
            owner.changed(plan.worldTime);
            committed = true;
        }

        @Override
        public void complete() {
            owner.pendingMutation = null;
            resolution = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            if (committed) {
                work.apply(plan.step.before());
                if (plan.completesResearch()) {
                    owner.entries.addFirst(plan.head);
                    owner.displayEntries = null;
                    owner.progress.put(work.target(), work);
                }
                owner.updatedAtWorldTime = previousTime;
                owner.lastGrantedEntryId = previousGrant;
                owner.lastGrantedWorldTime = previousGrantTime;
                owner.epoch = Math.incrementExact(owner.epoch);
                committed = false;
            }
            owner.pendingMutation = null;
            resolution = Resolution.ROLLED_BACK;
        }
    }

    public static final class PruneMutation implements ReversibleTransaction {
        private final TeamResearchQueue owner;
        private final ObjectList<Entry> removed, before;
        private boolean committed;
        private Resolution resolution = Resolution.REVERSIBLE;

        private PruneMutation(TeamResearchQueue owner, ObjectList<Entry> removed, ObjectList<Entry> before) {
            this.owner = owner;
            this.removed = removed;
            this.before = before;
        }

        public ObjectList<Entry> removed() {
            return removed;
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }

        @Override
        public void commit() {
            owner.entries.removeAll(removed);
            owner.displayEntries = null;
            owner.changed(owner.updatedAtWorldTime);
            committed = true;
        }

        @Override
        public void complete() {
            owner.pendingMutation = null;
            resolution = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            if (committed) {
                owner.entries.clear();
                owner.entries.addAll(before);
                owner.displayEntries = null;
                owner.changed(owner.updatedAtWorldTime);
                committed = false;
            }
            owner.pendingMutation = null;
            resolution = Resolution.ROLLED_BACK;
        }
    }
}
