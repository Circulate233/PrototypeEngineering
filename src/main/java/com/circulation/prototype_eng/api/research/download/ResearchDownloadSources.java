package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Frozen item-download source changes. Membership, material receipts and durable phases belong to the world owner.
 */
@SuppressWarnings({"unused", "SynchronizationOnLocalVariableOrMethodParameter"})
// Source checks and acquisition use the concrete source's monitor atomically.
public final class ResearchDownloadSources {
    private final Thread owner = Thread.currentThread();
    private final ResearchRegistry registry;
    private final NbtCodec<Plan> codec = new PlanCodec();

    public ResearchDownloadSources(ResearchRegistry registry) {
        this.registry = registry;
        if (!registry.isFrozen())
            throw new IllegalStateException("Download sources require frozen research definitions");
    }

    private static void releaseNewReservations(ResourceLocation id, UUID operationId,
                                               ObjectList<PlayerResearchState> acquired, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (int index = acquired.size() - 1; index >= 0; index--) {
            try {
                if (!acquired.get(index).releaseSource(id, operationId))
                    throw new IllegalStateException("New reservation acquired a live writer before admission finished");
            } catch (RuntimeException | Error cleanup) {
                failure = append(failure, cleanup);
            }
        }
        if (primary == null) rethrow(failure);
    }

    private static void requireSourceLevel(ResearchDefinition definition, int level) {
        if (level < 0 || level > definition.maxLevel())
            throw new IllegalArgumentException("Source level exceeds its definition");
    }

    @Nullable
    private static PlayerResearchState player(Map<UUID, PlayerResearchState> players, UUID id) {
        var source = players.get(id);
        if (source != null && !id.equals(source.playerUuid()))
            throw new IllegalArgumentException("Download player table has mismatched identity");
        return source;
    }

    private static Evaluation evaluation(Status status) {
        return new Evaluation(status, null, 0, 0);
    }

    private static Preparation rejectPrepared(Status status, ObjectList<HeldPlayer> held) {
        rollbackHeld(held, null, null);
        return new Preparation(status, null);
    }

    private static void rollbackHeld(ObjectList<HeldPlayer> held, @Nullable TeamOwnedResearch.Completion team, @Nullable Throwable primary) {
        Throwable failure = primary;
        if (team != null) {
            try {
                team.rollback();
            } catch (RuntimeException | Error cleanup) {
                failure = append(failure, cleanup);
            }
        }
        for (int index = held.size() - 1; index >= 0; index--) {
            try {
                held.get(index).mutation().rollback();
            } catch (RuntimeException | Error cleanup) {
                failure = append(failure, cleanup);
            }
        }
        if (primary == null) rethrow(failure);
    }

