package com.circulation.prototype_eng.api.research.queue;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One team's owner-thread queue. Numeric work accumulates on its head; logical resources are leased
 * by the station consumer and never become progress. This class does not grant network authority.
 * All worldTime arguments use one persistent server timeline, such as overworld totalWorldTime;
 * per-dimension station times and a restart-resetting server tick counter are invalid inputs.
 */
@SuppressWarnings("unused")
public final class TeamResearchQueue {
    public static final int CAPACITY = 5;
    private final ResearchRegistry registry;
    private final NetworkResourceRegistry resources;
    private final String teamId;
    private final Thread ownerThread = Thread.currentThread();
    private final ObjectArrayList<Entry> entries = new ObjectArrayList<>(CAPACITY);
    private final Object2LongOpenHashMap<NetworkResourceType> progress = new Object2LongOpenHashMap<>();
    private long nextEntryId = 1;
    private long epoch;
    private long updatedAtWorldTime;
    private long lastGrantedEntryId = -1;
    private long lastGrantedWorldTime = -1;
    @Nullable
    private Object pendingMutation;

    public TeamResearchQueue(ResearchRegistry registry, NetworkResourceRegistry resources, String teamId) {
        this.registry = Objects.requireNonNull(registry);
        this.resources = Objects.requireNonNull(resources);
        this.teamId = Objects.requireNonNull(teamId);
        if (!registry.isFrozen() || !resources.isFrozen()) {
            throw new IllegalStateException("Research queue requires frozen registries");
        }
        if (teamId.isBlank() || !teamId.equals(teamId.trim())) {
            throw new IllegalArgumentException("Invalid research queue team id");
        }
    }

    /**
     * Call on the actual runtime owner thread. Saved data contains no owner-thread or plan authority.
     */
    public static TeamResearchQueue restore(ResearchRegistry registry, NetworkResourceRegistry resources, Snapshot saved) {
        Objects.requireNonNull(saved);
        var queue = new TeamResearchQueue(registry, resources, saved.teamId());
        queue.validateSnapshot(saved);
        queue.replace(saved);
        return queue;
    }

    private static ObjectSet<UUID> stations(Collection<UUID> stationIds) {
        var copy = new ObjectOpenHashSet<UUID>(stationIds.size());
        for (var id : Objects.requireNonNull(stationIds)) {
            if (!copy.add(Objects.requireNonNull(id))) {
                throw new IllegalArgumentException("Duplicate research station");
            }
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Research segment needs contributing stations");
        }
        var ordered = new ObjectArrayList<>(copy);
        ordered.sort(UUID::compareTo);
        return ObjectSets.unmodifiable(new ObjectLinkedOpenHashSet<>(ordered));
    }

    public String teamId() {
        requireThread();
        return teamId;
    }

    public ResearchRegistry registry() {
        requireThread();
        return registry;
    }

    public NetworkResourceRegistry resources() {
        requireThread();
        return resources;
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
        for (var entry : entries) {
            if (entry.key().researchId().equals(Objects.requireNonNull(id)) && entry.key().targetLevel() == level) {
                return true;
            }
        }
        return false;
    }

    public ObjectList<Entry> queueSnapshot() {
        requireThread();
        return ObjectLists.unmodifiable(new ObjectArrayList<>(entries));
    }

    public Optional<Entry> activeHead() {
        requireThread();
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
    }

    public Object2LongMap<NetworkResourceType> resourceProgressSnapshot() {
        requireThread();
        return NetworkResourceModifier.copyResources(progress);
    }

    /**
     * Server owner entry after checking actual membership and the requester-specific start condition.
     * A preview view or client startable flag cannot authorize this call.
     */
    public Entry enqueue(ResourceLocation researchId, int targetLevel, UUID requestedBy, long worldTime) {
        requireBoundary();
        var definition = registry.require(researchId);
        definition.stageForLevel(targetLevel);
        if (definition.specialOnly()) {
            throw new IllegalArgumentException("Special-only research cannot enter an ordinary queue");
        }
        if (entries.size() >= CAPACITY) {
            throw new IllegalStateException("Research queue capacity exceeded");
        }
        for (var entry : entries) {
            if (entry.key().researchId().equals(researchId) && entry.key().targetLevel() == targetLevel) {
                throw new IllegalStateException("Duplicate queued research target");
            }
        }
        long followingId = Math.incrementExact(nextEntryId);
        long followingEpoch = Math.incrementExact(epoch);
        var entry = new Entry(new Key(researchId, targetLevel, nextEntryId), requestedBy, worldTime);
        entries.add(entry);
        nextEntryId = followingId;
        epoch = followingEpoch;
        updatedAtWorldTime = Math.max(updatedAtWorldTime, worldTime);
        return entry;
    }

