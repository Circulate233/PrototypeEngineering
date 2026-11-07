package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardItemDeliveryAdapter;
import com.circulation.prototype_eng.api.research.station.ResearchStationWork;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.time.Duration;

/**
 * Authoritative world owner for research sources, queues, manual and station execution. Membership is captured
 * by the platform, not inferred from network machines. Save failure retains the exact complete world
 * snapshot; further mutations first retry it. No research content or gameplay values are registered.
 */
@SuppressWarnings("unused") // Server hosts and integrations use this public owner API.
public final class ResearchWorld {
    private final Thread owner = Thread.currentThread();
    private final ResearchRegistry registry;
    private final NetworkResourceRegistry resources;
    private final ResearchPermissionPolicy permissions;
    private final TeamResearchStart admission;
    private final ResearchEligibility eligibility;
    private final Consumer<ResearchWorldSnapshot> writer;
    private Object2ObjectLinkedOpenHashMap<UUID, PlayerResearchState> players = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<String, TeamProgress> teams = new Object2ObjectLinkedOpenHashMap<>();
    @Nullable private ResearchWorldSnapshot pendingSave;
    private long serverTime;
    private boolean executing;
    private boolean dirty;
    private boolean closing;
    private boolean closed;
    private boolean checkpointRequested;
    @Nullable private StationTick activeTick;
    @Nullable private Throwable fatalFailure;
    @Nullable private PlayerResearchState.RewardSession activeReward;

    public ResearchWorld(ResearchRegistry registry, NetworkResourceRegistry resources,
                         ResearchPermissionPolicy permissions, Consumer<ResearchWorldSnapshot> writer) {
        this.registry = Objects.requireNonNull(registry);
        this.resources = Objects.requireNonNull(resources);
        this.permissions = Objects.requireNonNull(permissions);
        this.writer = Objects.requireNonNull(writer);
        admission = new TeamResearchStart(registry, resources, permissions);
        eligibility = new ResearchEligibility(registry);
    }

    public long serverTime() { requireOwner(); return serverTime; }
    public boolean savePending() { requireOwner(); return pendingSave != null; }
    public boolean busy() { requireOwner(); return activeTick != null || executing; }
    public boolean faulted() { requireOwner(); return fatalFailure != null; }

    /** Read-only snapshot; changing an authoritative source still requires this owner's checkpoint. */
    public ResearchWorldSnapshot snapshot() {
        requireOwner();
        requireHealthy();
        if (activeTick != null) { throw new IllegalStateException("Research tick still owns the world"); }
        return snapshotState();
    }

