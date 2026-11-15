package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Player-owned ordinary levels and permanent reward evidence, with exclusive reversible writes.
 */
@SuppressWarnings("unused") // Public state/transaction entry points are used by library consumers.
public final class PlayerResearchState {
    private final UUID playerUuid;
    private final Object2ObjectOpenHashMap<ResourceLocation, UUID> sourceReservations = new Object2ObjectOpenHashMap<>();
    private Snapshot state;
    private Object writer;

    public PlayerResearchState(UUID playerUuid) {
        this.playerUuid = playerUuid;
        state = new Snapshot(playerUuid, Object2IntMaps.emptyMap(), ObjectLists.emptyList());
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public synchronized boolean busy() {
        return writer != null;
    }

    public synchronized boolean canWriteResearch(ResourceLocation id) {
        return writer == null && !sourceReservations.containsKey(id);
    }

    public synchronized boolean sourceReserved(ResourceLocation id) {
        return sourceReservations.containsKey(id);
    }

    public synchronized boolean sourceReservedBy(ResourceLocation id, UUID operationId) {
        return operationId.equals(sourceReservations.get(id));
    }

    /**
     * Runtime-only research reservation. It never borrows the whole player's writer across ticks.
     */
    public synchronized boolean tryReserveSource(ResourceLocation id, UUID operationId) {
        var existing = sourceReservations.get(id);
        if (existing != null) return existing.equals(operationId);
        if (writer != null) return false;
        sourceReservations.put(id, operationId);
        return true;
    }

    public synchronized boolean canReleaseSource(ResourceLocation id, UUID operationId) {
        return !(writer instanceof Completion completion && id.equals(completion.researchId)
            && operationId.equals(completion.operationId));
    }

    public synchronized boolean releaseSource(ResourceLocation id, UUID operationId) {
        if (!canReleaseSource(id, operationId)) return false;
        sourceReservations.remove(id, operationId);
        return true;
    }

    public synchronized int completedLevel(ResourceLocation id) {
        return state.completedLevels().getInt(id);
    }

    public synchronized Object2IntMap<ResourceLocation> completedLevelsSnapshot() {
        return state.completedLevels();
    }

    public synchronized ObjectList<RewardClaim> rewardClaimsSnapshot() {
        return state.rewardClaims();
    }

    public synchronized Snapshot snapshot() {
        return state;
    }

    public synchronized void restore(Snapshot saved) {
        requireAvailable();
        if (!sourceReservations.isEmpty())
            throw new IllegalStateException("Cannot replace reserved player research sources");
        if (!playerUuid.equals(saved.playerUuid()))
            throw new IllegalArgumentException("Player snapshot identity changed");
        state = saved;
    }

    /**
     * Completes levels without reward definitions; completion services use prepareCompletion for eligibility.
     */
    @SuppressWarnings("UnusedReturnValue") // Reports whether a caller raised the personal level.
    public boolean completeAtLeast(ResourceLocation id, int level) {
        var mutation = tryPrepareCompletion(id, true, level, IntSets.emptySet(), 0);
        if (mutation == null) return false;
        mutation.commit();
        mutation.complete();
        return mutation.levelRaised();
    }

    public synchronized Completion prepareCompletion(ResourceLocation id, boolean raiseCompletedLevel, int targetLevel,
                                                     IntCollection rewardEligibilityLevels, long eligibleAtWorldTime) {
        requireAvailable();
        requireUnreserved(id);
        if (targetLevel < 1 || eligibleAtWorldTime < 0) throw new IllegalArgumentException("Invalid completion");
        Object2IntMap<ResourceLocation> levels = state.completedLevels();
        boolean raised = raiseCompletedLevel && levels.getInt(id) < targetLevel;
        if (raised) {
            levels = new Object2IntOpenHashMap<>(levels);
            levels.put(id, targetLevel);
        }
        if (rewardEligibilityLevels.isEmpty())
            return acquire(id, null, raised ? new Snapshot(playerUuid, levels, state.rewardClaims()) : state, raised, false);
        var eligible = new IntOpenHashSet();
        var elements = state.rewardClaims();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var claim = elements.get(index1);
            if (claim.key().researchId().equals(id)) eligible.add(claim.key().level());
        }
        var unique = new IntOpenHashSet(rewardEligibilityLevels.size());
        ObjectArrayList<RewardClaim> claims = null;
        for (int level : rewardEligibilityLevels) {
            if (level < 1 || level > targetLevel || !unique.add(level))
                throw new IllegalArgumentException("Invalid reward eligibility level");
            if (!eligible.contains(level)) {
                if (claims == null) claims = new ObjectArrayList<>(state.rewardClaims());
                claims.add(RewardClaim.unclaimed(new RewardClaim.Key(playerUuid, id, level), eligibleAtWorldTime));
            }
        }
        boolean eligibility = claims != null;
        return acquire(id, null, raised || eligibility
            ? new Snapshot(playerUuid, levels, eligibility ? claims : state.rewardClaims()) : state, raised, eligibility);
    }