    /**
     * Station capacity is the sum of the participating stations' rates: min(available, capacity, remaining).
     * Callers must acquire the stations' logical leases independently. No tokens are acquired here.
     * Available quantities are borrowed read-only for this synchronous planning call.
     */
    public Optional<SegmentPlan> planSegment(Key expectedHead, Collection<UUID> stationIds,
                                             Object2LongMap<NetworkResourceType> availableResources,
                                             Object2LongMap<NetworkResourceType> stationCapacity, long worldTime) {
        requireBoundary();
        var head = requireHead(expectedHead);
        if (worldTime < 0) {
            throw new IllegalArgumentException("Negative research segment time");
        }
        if (head.key().queueEntryId() == lastGrantedEntryId) {
            if (worldTime < lastGrantedWorldTime) {
                throw new IllegalStateException("Research grant time moved backwards");
            }
            if (worldTime == lastGrantedWorldTime) {
                return Optional.empty();
            }
        }
        var stations = stations(stationIds);
        NetworkResourceModifier.validateResources(availableResources);
        NetworkResourceModifier.validateResources(stationCapacity);
        for (var type : availableResources.keySet()) {
            resources.canonical(type);
        }
        var stage = registry.require(head.key().researchId()).stageForLevel(head.key().targetLevel());
        if (stage.researchTier() == 0) {
            throw new IllegalStateException("Tier-zero research requires manual execution");
        }
        var demand = stage.resourceDemandForLevel(head.key().targetLevel());
        var ceiling = new Object2LongOpenHashMap<>(progress);
        var grants = new Object2LongOpenHashMap<NetworkResourceType>(demand.size());
        boolean complete = true;
        for (var entry : demand.object2LongEntrySet()) {
            var type = entry.getKey();
            long before = progress.getLong(type);
            long remaining = entry.getLongValue() - before;
            if (remaining <= 0) {
                continue;
            }
            long cap = stationCapacity.getOrDefault(type, 0L);
            long grant = Math.min(remaining, Math.min(availableResources.getOrDefault(type, 0L), cap));
            if (grant > 0) {
                grants.put(type, grant);
                ceiling.addTo(type, grant);
            }
            if (ceiling.getLong(type) < entry.getLongValue()) {
                complete = false;
            }
        }
        if (grants.isEmpty() && !complete) {
            return Optional.empty();
        }
        return Optional.of(new SegmentPlan(this, head, stations, epoch, worldTime, resourceProgressSnapshot(),
            ceiling, grants,
            stage.materialRequirements(), complete));
    }

    /**
     * Prepare before material/resource commit, then commit this exact plan, settle the enclosing
     * transaction, and complete. Until complete/rollback, other queue writes and saves are rejected.
     */
    public GrantMutation prepareGrantSegment(SegmentPlan plan) {
        requireBoundary();
        validatePlan(plan);
        requireMutationEpochCapacity();
        var mutation = new GrantMutation(this, plan, snapshot());
        pendingMutation = mutation;
        return mutation;
    }

    /**
     * Rechecks structural next-level eligibility; a changed start condition does not cancel existing work.
     */
    public PruneMutation preparePrune(TeamResearchView view) {
        requireBoundary();
        requireView(view);
        requireMutationEpochCapacity();
        var canceled = new ObjectArrayList<Entry>();
        var eligibility = new ResearchEligibility(registry);
        for (var entry : entries) {
            if (!eligibility.structurallyEligible(view, entry.key().researchId(), entry.key().targetLevel())) {
                canceled.add(entry);
            }
        }
        var mutation = new PruneMutation(this, ObjectLists.unmodifiable(canceled), snapshot());
        pendingMutation = mutation;
        return mutation;
    }

    public Entry removeHead(Key key) {
        requireBoundary();
        var head = requireHead(key);
        long followingEpoch = Math.incrementExact(epoch);
        entries.removeFirst();
        progress.clear();
        epoch = followingEpoch;
        return head;
    }

    /**
     * Compensates insertion only; a started head cannot lose committed numeric work.
     */
    public Entry removeUnstarted(Key key) {
        requireBoundary();
        for (int index = 0; index < entries.size(); index++) {
            if (!entries.get(index).key().equals(Objects.requireNonNull(key))) {
                continue;
            }
            if (index == 0 && !progress.isEmpty()) {
                throw new IllegalStateException("Research head has committed work");
            }
            long followingEpoch = Math.incrementExact(epoch);
            var removed = entries.remove(index);
            epoch = followingEpoch;
            return removed;
        }
        throw new IllegalStateException("Missing exact queued entry");
    }