    /**
     * Display capture for the platform's scheduler MAIN action, using its current actor and membership.
     * Reads never create sources, flush a save or retry a material receipt. Admission conditions run
     * only on an actual start request; structural targets are deliberately weaker display facts.
     */
    @Nullable public ResearchPlayerView project(UUID actor, OwnerTeamResolution membership,
                                                ResearchPermissionContext permission, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        Objects.requireNonNull(actor);
        Objects.requireNonNull(membership);
        Objects.requireNonNull(permission);
        Objects.requireNonNull(current);
        if (!actor.equals(membership.ownerId()) || !actor.equals(permission.actorId()) || !actor.equals(permission.personalTarget())) {
            throw new IllegalArgumentException("Research view actor or personal target changed");
        }
        String teamId = membership.accepted() ? membership.teamId() : null;
        if (!Objects.equals(teamId, permission.actorTeamId()) || !Objects.equals(teamId, permission.targetTeamId())) {
            throw new IllegalArgumentException("Research view must target the actor's current team");
        }
        if (executing || activeTick != null || closing || closed) return null;
        executing = true; // Permission/provider callbacks cannot reenter world mutations during a read.
        try {
            if (!current.getAsBoolean()) return null;
            var sourceSnapshots = new ObjectArrayList<PlayerResearchState.Snapshot>();
            var own = players.get(actor);
            if (own != null) {
                if (own.busy()) return null;
                sourceSnapshots.add(own.snapshot());
            }
            var team = teamId == null ? null : teams.get(teamId);
            if (team != null && (team.queue.busy() || team.research.busy() || team.manual.checkpointPending())) return null;
            var memberSources = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
            if (membership.accepted()) {
                for (var member : membership.members()) {
                    var source = players.get(member);
                    var detached = new PlayerResearchState(member);
                    if (source != null) {
                        if (source.busy()) return null;
                        var snapshot = source.snapshot();
                        if (!member.equals(actor)) sourceSnapshots.add(snapshot);
                        detached.restore(new PlayerResearchState.Snapshot(member, snapshot.completedLevels(), ObjectList.of()));
                    }
                    memberSources.put(member, detached);
                }
            }
            var teamSnapshot = team == null ? null : team.research.snapshot();
            var queueSnapshot = team == null ? null : team.queue.snapshot();
            var manualSnapshots = team == null ? ObjectList.<ManualResearchSession>of() : team.manual.sessions();
            var ownSnapshot = own == null ? null : own.snapshot();
            var personalDecision = Objects.requireNonNull(permissions.can(ResearchPermissionPolicy.Action.VIEW_OWN_RESEARCH, permission));
            var viewDecision = ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM;
            var queueDecision = viewDecision;
            var startDecision = viewDecision;
            var blacklistDecision = viewDecision;
            if (membership.accepted()) {
                viewDecision = Objects.requireNonNull(permissions.can(ResearchPermissionPolicy.Action.VIEW_TEAM_RESEARCH, permission));
                queueDecision = Objects.requireNonNull(permissions.can(ResearchPermissionPolicy.Action.REQUEST_QUEUE_STATE, permission));
                startDecision = Objects.requireNonNull(permissions.can(ResearchPermissionPolicy.Action.START_RESEARCH, permission));
                blacklistDecision = Objects.requireNonNull(permissions.can(ResearchPermissionPolicy.Action.BLACKLIST_TEAM_RESEARCH, permission));
            }
            var claims = new ObjectArrayList<ResearchPlayerView.ClaimSummary>();
            var personalLevels = Object2IntMaps.<ResourceLocation>emptyMap();
            if (personalDecision == ResearchPermissionPolicy.Decision.ALLOWED && ownSnapshot != null) {
                personalLevels = ownSnapshot.completedLevels();
                for (var claim : ownSnapshot.rewardClaims()) {
                    var choices = new ObjectArrayList<ResourceLocation>();
                    for (var group : registry.require(claim.key().researchId()).rewardGroupsForLevel(claim.key().level())) choices.add(group.id());
                    claims.add(new ResearchPlayerView.ClaimSummary(claim.key().researchId(), claim.key().level(),
                        claim.status(), claim.selectedGroupId(), choices));
                }
            }
            var sharedLevels = Object2IntMaps.<ResourceLocation>emptyMap();
            var structuralTargets = new Object2IntOpenHashMap<ResourceLocation>();
            var blacklist = ObjectSets.<ResourceLocation>emptySet();
            if (viewDecision == ResearchPermissionPolicy.Decision.ALLOWED) {
                blacklist = team == null ? ObjectSets.emptySet() : team.blacklist;
                var structural = eligibility.calculateStructural(Objects.requireNonNull(teamId), membership.members(), memberSources,
                    teamSnapshot == null ? Object2IntMaps.emptyMap() : teamSnapshot.levels(), blacklist);
                sharedLevels = structural.sharedLevels();
                for (var definition : registry.all()) {
                    int completed = structural.completedLevel(definition.id());
                    if (completed < definition.maxLevel() && eligibility.structurallyEligible(structural, definition.id(), completed + 1)) {
                        structuralTargets.put(definition.id(), completed + 1);
                    }
                }
            }
            var queueRows = new ObjectArrayList<ResearchPlayerView.QueueRow>();
            var numericProgress = new Object2LongOpenHashMap<ResourceLocation>();
            var manualRows = new ObjectArrayList<ResearchPlayerView.ManualRow>();
            long queueEpoch = 0;
            if (queueDecision == ResearchPermissionPolicy.Decision.ALLOWED && queueSnapshot != null) {
                queueEpoch = queueSnapshot.epoch();
                for (var entry : queueSnapshot.entries()) queueRows.add(new ResearchPlayerView.QueueRow(entry.key().queueEntryId(),
                    entry.key().researchId(), entry.key().targetLevel(), entry.requestedBy(), entry.createdAtWorldTime()));
                for (var progress : queueSnapshot.resourceProgress().object2LongEntrySet()) numericProgress.put(progress.getKey().id(), progress.getLongValue());
                for (var manual : manualSnapshots) manualRows.add(new ResearchPlayerView.ManualRow(manual.queueKey().queueEntryId(),
                    manual.status(), manual.pauseReason(), manual.contributor(), manual.work()));
            }
            var status = !membership.accepted()
                ? membership.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE
                    ? ResearchPlayerView.TeamStatus.UNAVAILABLE : ResearchPlayerView.TeamStatus.MISSING_TEAM
                : viewDecision == ResearchPermissionPolicy.Decision.ALLOWED || queueDecision == ResearchPermissionPolicy.Decision.ALLOWED
                    ? ResearchPlayerView.TeamStatus.AVAILABLE : ResearchPlayerView.TeamStatus.DENIED;
            var projectedTeam = new ResearchPlayerView.Team(status, teamId == null ? "" : teamId, viewDecision, queueDecision,
                startDecision, blacklistDecision, sharedLevels, structuralTargets, blacklist, queueEpoch, queueRows, numericProgress, manualRows);
            var result = new ResearchPlayerView(actor, serverTime, pendingSave != null, personalDecision, personalLevels, projectedTeam, claims);
            if (!current.getAsBoolean() || !sourcesCurrent(sourceSnapshots)) return null;
            if (team != null && (team.queue.busy() || team.research.busy() || team.manual.checkpointPending()
                || !team.research.snapshot().equals(teamSnapshot) || !team.queue.snapshot().equals(queueSnapshot)
                || !team.manual.sessions().equals(manualSnapshots))) return null;
            return result;
        } finally { executing = false; }
    }

    /** Exposes an existing source to an authoritative integration; never implicitly creates one. */
    public PlayerResearchState player(UUID id) {
        return operate(serverTime, false, () -> {
            var player = players.get(Objects.requireNonNull(id));
            if (player == null) { throw new IllegalArgumentException("Unknown research player " + id); }
            dirty = true;
            return player;
        });
    }