    /**
     * Null is normal research contention; callers retain their work and retry.
     */
    @Nullable
    public synchronized Completion tryPrepareCompletion(ResourceLocation id, boolean raiseCompletedLevel,
                                                        int targetLevel, IntCollection rewardEligibilityLevels, long eligibleAtWorldTime) {
        if (!canWriteResearch(id)) return null;
        return prepareCompletion(id, raiseCompletedLevel, targetLevel, rewardEligibilityLevels, eligibleAtWorldTime);
    }

    /**
     * Download/forget source writes preserve every claim, including a partly delivered selection.
     */
    public synchronized Completion prepareSourceMutation(ResourceLocation id, int expectedBeforeLevel, int afterLevel) {
        requireAvailable();
        requireUnreserved(id);
        return prepareSourceMutation(id, expectedBeforeLevel, afterLevel, null);
    }

    /**
     * A download UUID permits only its own already-reserved research; null is ordinary contention.
     */
    @Nullable
    public synchronized Completion tryPrepareSourceMutation(ResourceLocation id, int expectedBeforeLevel,
                                                            int afterLevel, @Nullable UUID operationId) {
        if (writer != null || (operationId == null ? sourceReserved(id) : !sourceReservedBy(id, operationId)))
            return null;
        return prepareSourceMutation(id, expectedBeforeLevel, afterLevel, operationId);
    }

    private Completion prepareSourceMutation(ResourceLocation id, int expectedBeforeLevel, int afterLevel, @Nullable UUID operationId) {
        if (afterLevel < 0 || afterLevel > expectedBeforeLevel
            || state.completedLevels().getInt(id) != expectedBeforeLevel)
            throw new IllegalArgumentException("Invalid source level transition");
        if (afterLevel == expectedBeforeLevel) return acquire(id, operationId, state, false, false);
        var levels = new Object2IntOpenHashMap<>(state.completedLevels());
        if (afterLevel == 0) levels.removeInt(id);
        else levels.put(id, afterLevel);
        return acquire(id, operationId, new Snapshot(playerUuid, levels, state.rewardClaims()), false, false);
    }

    public synchronized Completion prepareForgetResearch(ResourceLocation id, int forgottenLevel) {
        if (forgottenLevel < 1) throw new IllegalArgumentException("Forgotten level must be positive");
        int before = completedLevel(id);
        return prepareSourceMutation(id, before, Math.min(before, forgottenLevel - 1));
    }

    @Nullable
    public synchronized Completion tryPrepareForgetResearch(ResourceLocation id, int forgottenLevel) {
        if (!canWriteResearch(id)) return null;
        return prepareForgetResearch(id, forgottenLevel);
    }

    private Completion acquire(ResourceLocation id, @Nullable UUID operationId, Snapshot target, boolean raised, boolean eligibility) {
        var mutation = new Completion(this, id, operationId, state, target, raised, eligibility);
        writer = mutation;
        return mutation;
    }

    /**
     * One reward executor owns all player changes until it leaves the checkpoint/delivery call stack.
     */
    public synchronized RewardSession tryRewardSession(RewardClaim.Key key) {
        if (!playerUuid.equals(key.playerUuid())) throw new IllegalArgumentException("Reward actor changed");
        if (writer != null) return null;
        var session = new RewardSession(this, key);
        writer = session;
        return session;
    }

    private void requireAvailable() {
        if (writer != null) throw new IllegalStateException("Player research state has an active writer");
    }

    private void requireUnreserved(ResourceLocation id) {
        if (sourceReservations.containsKey(id))
            throw new IllegalStateException("Player research source is reserved by a download");
    }

    public static final class Completion implements ReversibleTransaction {
        private final PlayerResearchState owner;
        private final ResourceLocation researchId;
        private final @Nullable UUID operationId;
        private final Snapshot before;
        private final Snapshot after;
        private final boolean levelRaised;
        private final boolean rewardClaimEligibilityCreated;
        private boolean committed;
        private Resolution terminal;

        private Completion(PlayerResearchState owner, ResourceLocation researchId, @Nullable UUID operationId,
                           Snapshot before, Snapshot after, boolean raised, boolean eligibility) {
            this.owner = owner;
            this.researchId = researchId;
            this.operationId = operationId;
            this.before = before;
            this.after = after;
            levelRaised = raised;
            rewardClaimEligibilityCreated = eligibility;
        }

