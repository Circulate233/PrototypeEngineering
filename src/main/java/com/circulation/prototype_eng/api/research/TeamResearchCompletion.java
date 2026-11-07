package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * PN completion propagation, shared by manual and station execution. Call at the owner's work commit
 * boundary with its captured members; this is not permission to skip research work or material costs.
 * Completion creates unclaimed reward eligibility. Only the reward claimer invokes delivery callbacks.
 */
public final class TeamResearchCompletion {
    private final ResearchRegistry registry;
    private final Thread owner = Thread.currentThread();

    public TeamResearchCompletion(ResearchRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        if (!registry.isFrozen()) { throw new IllegalStateException("Research registry is not ready"); }
    }

    /** Null is a normal busy source; the caller retains its uncommitted work and retries later. */
    @Nullable
    public Prepared prepare(ResourceLocation id, int targetLevel, Collection<UUID> members,
                            Map<UUID, PlayerResearchState> players, TeamOwnedResearch team, long worldTime) {
        return prepare(id, targetLevel, members, players, team, worldTime, null);
    }

    /**
     * Restore an already-decided completion against persisted member baselines. This grants the
     * original levels' eligibility even if another completion has since raised a member's source.
     * It does not repair rewards that were already at the baseline when the work started.
     */
    @Nullable
    public Prepared prepareFrozen(ResourceLocation id, int targetLevel, Collection<UUID> members,
                                  Map<UUID, PlayerResearchState> players, TeamOwnedResearch team, long worldTime,
                                  Object2IntMap<UUID> baselines) {
        return prepare(id, targetLevel, members, players, team, worldTime, Objects.requireNonNull(baselines));
    }

    @Nullable
    private Prepared prepare(ResourceLocation id, int targetLevel, Collection<UUID> members,
                             Map<UUID, PlayerResearchState> players, TeamOwnedResearch team, long worldTime,
                             @Nullable Object2IntMap<UUID> baselines) {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Research completion requires its owner thread"); }
        var definition = registry.require(id);
        if (definition.specialOnly() || targetLevel < 1 || targetLevel > definition.maxLevel() || worldTime < 0) {
            throw new IllegalArgumentException("Invalid ordinary research completion: " + id);
        }
        Objects.requireNonNull(players);
        Objects.requireNonNull(team);
        if (team.registry() != registry) { throw new IllegalArgumentException("Team research belongs to a different registry"); }
        var frozenMembers = new ObjectArrayList<>(members);
        var uniqueMembers = new ObjectOpenHashSet<UUID>();
        if (frozenMembers.isEmpty()) { throw new IllegalArgumentException("Research completion requires team members"); }
        boolean teamOwned = definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY;
        if (!teamOwned && team.completedLevel(id) != 0) { throw new IllegalArgumentException("Personal research also has a team-owned source"); }
        if (baselines != null && baselines.size() != frozenMembers.size()) { throw new IllegalArgumentException("Frozen completion membership differs"); }
        for (var member : frozenMembers) {
            if (member == null || !uniqueMembers.add(member)) { throw new IllegalArgumentException("Invalid or duplicate completion member"); }
            var player = players.get(member);
            if (player == null || !player.playerUuid().equals(member)) { throw new IllegalArgumentException("Missing or mismatched player research state"); }
            int personalLevel = player.completedLevel(id);
            if (teamOwned && personalLevel != 0) { throw new IllegalArgumentException("Team research also has a personal source"); }
            int current = teamOwned ? team.completedLevel(id) : personalLevel;
            if (current < 0 || current > definition.maxLevel()) { throw new IllegalArgumentException("Completed research exceeds definition"); }
            if (baselines != null && (!baselines.containsKey(member) || baselines.getInt(member) < 0
                    || baselines.getInt(member) > current)) { throw new IllegalArgumentException("Frozen completion baseline is incompatible"); }
        }
        if (teamOwned && team.busy()) { return null; }
        for (var member : frozenMembers) { if (players.get(member).busy()) { return null; } }

        var transactions = new ObjectArrayList<ReversibleTransaction>();
        var raisedPlayers = new ObjectArrayList<UUID>();
        var eligiblePlayers = new ObjectArrayList<UUID>();
        int teamBaseline = team.completedLevel(id);
        try {
            var teamMutation = teamOwned ? team.prepareCompletion(id, targetLevel) : null;
            if (teamMutation != null) { transactions.add(teamMutation); }
            for (var member : frozenMembers) {
                var player = players.get(member);
                int baseline = baselines != null ? baselines.getInt(member)
                    : teamOwned ? teamBaseline : player.completedLevel(id);
                var rewardLevels = new IntArrayList();
                for (var stage : definition.stages()) {
                    for (int level : stage.rewardGroupsByLevel().keySet()) {
                        if (level <= targetLevel && (level > baseline || baselines == null && baseline == targetLevel)) {
                            rewardLevels.add(level);
                        }
                    }
                }
                rewardLevels.sort(null);
                var mutation = player.prepareCompletion(id, !teamOwned, targetLevel, rewardLevels, worldTime);
                transactions.add(mutation);
                if (teamMutation == null ? mutation.levelRaised() : teamMutation.levelRaised()) { raisedPlayers.add(member); }
                if (mutation.rewardClaimEligibilityCreated()) { eligiblePlayers.add(member); }
            }
        } catch (RuntimeException | Error failure) {
            for (int index = transactions.size() - 1; index >= 0; index--) {
                try { transactions.get(index).rollback(); }
                catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
            }
            throw failure;
        }
        return new Prepared(transactions, new Result(raisedPlayers, eligiblePlayers));
    }