    /** Restore all sources before any manual session; invalid input never publishes a partial world. */
    public void restore(ResearchWorldSnapshot saved) {
        operate(saved.serverTime(), false, () -> {
            if (!players.isEmpty() || !teams.isEmpty()) { throw new IllegalStateException("Research world has already been initialized"); }
            var restoredPlayers = new Object2ObjectLinkedOpenHashMap<UUID, PlayerResearchState>();
            for (var snapshot : saved.players()) {
                var state = new PlayerResearchState(snapshot.playerUuid());
                state.restore(snapshot);
                restoredPlayers.put(snapshot.playerUuid(), state);
            }
            var restoredTeams = new Object2ObjectLinkedOpenHashMap<String, TeamProgress>();
            for (var snapshot : saved.teams()) {
                var team = new TeamProgress(snapshot.teamId(), TeamResearchQueue.restore(registry, resources, snapshot.queue()), restoredPlayers);
                team.research.restore(snapshot.research());
                team.knownMembers = snapshot.knownMembers();
                team.blacklist.addAll(snapshot.blacklist());
                team.manual.restore(snapshot.manualSessions());
                team.station.restore(snapshot.stationWork());
                restoredTeams.put(snapshot.teamId(), team);
            }
            players = restoredPlayers;
            teams.putAll(restoredTeams);
            dirty = true; // ACTIVE sessions were intentionally changed to PAUSED during restore.
            return null;
        });
    }

    /** Active saved teams, queued requesters and pending contributors must be resolved even offline. */
    public ObjectSet<UUID> trackedPlayers() {
        requireOwner();
        var tracked = new ObjectOpenHashSet<UUID>();
        for (var team : teams.values()) {
            if (team.queue.size() == 0 && team.manual.sessions().isEmpty()) { continue; }
            tracked.addAll(team.knownMembers);
            for (var entry : team.queue.queueSnapshot()) { tracked.add(entry.requestedBy()); }
            for (var session : team.manual.sessions()) {
                if (session.contributor() != null) { tracked.add(session.contributor()); }
                if (session.materialIntent() != null) { tracked.add(session.materialIntent().contributor()); }
                if (session.completionIntent() != null) { tracked.addAll(session.completionIntent().baselines().keySet()); }
            }
        }
        return ObjectSets.unmodifiable(tracked);
    }

    public void playerLogin(UUID player, long time) {
        operate(time, false, () -> { ensurePlayer(player); if (dirty) { persist(); } return null; });
    }

    /**
     * Claims this player's permanent eligibility through the world's single durable writer. No team
     * membership or current research level is required: forgetting and changing teams retain rewards.
     * The exact reward session is the only busy source admitted to its own intermediate checkpoints.
     */
    public ResearchRewardClaimer.Result claimReward(UUID actor, ResourceLocation id, int level,
            ResourceLocation group, long time, RewardItemDeliveryAdapter delivery, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        Objects.requireNonNull(actor);
        Objects.requireNonNull(group);
        Objects.requireNonNull(delivery);
        Objects.requireNonNull(current);
        if (activeTick != null && !executing) { return rewardResult(ResearchRewardClaimer.Status.BUSY); }
        return operate(time, false, () -> {
            if (!current.getAsBoolean()) { return rewardResult(ResearchRewardClaimer.Status.STALE_CONTEXT); }
            var definition = registry.require(id);
            definition.stageForLevel(level);
            var player = players.get(actor);
            if (player == null) { return rewardResult(ResearchRewardClaimer.Status.MISSING_CLAIM); }
            for (var source : players.values()) {
                if (source.busy()) { return rewardResult(ResearchRewardClaimer.Status.BUSY); }
            }
            for (var team : teams.values()) {
                if (team.research.busy() || team.queue.busy()) { return rewardResult(ResearchRewardClaimer.Status.BUSY); }
            }
            var key = new RewardClaim.Key(actor, id, level);
            var reward = player.tryRewardSession(key);
            if (reward == null) { return rewardResult(ResearchRewardClaimer.Status.BUSY); }
            try (reward) {
                activeReward = reward;
                return new ResearchRewardClaimer().claim(new ResearchRewardClaimer.Request(player, definition, level,
                    group, time, claim -> rewardCheckpoint(player, reward, claim), delivery, current), reward);
            } finally { activeReward = null; }
        });
    }

    private void rewardCheckpoint(PlayerResearchState player, PlayerResearchState.RewardSession reward, RewardClaim claim) {
        if (!executing || activeReward != reward || players.get(player.playerUuid()) != player
            || !reward.owns(player, claim.key()) || reward.requireCurrent() != claim) {
            throw new IllegalStateException("Reward checkpoint does not own its exact player/claim session");
        }
        dirty = true;
        persist(reward);
    }

    private static ResearchRewardClaimer.Result rewardResult(ResearchRewardClaimer.Status status) {
        return new ResearchRewardClaimer.Result(status, status.name(), Optional.empty());
    }

    /** Logout pauses every matching active session, including work waiting behind another queue head. */
    public void playerLogout(UUID player, long time, Set<UUID> remainingOnline) {
        operate(time, false, () -> {
            if (remainingOnline.contains(player)) { throw new IllegalArgumentException("Logout snapshot still contains the leaving player"); }
            for (var team : teams.values()) {
                var context = new ManualResearch.Context(time, team.knownMembers, remainingOnline, () -> true);
                for (var session : team.manual.sessions()) {
                    if (session.status() == ManualResearchSession.Status.ACTIVE && player.equals(session.contributor())) {
                        team.manual.pause(session.queueKey().queueEntryId(), context, ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE);
                    }
                }
            }
            if (dirty) { persist(); }
            return null;
        });
    }