        public boolean levelRaised() {
            return levelRaised;
        }

        public boolean rewardClaimEligibilityCreated() {
            return rewardClaimEligibilityCreated;
        }

        public boolean committed() {
            synchronized (owner) {
                return committed;
            }
        }

        /**
         * Exact live writer proof for an enclosing world's own held-source checkpoint.
         */
        public boolean owns(PlayerResearchState source) {
            synchronized (owner) {
                return source == owner && owner.writer == this && terminal == null
                    && owner.state == (committed ? after : before);
            }
        }

        /**
         * Borrowed immutable snapshot; only this still-exclusive transaction can supply it.
         */
        public Snapshot snapshot() {
            synchronized (owner) {
                if (!owns(owner)) throw new IllegalStateException("Completion no longer owns its source snapshot");
                return owner.state;
            }
        }

        @Override
        public void commit() {
            synchronized (owner) {
                if (owner.writer != this || terminal != null || committed || owner.state != before)
                    throw new IllegalStateException("Completion no longer owns its baseline");
                owner.state = after;
                committed = true;
            }
        }

        @Override
        public void complete() {
            synchronized (owner) {
                if (terminal == Resolution.COMPLETED) return;
                if (terminal == Resolution.ROLLED_BACK)
                    throw new IllegalStateException("Rolled back completion cannot complete");
                if (owner.writer != this || !committed || owner.state != after)
                    throw new IllegalStateException("Completion no longer owns committed state");
                terminal = Resolution.COMPLETED;
                owner.writer = null;
            }
        }

        @Override
        public void rollback() {
            synchronized (owner) {
                if (terminal == Resolution.ROLLED_BACK) return;
                if (terminal == Resolution.COMPLETED)
                    throw new IllegalStateException("Completed mutation cannot roll back");
                if (owner.writer != this || owner.state != (committed ? after : before))
                    throw new IllegalStateException("Completion no longer owns rollback state");
                owner.state = before;
                committed = false;
                terminal = Resolution.ROLLED_BACK;
                owner.writer = null;
            }
        }

        @Override
        public Resolution resolution() {
            synchronized (owner) {
                return terminal == null ? Resolution.REVERSIBLE : terminal;
            }
        }
    }

    public static final class RewardSession implements AutoCloseable {
        private final PlayerResearchState owner;
        private final RewardClaim.Key key;
        private final int index;

        private RewardSession(PlayerResearchState owner, RewardClaim.Key key) {
            this.owner = owner;
            this.key = key;
            var claims = owner.state.rewardClaims();
            int found = -1;
            for (int candidate = 0; candidate < claims.size(); candidate++) {
                if (claims.get(candidate).key().equals(key)) {
                    found = candidate;
                    break;
                }
            }
            index = found;
        }

        /**
         * Exact borrowed-session proof; another source or claim never inherits this writer.
         */
        public boolean owns(PlayerResearchState source, RewardClaim.Key expected) {
            synchronized (owner) {
                return owner == source && owner.writer == this && key.equals(expected);
            }
        }

        /**
         * The world checkpoint may snapshot its own reward writer while unrelated writes stay excluded.
         */
        public Snapshot snapshot() {
            synchronized (owner) {
                requireOwner();
                return owner.state;
            }
        }

        public RewardClaim current() {
            synchronized (owner) {
                requireOwner();
                return index < 0 ? null : owner.state.rewardClaims().get(index);
            }
        }

        public RewardClaim requireCurrent() {
            return Objects.requireNonNull(current(), "Reward eligibility disappeared");
        }

        public void replace(RewardClaim replacement) {
            synchronized (owner) {
                requireOwner();
                if (!replacement.key().equals(key)) throw new IllegalArgumentException("Reward claim identity changed");
                if (index < 0) throw new IllegalStateException("Reward eligibility disappeared");
                var current = owner.state.rewardClaims().get(index);
                if (current == replacement) return;
                current.requireTransitionTo(replacement);
                var claims = new ObjectArrayList<>(owner.state.rewardClaims());
                claims.set(index, replacement);
                owner.state = new Snapshot(owner.playerUuid, owner.state.completedLevels(), claims);
            }
        }

        private void requireOwner() {
            if (owner.writer != this) throw new IllegalStateException("Reward session is closed");
        }

        @Override
        public void close() {
            synchronized (owner) {
                if (owner.writer == this) owner.writer = null;
            }
        }
    }

    /**
     * Completed zero-default levels and claims are borrowed read-only for this snapshot's lifetime.
     */
    public record Snapshot(UUID playerUuid, Object2IntMap<ResourceLocation> completedLevels,
                           ObjectList<RewardClaim> rewardClaims) {
    }
}