    public Snapshot snapshot() {
        requireBoundary();
        return capture();
    }

    private Snapshot capture() {
        return new Snapshot(teamId, queueSnapshot(), entries.isEmpty() ? null : entries.getFirst().key(),
            resourceProgressSnapshot(), nextEntryId, epoch, updatedAtWorldTime, lastGrantedEntryId, lastGrantedWorldTime);
    }

    private void validateSnapshot(Snapshot saved) {
        if (!teamId.equals(saved.teamId())) {
            throw new IllegalArgumentException("Research queue team differs from snapshot");
        }
        var ids = new LongOpenHashSet(saved.entries().size());
        var targets = new ObjectOpenHashSet<Target>(saved.entries().size());
        for (var entry : saved.entries()) {
            var key = entry.key();
            var definition = registry.require(key.researchId());
            definition.stageForLevel(key.targetLevel());
            if (definition.specialOnly() || !ids.add(key.queueEntryId()) || !targets.add(new Target(key.researchId(), key.targetLevel()))
                || key.queueEntryId() >= saved.nextEntryId() || entry.createdAtWorldTime() > saved.updatedAtWorldTime()) {
                throw new IllegalArgumentException("Invalid or duplicate saved research queue entry");
            }
        }
        var expected = saved.entries().isEmpty() ? null : saved.entries().getFirst().key();
        if (!Objects.equals(expected, saved.activeHead())) {
            throw new IllegalArgumentException("Saved research head differs from first entry");
        }
        if (!saved.resourceProgress().isEmpty() && expected == null) {
            throw new IllegalArgumentException("Empty research queue has progress");
        }
        var demand = expected == null ? null : registry.require(expected.researchId()).totalResourceDemandForLevel(expected.targetLevel());
        for (var entry : saved.resourceProgress().object2LongEntrySet()) {
            var type = resources.canonical(entry.getKey());
            if (type.logicalConcurrency() || demand == null || !demand.containsKey(type) || entry.getLongValue() > demand.getLong(type)) {
                throw new IllegalArgumentException("Saved research progress is outside numeric head demand");
            }
        }
        if (saved.lastGrantedEntryId() >= saved.nextEntryId() || saved.lastGrantedWorldTime() > saved.updatedAtWorldTime()) {
            throw new IllegalArgumentException("Saved research grant marker is outside queue history");
        }
    }

    private void validatePlan(SegmentPlan plan) {
        Objects.requireNonNull(plan);
        requireHead(plan.head.key());
        if (plan.owner != this || plan.queueEpoch != epoch || !progress.equals(plan.progressBefore)) {
            throw new IllegalStateException("Research segment plan belongs to a stale or different queue");
        }
    }

    private Entry requireHead(Key key) {
        if (entries.isEmpty() || !entries.getFirst().key().equals(Objects.requireNonNull(key))) {
            throw new IllegalStateException("Research operation requires the exact current head");
        }
        return entries.getFirst();
    }

    private void requireView(TeamResearchView view) {
        if (Objects.requireNonNull(view).registry() != registry || !teamId.equals(view.teamId())) {
            throw new IllegalArgumentException("Research view belongs to a different queue registry or team");
        }
    }

    private void requireBoundary() {
        requireThread();
        if (pendingMutation != null) {
            throw new IllegalStateException("Research queue has a pending mutation");
        }
    }

    private void requireMutationEpochCapacity() {
        if (epoch > Long.MAX_VALUE - 2) {
            throw new IllegalStateException("Research queue lacks epoch capacity for commit and rollback");
        }
    }

    private void requireOwner(Object mutation) {
        requireThread();
        if (pendingMutation != mutation) {
            throw new IllegalStateException("Research queue mutation lost ownership");
        }
    }