    /** Admission uses structural facts and invokes the real requester condition exactly at start. */
    public TeamResearchStart.Result start(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                          ResourceLocation id, int target, TeamResearchStart.Environment environment,
                                          long time, Set<UUID> online, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        if (activeTick != null && !executing) { return new TeamResearchStart.Result(TeamResearchStart.Status.QUEUE_BUSY, null, null); }
        return operate(time, false, () -> {
            if (!membership.accepted() || !current.getAsBoolean()) { return staleStart(); }
            if (!membership.teamId().equals(permission.targetTeamId())) { throw new IllegalArgumentException("Admission target team changed"); }
            var definition = registry.require(id);
            boolean manual = definition.stageForLevel(target).researchTier() == 0;
            if (manual && (!membership.members().contains(permission.actorId()) || !online.contains(permission.actorId()))) { return staleStart(); }
            var team = captureTeam(membership.teamId(), membership.members());
            ensurePlayer(permission.actorId());
            if (team.queue.busy() || team.research.busy() || membersBusy(team.knownMembers)) {
                return new TeamResearchStart.Result(TeamResearchStart.Status.QUEUE_BUSY, null, null);
            }
            var capturedSources = new ObjectArrayList<PlayerResearchState.Snapshot>();
            for (var member : team.knownMembers) { capturedSources.add(players.get(member).snapshot()); }
            var capturedTeam = team.research.snapshot();
            BooleanSupplier proof = () -> current.getAsBoolean() && capturedTeam.equals(team.research.snapshot())
                && !team.research.busy() && sourcesCurrent(capturedSources);
            var view = structural(team, false);
            var result = admission.start(new TeamResearchStart.Request(team.queue, view, permission, id, target, environment, time, proof));
            if (result.status() != TeamResearchStart.Status.STARTED) { if (dirty) { persist(); } return result; }
            var entry = Objects.requireNonNull(result.entry());
            dirty = true;
            if (!manual) { persist(); return result; }
            try {
                var status = team.manual.begin(entry, permission.actorId(), new ManualResearch.Context(time, team.knownMembers, online, proof));
                if (status == ManualResearch.Status.STARTED) { return result; }
                if (team.manual.session(entry.key().queueEntryId()) != null || team.manual.checkpointPending()) {
                    throw new IllegalStateException("Unsuccessful manual start still owns persistent work");
                }
                team.queue.removeUnstarted(entry.key());
                persist();
                return staleStart();
            } catch (RuntimeException | Error failure) {
                // begin may already have installed its intent before persistence failed. Keep that pair.
                if (team.manual.session(entry.key().queueEntryId()) == null && !team.manual.checkpointPending()) {
                    try {
                        if (queued(team.queue, entry.key())) { team.queue.removeUnstarted(entry.key()); }
                        persist();
                    } catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
                }
                throw failure;
            }
        });
    }

    public ManualResearch.Status takeover(OwnerTeamResolution membership, UUID actor, ResourceLocation id, int target,
                                           long time, Set<UUID> online, BooleanSupplier current) {
        return takeover(membership, actor, id, target, 0, time, online, current);
    }

    /** A nonzero entry ID binds takeover to the original manual session shown to the requester. */
    public ManualResearch.Status takeover(OwnerTeamResolution membership, UUID actor, ResourceLocation id, int target,
                                           long expectedEntryId, long time, Set<UUID> online, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        if (expectedEntryId < 0) throw new IllegalArgumentException("Invalid expected research queue entry");
        if (activeTick != null && !executing) { return ManualResearch.Status.RECOVERY_PENDING; }
        return operate(time, false, () -> {
            if (!membership.accepted() || !current.getAsBoolean()) { return ManualResearch.Status.STALE_CONTEXT; }
            var team = teams.get(membership.teamId());
            if (team == null) { return ManualResearch.Status.STALE_CONTEXT; }
            captureTeam(membership.teamId(), membership.members());
            for (var entry : team.queue.queueSnapshot()) {
                if (entry.key().researchId().equals(id) && entry.key().targetLevel() == target) {
                    if (expectedEntryId != 0 && expectedEntryId != entry.key().queueEntryId()) return ManualResearch.Status.STALE_CONTEXT;
                    if (team.manual.session(entry.key().queueEntryId()) == null) { return ManualResearch.Status.STALE_CONTEXT; }
                    return team.manual.takeover(entry.key().queueEntryId(), actor, new ManualResearch.Context(time, team.knownMembers, online, current));
                }
            }
            return ManualResearch.Status.STALE_CONTEXT;
        });
    }

    /** Persist a permission-checked blacklist; the next tick settles pending work before pruning it. */
    public ResearchPermissionPolicy.Decision setBlacklisted(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                                            ResourceLocation id, boolean value, long time, BooleanSupplier current) {
        return operate(time, false, () -> {
            registry.require(id);
            if (!membership.accepted() || !membership.teamId().equals(permission.targetTeamId()) || !current.getAsBoolean()) {
                return ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER;
            }
            var decision = permissions.can(ResearchPermissionPolicy.Action.BLACKLIST_TEAM_RESEARCH, permission);
            if (decision != ResearchPermissionPolicy.Decision.ALLOWED) { return decision; }
            if (!current.getAsBoolean()) { return ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER; }
            var team = captureTeam(membership.teamId(), membership.members());
            boolean changed = value ? team.blacklist.add(id) : team.blacklist.remove(id);
            if (changed) { dirty = true; }
            if (dirty) { persist(); }
            return decision;
        });
    }

