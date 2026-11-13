package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
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
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
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
        this.registry = Objects.requireNonNull(registry);
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
        return new Evaluation(status, null);
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
    public Evaluation evaluate(ResearchDefinition definition, int downloadLevel, OwnerTeamResolution membership,
                               @Nullable DataCenterResearchSnapshot database, Map<UUID, PlayerResearchState> players, TeamOwnedResearch team) {
        requireOwner();
        Objects.requireNonNull(definition);
        Objects.requireNonNull(membership);
        Objects.requireNonNull(players);
        Objects.requireNonNull(team);
        if (registry.require(definition.id()) != definition)
            throw new IllegalArgumentException("Download definition is not canonical");
        definition.stageForLevel(downloadLevel);
        if (definition.specialOnly()) return evaluation(Status.SPECIAL_ONLY_REJECTED);
        if (!membership.accepted()) return evaluation(Status.MISSING_TEAM);
        requireTeam(membership.teamId(), team);
        int cost = definition.itemDownloadLevelCost();
        var members = new ObjectArrayList<>(membership.members());
        members.sort(null);
        if (definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
            if (database == null || database.storedResearch().getInt(definition.id()) < downloadLevel) {
                return evaluation(Status.MISSING_DATABASE_RESEARCH_LEVEL);
            }
            if (definition.researchTierForLevel(downloadLevel) > database.level())
                return evaluation(Status.DATA_CENTER_LEVEL_TOO_LOW);
            var changes = new ObjectArrayList<MemberChange>(members.size());
            for (var member : members) {
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
            }
            return new Evaluation(Status.READY, new Plan(definition.deliveryPolicy(), definition.id(), downloadLevel,
                cost, membership.teamId(), members, changes, -1, -1));
        }
        synchronized (team) {
            if (!team.canWriteResearch(definition.id())) return evaluation(Status.SOURCE_BUSY);
            int before = team.completedLevel(definition.id());
            if (before < downloadLevel || before < cost) return evaluation(Status.TEAM_LEVEL_INSUFFICIENT);
            return new Evaluation(Status.READY, new Plan(definition.deliveryPolicy(), definition.id(), downloadLevel,
                cost, membership.teamId(), members, ObjectList.of(), before, before - cost));
        }
    }

    /**
     * Reserves the original before-values across ticks; a saved pending operation recreates these runtime entries.
     */
    public Status reserve(Plan plan, UUID operationId, Map<UUID, PlayerResearchState> players, TeamOwnedResearch team) {
        requireOwner();
        validate(plan);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(players);
        requireTeam(plan.teamId(), team);
        var acquired = new ObjectArrayList<PlayerResearchState>(plan.memberChanges().size());
        try {
            if (plan.policy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
                for (var change : plan.memberChanges()) {
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
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(players);
        requireTeam(plan.teamId(), team);
        if (plan.policy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY) {
            return team.releaseSource(plan.researchId(), operationId) ? Status.READY : Status.SOURCE_BUSY;
        }
        var originalSources = new ObjectArrayList<PlayerResearchState>(plan.memberChanges().size());
        for (var change : plan.memberChanges()) {
            var source = player(players, change.memberId());
            if (source == null) return Status.SOURCE_CHANGED;
            if (!source.canReleaseSource(plan.researchId(), operationId)) return Status.SOURCE_BUSY;
            originalSources.add(source);
        }
        for (var source : originalSources) {
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
        Objects.requireNonNull(players);
        requireTeam(plan.teamId(), team);
        var held = new ObjectArrayList<HeldPlayer>();
        TeamOwnedResearch.Completion teamMutation = null;
        try {
            if (plan.policy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
                for (var change : plan.memberChanges()) {
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
        Objects.requireNonNull(players);
        requireTeam(plan.teamId(), team);
        if (plan.policy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
            for (var change : plan.memberChanges()) {
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
        Objects.requireNonNull(plan);
        var definition = registry.require(plan.researchId());
        definition.stageForLevel(plan.downloadLevel());
        if (definition.specialOnly() || definition.deliveryPolicy() != plan.policy() || plan.cost() > definition.maxLevel()) {
            throw new IllegalArgumentException("Frozen download source policy is incompatible with its definition");
        }
        for (var change : plan.memberChanges()) requireSourceLevel(definition, change.beforeLevel());
        if (plan.policy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            requireSourceLevel(definition, plan.teamBeforeLevel());
        // Cost is the persisted cost, not a newly sampled definition.itemDownloadLevelCost().
    }

    private void requireTeam(String teamId, TeamOwnedResearch team) {
        if (Objects.requireNonNull(team).registry() != registry || !teamId.equals(team.teamId())) {
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

    public record Evaluation(Status status, @Nullable Plan plan) {
        public Evaluation {
            Objects.requireNonNull(status);
            if ((status == Status.READY) == (plan == null))
                throw new IllegalArgumentException("Only ready source evaluation has a plan");
        }
    }

    public record Preparation(Status status, @Nullable Prepared prepared) {
        public Preparation {
            Objects.requireNonNull(status);
            if ((status == Status.READY) == (prepared == null))
                throw new IllegalArgumentException("Only ready source preparation has held transactions");
        }
    }

    public record MemberChange(UUID memberId, int beforeLevel, int afterLevel) {
        public MemberChange {
            Objects.requireNonNull(memberId);
            if (afterLevel < 0 || afterLevel > beforeLevel)
                throw new IllegalArgumentException("Invalid member source change");
        }
    }

    /**
     * No live source, membership provider, database permit or transaction is serialized.
     */
    public record Plan(ResearchDownloadDeliveryPolicy policy, ResourceLocation researchId, int downloadLevel, int cost,
                       String teamId, ObjectList<UUID> members, ObjectList<MemberChange> memberChanges,
                       int teamBeforeLevel, int teamAfterLevel) {
        public Plan {
            Objects.requireNonNull(policy);
            StrictNbt.requireId(Objects.requireNonNull(researchId).toString());
            if (downloadLevel < 1 || cost < 0 || Objects.requireNonNull(teamId).isBlank() || !teamId.equals(teamId.trim())) {
                throw new IllegalArgumentException("Invalid frozen download source metadata");
            }
            var memberCopy = new ObjectArrayList<>(Objects.requireNonNull(members));
            var ids = new ObjectOpenHashSet<UUID>();
            for (var member : memberCopy)
                if (!ids.add(Objects.requireNonNull(member)))
                    throw new IllegalArgumentException("Duplicate frozen member");
            if (memberCopy.isEmpty()) throw new IllegalArgumentException("Download requires original members");
            memberCopy.sort(null);
            members = ObjectLists.unmodifiable(memberCopy);
            var changes = new ObjectArrayList<>(Objects.requireNonNull(memberChanges));
            var changed = new ObjectOpenHashSet<UUID>();
            for (var change : changes) {
                Objects.requireNonNull(change);
                if (!changed.add(change.memberId()) || change.beforeLevel() < cost || change.afterLevel() != change.beforeLevel() - cost) {
                    throw new IllegalArgumentException("Frozen member source cost differs");
                }
            }
            changes.sort(Comparator.comparing(MemberChange::memberId));
            memberChanges = ObjectLists.unmodifiable(changes);
            if (policy == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
                if (!changed.equals(ids) || teamBeforeLevel != -1 || teamAfterLevel != -1)
                    throw new IllegalArgumentException("Invalid aggregate source scope");
            } else if (!changes.isEmpty() || teamBeforeLevel < downloadLevel || teamBeforeLevel < cost
                || teamAfterLevel < 0 || teamAfterLevel != teamBeforeLevel - cost) {
                throw new IllegalArgumentException("Invalid team source scope");
            }
        }
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
            for (var held : players) if (held.source() == source) return held.mutation().owns(source);
            return false;
        }

        public PlayerResearchState.Snapshot snapshot(PlayerResearchState source) {
            requireOwner();
            for (var held : players) if (held.source() == source && owns(source)) return held.mutation().snapshot();
            throw new IllegalStateException("Download does not own this personal source snapshot");
        }

        public boolean owns(TeamOwnedResearch source) {
            requireOwner();
            return terminal == Resolution.REVERSIBLE && team == source && teamMutation != null && teamMutation.owns(source);
        }

        public TeamOwnedResearch.Snapshot snapshot(TeamOwnedResearch source) {
            requireOwner();
            if (!owns(source)) throw new IllegalStateException("Download does not own this team source snapshot");
            return Objects.requireNonNull(teamMutation).snapshot();
        }

        @Override
        public void commit() {
            requireOwner();
            if (terminal != Resolution.REVERSIBLE || commitAttempted)
                throw new IllegalStateException("Download source commit cannot replay");
            commitAttempted = true;
            try {
                for (var held : players) held.mutation().commit();
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
            for (var held : players) held.mutation().complete();
            if (teamMutation != null) teamMutation.complete();
            terminal = Resolution.COMPLETED;
        }

        private boolean allRolledBack() {
            if (teamMutation != null && teamMutation.resolution() != Resolution.ROLLED_BACK) return false;
            for (var held : players) if (held.mutation().resolution() != Resolution.ROLLED_BACK) return false;
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
            tag.setInteger("Version", 1);
            tag.setString("Policy", plan.policy().name());
            tag.setString("Research", plan.researchId().toString());
            tag.setInteger("DownloadLevel", plan.downloadLevel());
            tag.setInteger("Cost", plan.cost());
            tag.setString("Team", plan.teamId());
            tag.setInteger("TeamBefore", plan.teamBeforeLevel());
            tag.setInteger("TeamAfter", plan.teamAfterLevel());
            var members = new NBTTagList();
            for (var member : plan.members()) members.appendTag(new NBTTagString(member.toString()));
            tag.setTag("Members", members);
            var changes = new NBTTagList();
            for (var change : plan.memberChanges()) {
                var saved = new NBTTagCompound();
                saved.setString("Member", change.memberId().toString());
                saved.setInteger("Before", change.beforeLevel());
                saved.setInteger("After", change.afterLevel());
                changes.appendTag(saved);
            }
            tag.setTag("Changes", changes);
            return tag;
        }

        @Override
        public Plan decode(NBTTagCompound tag) {
            StrictNbt.requireKeys(tag, "Version", "Policy", "Research", "DownloadLevel", "Cost", "Team", "TeamBefore", "TeamAfter", "Members", "Changes");
            if (StrictNbt.requireInt(tag, "Version") != 1)
                throw new IllegalArgumentException("Unknown download source plan version");
            var memberTags = StrictNbt.requireList(tag, "Members", Constants.NBT.TAG_STRING);
            var members = new ObjectArrayList<UUID>(memberTags.tagCount());
            for (int index = 0; index < memberTags.tagCount(); index++)
                members.add(uuid(memberTags.getStringTagAt(index)));
            var changeTags = StrictNbt.requireList(tag, "Changes", Constants.NBT.TAG_COMPOUND);
            var changes = new ObjectArrayList<MemberChange>(changeTags.tagCount());
            for (int index = 0; index < changeTags.tagCount(); index++) {
                var saved = changeTags.getCompoundTagAt(index);
                StrictNbt.requireKeys(saved, "Member", "Before", "After");
                changes.add(new MemberChange(uuid(StrictNbt.requireString(saved, "Member")), StrictNbt.requireInt(saved, "Before"), StrictNbt.requireInt(saved, "After")));
            }
            var plan = new Plan(ResearchDownloadDeliveryPolicy.valueOf(StrictNbt.requireString(tag, "Policy")),
                StrictNbt.requireId(tag, "Research"), StrictNbt.requireInt(tag, "DownloadLevel"), StrictNbt.requireInt(tag, "Cost"),
                StrictNbt.requireString(tag, "Team"), members, changes, StrictNbt.requireInt(tag, "TeamBefore"), StrictNbt.requireInt(tag, "TeamAfter"));
            validate(plan);
            return plan;
        }

        private UUID uuid(String value) {
            var id = UUID.fromString(value);
            if (!id.toString().equals(value))
                throw new IllegalArgumentException("Noncanonical frozen download member UUID");
            return id;
        }
    }
}