    private void requireThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Research queue access requires its owner thread");
        }
    }

    private void replace(Snapshot snapshot) {
        entries.clear();
        entries.addAll(snapshot.entries());
        progress.clear();
        progress.putAll(snapshot.resourceProgress());
        nextEntryId = snapshot.nextEntryId();
        epoch = snapshot.epoch();
        updatedAtWorldTime = snapshot.updatedAtWorldTime();
        lastGrantedEntryId = snapshot.lastGrantedEntryId();
        lastGrantedWorldTime = snapshot.lastGrantedWorldTime();
    }

    private void undo(Snapshot snapshot) {
        long followingEpoch = Math.incrementExact(epoch);
        long retainedNextId = Math.max(nextEntryId, snapshot.nextEntryId());
        replace(snapshot);
        epoch = followingEpoch;
        nextEntryId = retainedNextId;
    }

    private enum MutationState {PREPARED, COMMITTED, COMPLETED, ROLLED_BACK}

    public record Key(ResourceLocation researchId, int targetLevel, long queueEntryId) {
        public Key {
            Objects.requireNonNull(researchId);
            if (targetLevel < 1 || queueEntryId < 1) {
                throw new IllegalArgumentException("Invalid queued research key");
            }
        }
    }

    public record Entry(Key key, UUID requestedBy, long createdAtWorldTime) {
        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(requestedBy);
            if (createdAtWorldTime < 0) {
                throw new IllegalArgumentException("Negative research request time");
            }
        }
    }

    private record Target(ResourceLocation researchId, int level) {
    }

    public record Snapshot(String teamId, ObjectList<Entry> entries, @Nullable Key activeHead,
                           Object2LongMap<NetworkResourceType> resourceProgress, long nextEntryId, long epoch,
                           long updatedAtWorldTime, long lastGrantedEntryId, long lastGrantedWorldTime) {
        public Snapshot {
            Objects.requireNonNull(teamId);
            entries = ObjectLists.unmodifiable(new ObjectArrayList<>(Objects.requireNonNull(entries)));
            for (var entry : entries) {
                Objects.requireNonNull(entry);
            }
            resourceProgress = NetworkResourceModifier.copyResources(resourceProgress);
            if (entries.size() > CAPACITY || nextEntryId < 1 || epoch < 0 || updatedAtWorldTime < 0
                || (lastGrantedEntryId != -1 && lastGrantedEntryId < 1) || lastGrantedWorldTime < -1
                || (lastGrantedEntryId == -1) != (lastGrantedWorldTime == -1)) {
                throw new IllegalArgumentException("Invalid research queue snapshot counters");
            }
        }
    }

    /**
     * Constructor is private; restoring queue state never restores plan authority.
     */
    public static final class SegmentPlan {
        private final TeamResearchQueue owner;
        private final Entry head;
        private final ObjectSet<UUID> stationIds;
        private final long queueEpoch;
        private final long worldTime;
        private final Object2LongMap<NetworkResourceType> progressBefore;
        private final Object2LongMap<NetworkResourceType> progressCeiling;
        private final Object2LongMap<NetworkResourceType> grantedResources;
        private final ObjectList<RecipeRequirement<?>> materialRequirements;
        private final boolean completesResearch;

        private SegmentPlan(TeamResearchQueue owner, Entry head, ObjectSet<UUID> stationIds, long queueEpoch, long worldTime,
                            Object2LongMap<NetworkResourceType> progressBefore, Object2LongMap<NetworkResourceType> progressCeiling,
                            Object2LongMap<NetworkResourceType> grantedResources, ObjectList<RecipeRequirement<?>> materialRequirements,
                            boolean completesResearch) {
            this.owner = owner;
            this.head = head;
            this.stationIds = stationIds;
            this.queueEpoch = queueEpoch;
            this.worldTime = worldTime;
            this.progressBefore = progressBefore;
            this.progressCeiling = progressCeiling;
            this.grantedResources = grantedResources;
            this.materialRequirements = materialRequirements;
            this.completesResearch = completesResearch;
        }

        public Key key() {
            return head.key();
        }

        public ObjectSet<UUID> stationIds() {
            return stationIds;
        }

        public long queueEpoch() {
            return queueEpoch;
        }

        public long worldTime() {
            return worldTime;
        }

        public Object2LongMap<NetworkResourceType> progressBefore() {
            return progressBefore;
        }

        public Object2LongMap<NetworkResourceType> progressCeiling() {
            return progressCeiling;
        }

        public Object2LongMap<NetworkResourceType> grantedResources() {
            return grantedResources;
        }

        public ObjectList<RecipeRequirement<?>> materialRequirements() {
            return materialRequirements;
        }

        public boolean completesResearch() {
            return completesResearch;
        }
    }

    /**
     * Grant amounts are borrowed immutable data from the completed segment plan.
     */
    public record GrantResult(Entry entry, ObjectSet<UUID> stationIds,
                              Object2LongMap<NetworkResourceType> grantedResources,
                              boolean completedResearch) {
        public GrantResult {
            Objects.requireNonNull(entry);
            stationIds = stations(stationIds);
            NetworkResourceModifier.validateResources(grantedResources);
        }
    }

    public static final class GrantMutation implements ReversibleTransaction {
        private final TeamResearchQueue owner;
        private final SegmentPlan plan;
        private final Snapshot before;
        private MutationState state = MutationState.PREPARED;
        @Nullable
        private GrantResult result;

        private GrantMutation(TeamResearchQueue owner, SegmentPlan plan, Snapshot before) {
            this.owner = owner;
            this.plan = plan;
            this.before = before;
        }

        @Override
        public Resolution resolution() {
            owner.requireThread();
            return switch (state) {
                case PREPARED, COMMITTED -> Resolution.REVERSIBLE;
                case COMPLETED -> Resolution.COMPLETED;
                case ROLLED_BACK -> Resolution.ROLLED_BACK;
            };
        }

        public GrantResult result() {
            owner.requireThread();
            if (result == null) {
                throw new IllegalStateException("Research grant has no committed result");
            }
            return result;
        }

        @Override
        public void commit() {
            owner.requireThread();
            if (state != MutationState.PREPARED) {
                throw new IllegalStateException("Research grant commit cannot be replayed");
            }
            owner.requireOwner(this);
            owner.validatePlan(plan);
            long followingEpoch = Math.incrementExact(owner.epoch);
            var committedResult = new GrantResult(plan.head, plan.stationIds, plan.grantedResources, plan.completesResearch);
            owner.progress.clear();
            owner.progress.putAll(plan.progressCeiling);
            owner.updatedAtWorldTime = Math.max(owner.updatedAtWorldTime, plan.worldTime);
            owner.lastGrantedEntryId = plan.head.key().queueEntryId();
            owner.lastGrantedWorldTime = plan.worldTime;
            if (plan.completesResearch) {
                owner.entries.removeFirst();
                owner.progress.clear();
            }
            owner.epoch = followingEpoch;
            state = MutationState.COMMITTED;
            result = committedResult;
        }

        @Override
        public void complete() {
            owner.requireThread();
            if (state == MutationState.COMPLETED) {
                return;
            }
            if (state != MutationState.COMMITTED) {
                throw new IllegalStateException("Research grant is not committed");
            }
            owner.requireOwner(this);
            owner.pendingMutation = null;
            state = MutationState.COMPLETED;
        }

        @Override
        public void rollback() {
            owner.requireThread();
            if (state == MutationState.ROLLED_BACK) {
                return;
            }
            if (state == MutationState.COMPLETED) {
                throw new IllegalStateException("Completed research grant cannot roll back");
            }
            owner.requireOwner(this);
            owner.undo(before);
            owner.pendingMutation = null;
            result = null;
            state = MutationState.ROLLED_BACK;
        }
    }

    public static final class PruneMutation implements ReversibleTransaction {
        private final TeamResearchQueue owner;
        private final ObjectList<Entry> canceled;
        private final Snapshot before;
        private MutationState state = MutationState.PREPARED;

        private PruneMutation(TeamResearchQueue owner, ObjectList<Entry> canceled, Snapshot before) {
            this.owner = owner;
            this.canceled = canceled;
            this.before = before;
        }

        @Override
        public Resolution resolution() {
            owner.requireThread();
            return switch (state) {
                case PREPARED, COMMITTED -> Resolution.REVERSIBLE;
                case COMPLETED -> Resolution.COMPLETED;
                case ROLLED_BACK -> Resolution.ROLLED_BACK;
            };
        }

        public ObjectList<Entry> removed() {
            owner.requireThread();
            return canceled;
        }

        @Override
        public void commit() {
            owner.requireThread();
            if (state != MutationState.PREPARED) {
                throw new IllegalStateException("Research prune commit cannot be replayed");
            }
            owner.requireOwner(this);
            long followingEpoch = Math.incrementExact(owner.epoch);
            if (!owner.entries.isEmpty() && canceled.contains(owner.entries.getFirst())) {
                owner.progress.clear();
            }
            owner.entries.removeAll(canceled);
            owner.epoch = followingEpoch;
            state = MutationState.COMMITTED;
        }

        @Override
        public void complete() {
            owner.requireThread();
            if (state == MutationState.COMPLETED) {
                return;
            }
            if (state != MutationState.COMMITTED) {
                throw new IllegalStateException("Research prune is not committed");
            }
            owner.requireOwner(this);
            owner.pendingMutation = null;
            state = MutationState.COMPLETED;
        }

        @Override
        public void rollback() {
            owner.requireThread();
            if (state == MutationState.ROLLED_BACK) {
                return;
            }
            if (state == MutationState.COMPLETED) {
                throw new IllegalStateException("Completed research prune cannot roll back");
            }
            owner.requireOwner(this);
            owner.undo(before);
            owner.pendingMutation = null;
            state = MutationState.ROLLED_BACK;
        }
    }
}