    /** One call per server END boundary, after worker exit; unavailable membership never means teardown. */
    public void tick(long time, Map<UUID, OwnerTeamResolution> captures, Set<UUID> online,
                     Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current) {
        operate(time, false, () -> {
            var memberships = validateMemberships(captures);
            if (memberships == null || !current.getAsBoolean()) { return null; }
            if (!captures.keySet().containsAll(trackedPlayers())) { throw new IllegalArgumentException("Research capture omitted tracked players"); }
            for (var entry : memberships.entrySet()) { captureTeam(entry.getKey(), entry.getValue()); }
            for (var team : teams.values()) {
                if (team.queue.size() == 0 && team.manual.sessions().isEmpty()) { continue; }
                var members = memberships.getOrDefault(team.id, ObjectSets.emptySet());
                if (!members.equals(team.knownMembers)) { team.knownMembers = members; dirty = true; }
            }
            for (var team : new ObjectArrayList<>(teams.values())) {
                if (!current.getAsBoolean()) { break; }
                if (team.queue.size() == 0 && team.manual.sessions().isEmpty()) { continue; }
                if (team.queue.busy() || team.research.busy() || membersBusy(team.knownMembers)) { continue; }
                var context = new ManualResearch.Context(time, team.knownMembers, online, current);
                if (!reconcile(team, context, inventories)) { continue; }
                var head = team.queue.activeHead().orElse(null);
                if (head == null || registry.require(head.key().researchId()).stageForLevel(head.key().targetLevel()).researchTier() != 0) { continue; }
                var session = team.manual.session(head.key().queueEntryId());
                if (session == null) { throw new IllegalStateException("Tier-zero queue head has no manual session"); }
                team.manual.advance(head.key().queueEntryId(), context, inventoryFor(session, inventories));
            }
            if (dirty) { persist(); }
            return null;
        });
    }

    /** Captures no business effects; the scheduler owns this execution before its first advance. */
    @SuppressWarnings("ReturnOfInnerClass") // The retained scheduler execution deliberately owns this world's pending work.
    public PlannedExecution prepareTick(long time, Map<UUID, OwnerTeamResolution> captures, Set<UUID> online,
                                        Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current,
                                        ResearchStations stations, Runnable refresh, PlannedTaskExecutor tasks,
                                        PlannedDeadline forwardDeadline) {
        requireOwner();
        requireHealthy();
        if (activeTick != null || executing || closed || closing) { throw new IllegalStateException("Research owner already has a tick or is closing"); }
        return new StationTick(time, Object2ObjectMaps.unmodifiable(new Object2ObjectOpenHashMap<>(captures)),
            ObjectSets.unmodifiable(new ObjectOpenHashSet<>(online)), inventories, current, stations, refresh, tasks, forwardDeadline);
    }