    @Nullable
    private static Throwable append(@Nullable Throwable primary, Throwable next) {
        if (primary == null) return next;
        if (primary != next) primary.addSuppressed(next);
        return primary;
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    /**
     * Checks raw personal levels, never the team's cloud maximum. No source writer is acquired here.
     */
    public Evaluation evaluate(ResearchDefinition definition, int downloadLevel, int cost, OwnerTeamResolution membership,
                               @Nullable DataCenterResearchSnapshot database, Map<UUID, PlayerResearchState> players, @Nullable TeamOwnedResearch team) {
        requireOwner();
        if (registry.require(definition.id()) != definition)
            throw new IllegalArgumentException("Download definition is not canonical");
        definition.stageForLevel(downloadLevel);
        if (definition.specialOnly()) return evaluation(Status.SPECIAL_ONLY_REJECTED);
        if (!membership.accepted()) return evaluation(Status.MISSING_TEAM);
        if (team != null) requireTeam(membership.teamId(), team);
        var members = new ObjectArrayList<>(membership.members());
        members.sort(null);
        if (definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
            if (database == null || database.storedResearch().getInt(definition.id()) < downloadLevel) {
                return evaluation(Status.MISSING_DATABASE_RESEARCH_LEVEL);
            }
            if (definition.researchTierForLevel(downloadLevel) > database.level())
                return evaluation(Status.DATA_CENTER_LEVEL_TOO_LOW);
            var changes = new ObjectArrayList<MemberChange>(members.size());
            int teamLevel = team == null ? 0 : team.completedLevel(definition.id());
            int beforeVisible = 0;
            int afterVisible = 0;
            for (int index2 = 0; index2 < members.size(); index2++) {
                var member = members.get(index2);
                var source = player(players, member);
                int before;
                if (source == null) before = 0;
                else synchronized (source) {
                    if (!source.canWriteResearch(definition.id())) return evaluation(Status.SOURCE_BUSY);
                    before = source.completedLevel(definition.id());
                }
                requireSourceLevel(definition, before);
                if (before < cost) return evaluation(Status.PERSONAL_LEVEL_INSUFFICIENT);
                changes.add(new MemberChange(member, before, before - cost));
                beforeVisible = Math.max(beforeVisible, before);
                afterVisible = Math.max(afterVisible, before - cost);
            }
            return new Evaluation(Status.READY, new Plan(definition.deliveryPolicy(), definition.id(), downloadLevel,
                cost, membership.teamId(), members, changes, teamLevel, teamLevel),
                teamLevel > 0 ? teamLevel : beforeVisible, teamLevel > 0 ? teamLevel : afterVisible);
        }
        if (team == null) return evaluation(Status.TEAM_LEVEL_INSUFFICIENT);
        synchronized (team) {
            if (!team.canWriteResearch(definition.id())) return evaluation(Status.SOURCE_BUSY);
            int before = team.completedLevel(definition.id());
            if (before < downloadLevel || before < cost) return evaluation(Status.TEAM_LEVEL_INSUFFICIENT);
            int personalMaximum = 0;
            for (int index = 0; index < members.size(); index++) {
                var source = player(players, members.get(index));
                if (source != null) synchronized (source) {
                    personalMaximum = Math.max(personalMaximum, source.completedLevel(definition.id()));
                }
            }
            return new Evaluation(Status.READY, new Plan(definition.deliveryPolicy(), definition.id(), downloadLevel,
                cost, membership.teamId(), members, ObjectList.of(), before, before - cost),
                before, before - cost > 0 ? before - cost : personalMaximum);
        }
    }

    /**
     * Reserves the original before-values across ticks; a saved pending operation recreates these runtime entries.
     */
    public Status reserve(Plan plan, UUID operationId, Map<UUID, PlayerResearchState> players, TeamOwnedResearch team) {
        requireOwner();
        validate(plan);
        requireTeam(plan.teamId(), team);
        var acquired = new ObjectArrayList<PlayerResearchState>(plan.memberChanges().size());
        try {
            if (plan.policy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
                var elements13 = plan.memberChanges();
                for (int index13 = 0; index13 < elements13.size(); index13++) {
                    var change = elements13.get(index13);
                    var source = player(players, change.memberId());
                    Status rejected = null;
                    if (source == null) rejected = Status.SOURCE_CHANGED;
                    else synchronized (source) {
                        if (source.busy()) rejected = Status.SOURCE_BUSY;
                        else if (source.completedLevel(plan.researchId()) != change.beforeLevel())
                            rejected = Status.SOURCE_CHANGED;
                        else {
                            boolean alreadyOwned = source.sourceReservedBy(plan.researchId(), operationId);
                            if (!source.tryReserveSource(plan.researchId(), operationId)) rejected = Status.SOURCE_BUSY;
                            else if (!alreadyOwned) acquired.add(source);
                        }
                    }
                    if (rejected != null) {
                        releaseNewReservations(plan.researchId(), operationId, acquired, null);
                        return rejected;
                    }
                }
            } else synchronized (team) {
                if (team.busy()) return Status.SOURCE_BUSY;
                if (team.completedLevel(plan.researchId()) != plan.teamBeforeLevel()) return Status.SOURCE_CHANGED;
                if (!team.tryReserveSource(plan.researchId(), operationId)) return Status.SOURCE_BUSY;
            }
            return Status.READY;
        } catch (RuntimeException | Error failure) {
            releaseNewReservations(plan.researchId(), operationId, acquired, failure);
            throw failure;
        }
    }

    /**
     * Releases only this UUID's entries after its short Completion writer has settled. Repeated release is harmless.
     */
    public Status release(Plan plan, UUID operationId, Map<UUID, PlayerResearchState> players, TeamOwnedResearch team) {
        requireOwner();
        validate(plan);
        requireTeam(plan.teamId(), team);
        if (plan.policy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY) {
            return team.releaseSource(plan.researchId(), operationId) ? Status.READY : Status.SOURCE_BUSY;
        }
        var originalSources = new ObjectArrayList<PlayerResearchState>(plan.memberChanges().size());
        var elements10 = plan.memberChanges();
        for (int index10 = 0; index10 < elements10.size(); index10++) {
            var change = elements10.get(index10);
            var source = player(players, change.memberId());
            if (source == null) return Status.SOURCE_CHANGED;
            if (!source.canReleaseSource(plan.researchId(), operationId)) return Status.SOURCE_BUSY;
            originalSources.add(source);
        }
        for (int index1 = 0; index1 < originalSources.size(); index1++) {
            var source = originalSources.get(index1);
            if (!source.releaseSource(plan.researchId(), operationId)) return Status.SOURCE_BUSY;
        }
        return Status.READY;
    }

    /**
     * Reacquires only frozen original sources. The caller's material/journal phase decides when to invoke this.
     */
    public Preparation prepare(Plan plan, Map<UUID, PlayerResearchState> players, TeamOwnedResearch team) {
        return prepare(plan, null, players, team);
    }

    /**
     * The UUID can borrow only its own research reservation, never another operation's live writer.
     */
    public Preparation prepare(Plan plan, @Nullable UUID operationId, Map<UUID, PlayerResearchState> players, TeamOwnedResearch team) {
        requireOwner();
        validate(plan);
        requireTeam(plan.teamId(), team);
        var held = new ObjectArrayList<HeldPlayer>();
        TeamOwnedResearch.Completion teamMutation = null;
        try {
            if (plan.policy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
                var elements14 = plan.memberChanges();
                for (int index14 = 0; index14 < elements14.size(); index14++) {
                    var change = elements14.get(index14);
                    var source = player(players, change.memberId());
                    if (source == null) {
                        if (operationId != null || change.beforeLevel() != 0)
                            return rejectPrepared(Status.SOURCE_CHANGED, held);
                        continue;
                    }
                    Status rejected = null;
                    synchronized (source) {
                        if (source.busy()) rejected = Status.SOURCE_BUSY;
                        else if (operationId == null ? source.sourceReserved(plan.researchId()) : !source.sourceReservedBy(plan.researchId(), operationId)) {
                            rejected = source.sourceReserved(plan.researchId()) ? Status.SOURCE_BUSY : Status.SOURCE_CHANGED;
                        } else if (source.completedLevel(plan.researchId()) != change.beforeLevel())
                            rejected = Status.SOURCE_CHANGED;
                        else {
                            var mutation = source.tryPrepareSourceMutation(plan.researchId(), change.beforeLevel(), change.afterLevel(), operationId);
                            if (mutation == null) rejected = Status.SOURCE_BUSY;
                            else held.add(new HeldPlayer(source, mutation));
                        }
                    }
                    if (rejected != null) return rejectPrepared(rejected, held);
                }
            } else synchronized (team) {
                if (team.busy()) return new Preparation(Status.SOURCE_BUSY, null);
                if (operationId == null ? team.sourceReserved(plan.researchId()) : !team.sourceReservedBy(plan.researchId(), operationId)) {
                    return new Preparation(team.sourceReserved(plan.researchId()) ? Status.SOURCE_BUSY : Status.SOURCE_CHANGED, null);
                }
                if (team.completedLevel(plan.researchId()) != plan.teamBeforeLevel())
                    return new Preparation(Status.SOURCE_CHANGED, null);
                teamMutation = team.tryPrepareSourceMutation(plan.researchId(), plan.teamBeforeLevel(), plan.teamAfterLevel(), operationId);
                if (teamMutation == null) return new Preparation(Status.SOURCE_BUSY, null);
            }
            return new Preparation(Status.READY, new Prepared(plan, held, teamMutation == null ? null : team, teamMutation));
        } catch (RuntimeException | Error failure) {
            rollbackHeld(held, teamMutation, failure);
            throw failure;
        }
    }

    /**
     * SOURCE_LEVELS_COMMITTED recovery checks after-values without applying another cost or recapturing membership.
     */
    public Status verifyCommitted(Plan plan, Map<UUID, PlayerResearchState> players, TeamOwnedResearch team) {
        requireOwner();
        validate(plan);
        requireTeam(plan.teamId(), team);
        if (plan.policy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
            var elements12 = plan.memberChanges();
            for (int index12 = 0; index12 < elements12.size(); index12++) {
                var change = elements12.get(index12);
                var source = player(players, change.memberId());
                if (source == null) {
                    if (change.afterLevel() != 0) return Status.SOURCE_CHANGED;
                } else synchronized (source) {
                    if (source.busy()) return Status.SOURCE_BUSY;
                    if (source.completedLevel(plan.researchId()) != change.afterLevel()) return Status.SOURCE_CHANGED;
                }
            }
        } else synchronized (team) {
            if (team.busy()) return Status.SOURCE_BUSY;
            if (team.completedLevel(plan.researchId()) != plan.teamAfterLevel()) return Status.SOURCE_CHANGED;
        }
        return Status.READY;
    }

    public NbtCodec<Plan> codec() {
        return codec;
    }

    private void validate(Plan plan) {
        var definition = registry.require(plan.researchId());
        definition.stageForLevel(plan.downloadLevel());
        if (definition.specialOnly() || definition.deliveryPolicy() != plan.policy() || plan.cost() > definition.maxLevel()) {
            throw new IllegalArgumentException("Frozen download source policy is incompatible with its definition");
        }
        var elements7 = plan.memberChanges();
        for (int index7 = 0; index7 < elements7.size(); index7++) {
            var change = elements7.get(index7);
            requireSourceLevel(definition, change.beforeLevel());
        }
        if (plan.policy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            requireSourceLevel(definition, plan.teamBeforeLevel());
    }

    private void requireTeam(String teamId, TeamOwnedResearch team) {
        if (team.registry() != registry || !teamId.equals(team.teamId())) {
            throw new IllegalArgumentException("Download sources belong to another team or registry");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Download sources require their owner thread");
    }

    public enum Status {
        READY, SPECIAL_ONLY_REJECTED, MISSING_TEAM, MISSING_DATABASE_RESEARCH_LEVEL, DATA_CENTER_LEVEL_TOO_LOW,
        TEAM_LEVEL_INSUFFICIENT, PERSONAL_LEVEL_INSUFFICIENT, SOURCE_BUSY, SOURCE_CHANGED
    }

    public record Evaluation(Status status, @Nullable Plan plan, int teamLevelBefore, int teamLevelAfter) {
        public int teamLevelLoss() {
            return Math.max(0, teamLevelBefore - teamLevelAfter);
        }
    }

    public record Preparation(Status status, @Nullable Prepared prepared) {
    }

    public record MemberChange(UUID memberId, int beforeLevel, int afterLevel) {
    }

    /**
     * No live source, membership provider, database permit or transaction is serialized.
     * Members and changes must be sorted by member UUID and remain immutable while retained.
     */
    public record Plan(ResearchDownloadDeliveryPolicy policy, ResourceLocation researchId, int downloadLevel, int cost,
                       String teamId, ObjectList<UUID> members, ObjectList<MemberChange> memberChanges,
                       int teamBeforeLevel, int teamAfterLevel) {
    }

    private record HeldPlayer(PlayerResearchState source, PlayerResearchState.Completion mutation) {
    }

    /**
     * Keeps every original concrete writer until durable source settlement is acknowledged by the world.
     */
    public static final class Prepared implements ReversibleTransaction {
        private final Thread owner = Thread.currentThread();
        private final Plan plan;
        private final ObjectList<HeldPlayer> players;
        private final @Nullable TeamOwnedResearch team;
        private final @Nullable TeamOwnedResearch.Completion teamMutation;
        private boolean commitAttempted;
        private boolean committed;
        private Resolution terminal = Resolution.REVERSIBLE;

        private Prepared(Plan plan, ObjectList<HeldPlayer> players, @Nullable TeamOwnedResearch team,
                         @Nullable TeamOwnedResearch.Completion teamMutation) {
            this.plan = plan;
            // The acquisition builder transfers its private, completed writer list.
            this.players = players;
            this.team = team;
            this.teamMutation = teamMutation;
        }

        public Plan plan() {
            return plan;
        }

        public boolean owns(PlayerResearchState source) {
            requireOwner();
            if (terminal != Resolution.REVERSIBLE) return false;
            for (int index6 = 0; index6 < players.size(); index6++) {
                var held = players.get(index6);
                if (held.source() == source) return held.mutation().owns(source);
            }
            return false;
        }

        public PlayerResearchState.Snapshot snapshot(PlayerResearchState source) {
            requireOwner();
            for (int index9 = 0; index9 < players.size(); index9++) {
                var held = players.get(index9);
                if (held.source() == source && terminal == Resolution.REVERSIBLE && held.mutation().owns(source))
                    return held.mutation().snapshot();
            }
            throw new IllegalStateException("Download does not own this personal source snapshot");
        }

        public boolean owns(TeamOwnedResearch source) {
            requireOwner();
            return terminal == Resolution.REVERSIBLE && team == source && teamMutation != null && teamMutation.owns(source);
        }

        public TeamOwnedResearch.Snapshot snapshot(TeamOwnedResearch source) {
            requireOwner();
            if (terminal != Resolution.REVERSIBLE || team != source || teamMutation == null || !teamMutation.owns(source))
                throw new IllegalStateException("Download does not own this team source snapshot");
            return teamMutation.snapshot();
        }

        @Override
        public void commit() {
            requireOwner();
            if (terminal != Resolution.REVERSIBLE || commitAttempted)
                throw new IllegalStateException("Download source commit cannot replay");
            commitAttempted = true;
            try {
                for (int index3 = 0; index3 < players.size(); index3++) {
                    var held = players.get(index3);
                    held.mutation().commit();
                }
                if (teamMutation != null) teamMutation.commit();
                committed = true;
            } catch (RuntimeException | Error failure) {
                rollbackHeld(players, teamMutation, failure);
                if (allRolledBack()) terminal = Resolution.ROLLED_BACK;
                throw failure;
            }
        }

        @Override
        public void rollback() {
            requireOwner();
            if (terminal == Resolution.ROLLED_BACK) return;
            if (terminal != Resolution.REVERSIBLE)
                throw new IllegalStateException("Completed download source cannot roll back");
            rollbackHeld(players, teamMutation, null);
            terminal = Resolution.ROLLED_BACK;
        }

        @Override
        public void complete() {
            requireOwner();
            if (terminal == Resolution.COMPLETED) return;
            if (terminal != Resolution.REVERSIBLE || !committed)
                throw new IllegalStateException("Download sources have not committed");
            for (int index4 = 0; index4 < players.size(); index4++) {
                var held = players.get(index4);
                held.mutation().complete();
            }
            if (teamMutation != null) teamMutation.complete();
            terminal = Resolution.COMPLETED;
        }

        private boolean allRolledBack() {
            if (teamMutation != null && teamMutation.resolution() != Resolution.ROLLED_BACK) return false;
            for (int index8 = 0; index8 < players.size(); index8++) {
                var held = players.get(index8);
                if (held.mutation().resolution() != Resolution.ROLLED_BACK) return false;
            }
            return true;
        }

        @Override
        public Resolution resolution() {
            requireOwner();
            return terminal;
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Held download sources require their owner thread");
        }
    }

    private final class PlanCodec implements NbtCodec<Plan> {
        @Override
        public NBTTagCompound encode(Plan plan) {
            validate(plan);
            var tag = new NBTTagCompound();
            tag.setString("Policy", plan.policy().name());
            tag.setString("Research", plan.researchId().toString());
            tag.setInteger("DownloadLevel", plan.downloadLevel());
            tag.setInteger("Cost", plan.cost());
            tag.setString("Team", plan.teamId());
            tag.setInteger("TeamBefore", plan.teamBeforeLevel());
            tag.setInteger("TeamAfter", plan.teamAfterLevel());
            var members = new NBTTagList();
            var elements = plan.members();
            for (int index5 = 0; index5 < elements.size(); index5++) {
                var member = elements.get(index5);
                members.appendTag(new NBTTagString(member.toString()));
            }
            tag.setTag("Members", members);
            tag.setTag("Changes", changes(plan.memberChanges()));
            return tag;
        }

        private static NBTTagList changes(List<MemberChange> changes) {
            var list = new NBTTagList();
            for (int index = 0; index < changes.size(); index++) {
                var change = changes.get(index);
                var tag = new NBTTagCompound();
                tag.setString("Member", change.memberId().toString());
                tag.setInteger("Before", change.beforeLevel());
                tag.setInteger("After", change.afterLevel());
                list.appendTag(tag);
            }
            return list;
        }

        @Override
        public Plan decode(NBTTagCompound tag) {
            var memberTags = tag.getTagList("Members", Constants.NBT.TAG_STRING);
            var members = new ObjectArrayList<UUID>(memberTags.tagCount());
            for (int index = 0; index < memberTags.tagCount(); index++)
                members.add(uuid(memberTags.getStringTagAt(index)));
            var changeTags = tag.getTagList("Changes", Constants.NBT.TAG_COMPOUND);
            var changes = new ObjectArrayList<MemberChange>(changeTags.tagCount());
            for (int index = 0; index < changeTags.tagCount(); index++) {
                var saved = changeTags.getCompoundTagAt(index);
                changes.add(new MemberChange(uuid(saved.getString("Member")), saved.getInteger("Before"), saved.getInteger("After")));
            }
            var plan = new Plan(ResearchDownloadDeliveryPolicy.valueOf(tag.getString("Policy")),
                new ResourceLocation(tag.getString("Research")), tag.getInteger("DownloadLevel"), tag.getInteger("Cost"),
                tag.getString("Team"), members, changes, tag.getInteger("TeamBefore"), tag.getInteger("TeamAfter"));
            validate(plan);
            return plan;
        }

        private UUID uuid(String value) {
            return UUID.fromString(value);
        }
    }
}