    public record Result(ObjectList<UUID> newlyCompletedPlayers, ObjectList<UUID> rewardEligiblePlayers) {
        public Result {
            newlyCompletedPlayers = ObjectLists.unmodifiable(new ObjectArrayList<>(newlyCompletedPlayers));
            rewardEligiblePlayers = ObjectLists.unmodifiable(new ObjectArrayList<>(rewardEligiblePlayers));
        }
    }

    /** All children are private, concrete state mutations. No addon callbacks run inside this batch. */
    public static final class Prepared implements ReversibleTransaction {
        private final Thread owner = Thread.currentThread();
        private final ObjectList<ReversibleTransaction> transactions;
        private final Result result;
        private boolean commitAttempted;
        private boolean committed;
        @Nullable private Resolution terminal;

        private Prepared(ObjectList<ReversibleTransaction> transactions, Result result) {
            this.transactions = transactions;
            this.result = result;
        }

        public Result result() { return result; }

        @Override public void commit() {
            requireOwner();
            if (commitAttempted || terminal != null) { throw new IllegalStateException("Completion commit cannot be replayed"); }
            commitAttempted = true;
            for (var transaction : transactions) { transaction.commit(); }
            committed = true;
        }

        @Override public void complete() {
            requireOwner();
            if (terminal == Resolution.COMPLETED) { return; }
            if (terminal != null || !committed) { throw new IllegalStateException("Completion has not committed"); }
            for (var transaction : transactions) { transaction.complete(); }
            terminal = Resolution.COMPLETED;
        }

        @Override public void rollback() {
            requireOwner();
            if (terminal == Resolution.ROLLED_BACK) { return; }
            if (terminal != null) { throw new IllegalStateException("Completed research cannot be rolled back"); }
            for (int index = transactions.size() - 1; index >= 0; index--) { transactions.get(index).rollback(); }
            terminal = Resolution.ROLLED_BACK;
        }

        @Override public Resolution resolution() { requireOwner(); return terminal == null ? Resolution.REVERSIBLE : terminal; }

        private void requireOwner() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Research completion requires its owner thread"); }
        }
    }
}