    private boolean reconcile(TeamProgress team, ManualResearch.Context context, Function<UUID, ManualResearchInventory> inventories) {
        // Cancellation can complete an already-paid head and change subsequent eligibility. The queue is bounded.
        for (int round = 0; round <= TeamResearchQueue.CAPACITY; round++) {
            var view = structural(team, team.knownMembers.isEmpty());
            var stationKey = team.station.key();
            if (stationKey != null && !eligibility.structurallyEligible(view, stationKey.researchId(), stationKey.targetLevel())) {
                try {
                    if (!team.station.cancel(PlannedDeadline.after(Duration.ofSeconds(5)))) { return false; }
                } catch (RuntimeException | Error failure) {
                    if (activeTick != null) { activeTick.stationOperation = true; }
                    throw failure;
                }
                dirty = true;
            }
            boolean changed = false;
            boolean pending = false;
            for (var session : team.manual.sessions()) {
                var key = session.queueKey();
                if (!eligibility.structurallyEligible(view, key.researchId(), key.targetLevel())) {
                    var result = team.manual.cancelInvalidated(key.queueEntryId(), context, inventoryFor(session, inventories));
                    if (result == ManualResearch.Status.CANCELLED || result == ManualResearch.Status.COMPLETED) { changed = true; }
                    else { pending = true; }
                }
            }
            if (pending || !context.current().getAsBoolean()) { return false; }
            if (changed) { continue; }
            boolean needsPrune = false;
            for (var entry : team.queue.queueSnapshot()) {
                if (!eligibility.structurallyEligible(view, entry.key().researchId(), entry.key().targetLevel())) { needsPrune = true; break; }
            }
            if (!needsPrune) { return true; }
            var prune = team.queue.preparePrune(view);
            boolean removed = !prune.removed().isEmpty();
            try { prune.commit(); }
            catch (RuntimeException | Error failure) {
                try { prune.rollback(); }
                catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
            prune.complete();
            if (removed) { dirty = true; }
            return true;
        }
        throw new IllegalStateException("Manual cancellation did not converge within its bounded queue");
    }

    @Nullable private static ManualResearchInventory inventoryFor(ManualResearchSession session, Function<UUID, ManualResearchInventory> inventories) {
        UUID player = session.materialIntent() == null ? session.contributor() : session.materialIntent().contributor();
        return player == null ? null : inventories.apply(player);
    }

    @Nullable private static Map<String, ObjectSet<UUID>> validateMemberships(Map<UUID, OwnerTeamResolution> captures) {
        // A partial provider capture is not proof of removal or contradictory membership.
        for (var resolution : captures.values()) {
            if (Objects.requireNonNull(resolution).status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE) { return null; }
        }
        var memberships = new Object2ObjectOpenHashMap<String, ObjectSet<UUID>>();
        for (var entry : captures.entrySet()) {
            var resolution = Objects.requireNonNull(entry.getValue());
            if (!resolution.ownerId().equals(entry.getKey())) { throw new IllegalArgumentException("Research membership capture changed its owner"); }
            if (!resolution.accepted()) { continue; }
            var previous = memberships.putIfAbsent(resolution.teamId(), resolution.members());
            if (previous != null && !previous.equals(resolution.members())) { throw new IllegalArgumentException("Contradictory research team captures"); }
            for (var member : resolution.members()) {
                var peer = captures.get(member);
                if (peer == null || !peer.accepted() || !peer.teamId().equals(resolution.teamId())
                    || !peer.members().equals(resolution.members())) { throw new IllegalArgumentException("Incomplete research membership capture"); }
            }
        }
        return memberships;
    }

    private TeamProgress captureTeam(String id, Collection<UUID> members) {
        for (var member : members) { ensurePlayer(member); }
        var team = teams.get(id);
        if (team == null) { team = new TeamProgress(id, new TeamResearchQueue(registry, resources, id), players); teams.put(id, team); dirty = true; }
        var copy = ObjectSets.unmodifiable(new ObjectOpenHashSet<>(members));
        if (!copy.equals(team.knownMembers)) { team.knownMembers = copy; dirty = true; }
        return team;
    }

    private void ensurePlayer(UUID id) {
        Objects.requireNonNull(id);
        if (!players.containsKey(id)) { players.put(id, new PlayerResearchState(id)); dirty = true; }
    }

    private TeamResearchView structural(TeamProgress team, boolean disappeared) {
        var members = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
        for (var id : team.knownMembers) { members.put(id, Objects.requireNonNull(players.get(id))); }
        var blacklist = new ObjectOpenHashSet<>(team.blacklist);
        if (disappeared) { for (var definition : registry.all()) { blacklist.add(definition.id()); } }
        return eligibility.calculateStructural(team.id, team.knownMembers, members, team.research.completedLevelsSnapshot(), blacklist);
    }

    private boolean membersBusy(Collection<UUID> members) {
        for (var id : members) { if (Objects.requireNonNull(players.get(id)).busy()) { return true; } }
        return false;
    }

    private boolean sourcesCurrent(ObjectList<PlayerResearchState.Snapshot> snapshots) {
        for (var snapshot : snapshots) {
            var current = players.get(snapshot.playerUuid());
            if (current == null || current.busy() || !current.snapshot().equals(snapshot)) { return false; }
        }
        return true;
    }

    private void manualCheckpoint(TeamProgress team, ManualResearch.CheckpointState state) {
        if (!executing || teams.get(team.id) != team || !state.queue().equals(team.queue.snapshot())
            || !state.team().equals(team.research.snapshot()) || !state.sessions().equals(team.manual.sessions())
            || !sourcesCurrent(state.players())) { throw new IllegalStateException("Manual checkpoint does not own the world records it supplied"); }
        // Sources are shared authoritative objects. Preserve every other player; never replace the global table.
        dirty = true;
        persist();
    }

    public void checkpoint(long time) {
        requireOwner();
        requireHealthy();
        if (time < serverTime) { throw new IllegalArgumentException("Research checkpoint time moved backwards"); }
        if (activeTick != null || activeReward != null) { checkpointRequested = true; return; }
        operate(time, true, () -> { persist(); return null; });
    }

    /** Stop accepting work before the final write; a failed write retains this owner for retry. */
    public void close(long time) {
        requireOwner();
        if (closed) { return; }
        requireHealthy();
        if (executing) { throw new IllegalStateException("Research close cannot reenter an operation"); }
        if (time < serverTime) { throw new IllegalArgumentException("Research close time moved backwards"); }
        closing = true;
        if (activeTick != null) {
            var pending = activeTick;
            pending.cancel(Reason.SERVER_STOP);
            pending.awaitExit();
            if (!pending.advance(PlannedDeadline.after(Duration.ofSeconds(5)))) {
                throw new IllegalStateException("Research shutdown retains an unconfirmed station transaction");
            }
        }
        operate(time, true, () -> {
            persist();
            closed = true;
            return null;
        });
    }

    private ResearchWorldSnapshot snapshotState() {
        return snapshotState(null);
    }

    private ResearchWorldSnapshot snapshotState(@Nullable PlayerResearchState.RewardSession reward) {
        var playerSnapshots = new ObjectArrayList<PlayerResearchState.Snapshot>();
        for (var player : players.values()) {
            if (reward != null && reward == activeReward && reward.owns(player, reward.requireCurrent().key())) {
                playerSnapshots.add(reward.snapshot());
                continue;
            }
            if (player.busy()) { throw new IllegalStateException("Cannot snapshot a research source owned by another transaction"); }
            playerSnapshots.add(player.snapshot());
        }
        var teamSnapshots = new ObjectArrayList<ResearchWorldSnapshot.Team>();
        for (var team : teams.values()) {
            if (team.research.busy()) { throw new IllegalStateException("Cannot snapshot a pending team research mutation"); }
            teamSnapshots.add(new ResearchWorldSnapshot.Team(team.id, team.knownMembers, team.blacklist,
                team.research.snapshot(), team.queue.snapshot(), team.manual.sessions(), team.station.snapshot()));
        }
        return new ResearchWorldSnapshot(serverTime, playerSnapshots, teamSnapshots);
    }

    private void persist() {
        persist(null);
    }

    private void persist(@Nullable PlayerResearchState.RewardSession reward) {
        if (pendingSave != null) { throw new IllegalStateException("Previous world checkpoint still owns its snapshot"); }
        pendingSave = snapshotState(reward);
        flushPending(reward);
    }

    private void flushPending() {
        flushPending(null);
    }

    private void flushPending(@Nullable PlayerResearchState.RewardSession reward) {
        if (pendingSave == null) { return; }
        if (!pendingSave.equals(snapshotState(reward))) { throw new IllegalStateException("Pending research save lost its exact world state"); }
        writer.accept(pendingSave);
        if (!pendingSave.equals(snapshotState(reward))) { throw new IllegalStateException("Research writer changed the world it was saving"); }
        pendingSave = null;
        dirty = false;
        checkpointRequested = false;
    }

    private <T> T operate(long time, boolean allowClosing, Supplier<T> action) {
        requireOwner();
        requireHealthy();
        if (executing || activeTick != null || closed || closing && !allowClosing) { throw new IllegalStateException("Research owner is busy or closing"); }
        if (time < serverTime) { throw new IllegalArgumentException("Research server time moved backwards"); }
        executing = true;
        try {
            flushPending();
            for (var team : teams.values()) { team.manual.retryCheckpoint(); }
            serverTime = time;
            return action.get();
        }
        finally { executing = false; }
    }

    private void requireHealthy() {
        if (fatalFailure != null) { throw new IllegalStateException("Research world retains a fatal station material failure", fatalFailure); }
    }

    private void requireOwner() { if (Thread.currentThread() != owner) { throw new IllegalStateException("Research world requires its owner thread"); } }
    private static TeamResearchStart.Result staleStart() { return new TeamResearchStart.Result(TeamResearchStart.Status.STALE_CONTEXT, null, null); }
    private static boolean queued(TeamResearchQueue queue, TeamResearchQueue.Key key) {
        for (var entry : queue.queueSnapshot()) { if (entry.key().equals(key)) { return true; } }
        return false;
    }

    private final class TeamProgress {
        private final String id;
        private final TeamResearchQueue queue;
        private final TeamOwnedResearch research;
        private final ManualResearch manual;
        private final ResearchStationWork station;
        private ObjectSet<UUID> knownMembers = ObjectSets.emptySet();
        private final ObjectSet<ResourceLocation> blacklist = new ObjectOpenHashSet<>();

        private TeamProgress(String id, TeamResearchQueue queue, Map<UUID, PlayerResearchState> sources) {
            this.id = id;
            this.queue = queue;
            research = new TeamOwnedResearch(id, registry);
            manual = new ManualResearch(queue, research, sources, state -> manualCheckpoint(this, state));
            station = new ResearchStationWork(queue, research, sources);
        }
    }

    /** One server tick retains its team cursor, source execution and lifecycle leases until settlement. */
    private final class StationTick implements PlannedExecution {
        private final long time;
        private final Map<UUID, OwnerTeamResolution> captures;
        private final Set<UUID> online;
        private final Function<UUID, ManualResearchInventory> inventories;
        private final BooleanSupplier current;
        private final ResearchStations stations;
        private final Runnable refresh;
        private final PlannedTaskExecutor tasks;
        private final PlannedDeadline forwardDeadline;
        private final ObjectArrayList<MachineLifecycle.ScheduledExecution> handles = new ObjectArrayList<>();
        @Nullable private ObjectArrayList<TeamProgress> order;
        @Nullable private TeamProgress activeTeam;
        @Nullable private ResearchStationWork.Saved before;
        @Nullable private Throwable stationFailure;
        private int cursor;
        private boolean stationOperation;
        private boolean stopped;
        private boolean finished;

        private StationTick(long time, Map<UUID, OwnerTeamResolution> captures, Set<UUID> online,
                            Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current,
                            ResearchStations stations, Runnable refresh, PlannedTaskExecutor tasks, PlannedDeadline deadline) {
            if (time < serverTime) { throw new IllegalArgumentException("Research tick time moved backwards"); }
            this.time = time; this.captures = captures; this.online = online;
            this.inventories = Objects.requireNonNull(inventories); this.current = Objects.requireNonNull(current);
            this.stations = Objects.requireNonNull(stations); this.refresh = Objects.requireNonNull(refresh);
            this.tasks = Objects.requireNonNull(tasks); forwardDeadline = Objects.requireNonNull(deadline);
        }

        @Override public boolean advance(PlannedDeadline cleanupDeadline) {
            requireOwner();
            Objects.requireNonNull(cleanupDeadline);
            if (finished) { return true; }
            requireHealthy();
            if (closed || closing && activeTick != this) { throw new IllegalStateException("Research tick belongs to a closed or closing world"); }
            if (executing || activeTick != null && activeTick != this) { throw new IllegalStateException("Research tick is executing or stale"); }
            activeTick = this;
            stationOperation = false;
            executing = true;
            try {
                flushPending();
                for (var team : teams.values()) { team.manual.retryCheckpoint(); }
                if (order == null) { initialize(); }
                if (activeTeam != null) {
                    var work = activeTeam.station;
                    if (work.pending()) {
                        if (!forward()) { work.requestStop(Reason.EPOCH_CHANGED); }
                        cleanup(() -> work.resolve(cleanupDeadline));
                        if (work.pending()) { return false; }
                    }
                    if (!finishTeam()) { return false; }
                }
                while (forward() && cursor < Objects.requireNonNull(order).size()) {
                    var team = order.get(cursor++);
                    if (team.queue.size() == 0 && team.manual.sessions().isEmpty() && team.station.key() == null) { continue; }
                    if (team.queue.busy() || team.research.busy() || membersBusy(team.knownMembers)) { continue; }
                    stationOperation = true;
                    var savedKey = team.station.key();
                    if (savedKey != null && team.station.sources() == null) {
                        if (!eligibility.structurallyEligible(structural(team, team.knownMembers.isEmpty()),
                            savedKey.researchId(), savedKey.targetLevel())) {
                            var cleanupSources = stations.restoreForCleanup(team.station.sites());
                            if (cleanupSources != null && team.station.cancelRestored(cleanupSources)) { dirty = true; }
                        } else {
                            refresh.run();
                            if (!forward()) { break; }
                            int savedTier = registry.require(savedKey.researchId()).researchTierForLevel(savedKey.targetLevel());
                            var restoredSources = stations.restore(team.id, savedTier, team.station.sites());
                            if (restoredSources != null) { team.station.resume(restoredSources); }
                        }
                    }
                    stationOperation = false;
                    var context = new ManualResearch.Context(time, team.knownMembers, online, this::forward);
                    if (!reconcile(team, context, inventories)) { continue; }
                    var head = team.queue.activeHead().orElse(null);
                    if (head == null) { continue; }
                    int tier = registry.require(head.key().researchId()).researchTierForLevel(head.key().targetLevel());
                    if (tier == 0) {
                        var session = Objects.requireNonNull(team.manual.session(head.key().queueEntryId()), "Manual head has no session");
                        team.manual.advance(head.key().queueEntryId(), context, inventoryFor(session, inventories));
                        continue;
                    }
                    refresh.run();
                    if (!forward()) { break; }
                    stationOperation = true;
                    var source = team.station.sources();
                    var captured = source == null ? null : stations.renew(source);
                    if (source != null && captured == null) {
                        if (!team.station.suspend()) { throw new IllegalStateException("Inactive station cannot release its process"); }
                    }
                    if (captured == null) {
                        captured = team.station.key() == null ? stations.capture(team.id, tier) : stations.restore(team.id, tier, team.station.sites());
                    }
                    if (captured == null) { continue; }
                    before = team.station.snapshot();
                    activeTeam = team;
                    for (var machine : captured.machines()) {
                        var handle = Objects.requireNonNull(machine.lifecycle()).beginScheduled();
                        if (handle == null) { break; }
                        handles.add(handle);
                    }
                    if (handles.size() != captured.machines().size()) {
                        if (!finishTeam()) { return false; }
                        continue;
                    }
                    var workSources = captured;
                    handles.getFirst().run(() -> team.station.advance(workSources,
                        new ResearchStationWork.Context(time, team.knownMembers, this::forward), tasks, forwardDeadline));
                    if (team.station.pending()) { return false; }
                    if (!finishTeam()) { return false; }
                }
                return finishTick();
            } catch (RuntimeException | Error failure) {
                stopped = true;
                if (activeTeam != null && activeTeam.station.pending()) {
                    if (stationFailure == null) { stationFailure = failure; }
                    activeTeam.station.requestStop(Reason.WORKER_FAILURE);
                } else if (pendingSave == null && (activeTeam != null || stationOperation)) {
                    // No unresolved source transaction remains. Keep the original world/process as a
                    // fatal diagnostic state, but do not pretend it still owns running source work.
                    try {
                        if (releaseHandles()) {
                            fatalFailure = failure;
                            activeTeam = null; before = null;
                            finished = true; activeTick = null;
                        }
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (failure != cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                    }
                }
                throw failure;
            } finally { executing = false; }
        }

        private void initialize() {
            order = new ObjectArrayList<>();
            var memberships = validateMemberships(captures);
            if (memberships == null || !forward()) { stopped = true; return; }
            if (!captures.keySet().containsAll(trackedPlayers())) { throw new IllegalArgumentException("Research capture omitted tracked players"); }
            serverTime = time;
            for (var entry : memberships.entrySet()) { captureTeam(entry.getKey(), entry.getValue()); }
            for (var team : teams.values()) {
                var members = memberships.getOrDefault(team.id, ObjectSets.emptySet());
                if (!members.equals(team.knownMembers)) { team.knownMembers = members; dirty = true; }
                order.add(team);
            }
        }

        private boolean forward() {
            return !stopped && !closing && !closed && !forwardDeadline.expired(System.nanoTime()) && current.getAsBoolean();
        }

        private void cleanup(Runnable action) {
            if (handles.isEmpty()) { action.run(); }
            else { handles.getFirst().cleanup(action); }
        }

        private boolean finishTeam() {
            var team = Objects.requireNonNull(activeTeam);
            if (team.station.pending()) { return false; }
            if (!releaseHandles()) { return false; }
            if (team.station.result().status() == ResearchStationWork.Status.FAILED) {
                if (stationFailure != null || !team.station.cancel(PlannedDeadline.after(Duration.ofSeconds(5)))) {
                    throw new IllegalStateException("Research station material process faulted", stationFailure);
                }
                dirty = true; // A paid cycle can begin and fail within this tick, leaving no saved segment.
            }
            if (!Objects.requireNonNull(before).equals(team.station.snapshot())) { dirty = true; }
            activeTeam = null; before = null; stationFailure = null; stationOperation = false;
            return true;
        }

        private boolean releaseHandles() {
            for (int index = handles.size() - 1; index >= 0; index--) {
                var handle = handles.get(index);
                if (!handle.finished() && !handle.tryFinish()) { return false; }
            }
            handles.clear();
            return true;
        }

        private boolean finishTick() {
            if (activeTeam != null) { return false; }
            if (dirty || checkpointRequested) { persist(); checkpointRequested = false; }
            finished = true;
            activeTick = null;
            return true;
        }

        @Override public boolean finished() { requireOwner(); return finished; }

        @Override public void cancel(Reason reason) {
            requireOwner(); Objects.requireNonNull(reason);
            stopped = true;
            if (activeTeam != null) { activeTeam.station.requestStop(reason); }
        }

        @Override public void awaitExit() {
            requireOwner();
            if (activeTeam != null) { activeTeam.station.awaitExit(); }
        }
    }
}
