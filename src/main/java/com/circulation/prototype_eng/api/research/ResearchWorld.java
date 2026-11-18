package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.delivery.DownloadOutputKey;
import com.circulation.prototype_eng.api.research.delivery.ItemDelivery;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryAdapter;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImportResult;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskRecovery;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskStorage;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadInventory;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadReceipt;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadResult;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadSources;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadEvaluation;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportEvaluation;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.station.ResearchStationWork;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Authoritative world owner for research sources, queues, manual and station execution. Membership is captured
 * by the platform, not inferred from network machines. Save failure retains the exact complete world
 * snapshot; further mutations first retry it. No research content or gameplay values are registered.
 */
@SuppressWarnings("unused") // Server hosts and integrations use this public owner API.
public final class ResearchWorld {
    private final Thread owner = Thread.currentThread();
    private final ResearchRegistry registry;
    private final ResearchPermissionPolicy permissions;
    private final TeamResearchStart admission;
    private final ResearchEligibility eligibility;
    private final Consumer<ResearchWorldSnapshot> writer;
    private final Object2ObjectLinkedOpenHashMap<String, TeamProgress> teams = new Object2ObjectLinkedOpenHashMap<>();
    private final ResearchDownloadSources downloadSources;
    private final Object2ObjectOpenHashMap<UUID, Object2IntOpenHashMap<ResearchDataCenters.Target>> downloadInventoryTargets = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<UUID, ResearchDownloadOperation> downloads = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<UUID, ResearchDownloadReceipt> downloadReceipts = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<UUID, ResearchDiskOperation> diskImports = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<UUID, ResearchDiskOperation> activeDiskImports = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectOpenHashMap<UUID, Object2ObjectOpenHashMap<UUID, UUID>> diskImportNonces = new Object2ObjectOpenHashMap<>();
    @Nullable
    private List<TeamProgress> capturedTeams;
    private Object2ObjectLinkedOpenHashMap<UUID, PlayerResearchState> players = new Object2ObjectLinkedOpenHashMap<>();
    @Nullable
    private ResearchWorldSnapshot pendingSave;
    private long serverTime;
    private boolean executing;
    private boolean dirty;
    private boolean closing;
    private boolean closed;
    private boolean checkpointRequested;
    @Nullable
    private StationTick activeTick;
    @Nullable
    private Throwable fatalFailure;
    @Nullable
    private PlayerResearchState.RewardSession activeReward;
    @Nullable
    private ResearchDownloadSources.Prepared activeDownload;
    @Nullable
    private PlayerResearchState.Completion activePlayerLevel;
    @Nullable
    private TeamOwnedResearch.Completion activeTeamLevel;
    @Nullable
    private Object2ObjectOpenHashMap<UUID, PlayerResearchState.Completion> activeMemberLevels;

    /**
     * The writer must synchronously save the supplied read-only checkpoint without modifying research sources
     * or reentering this world. A thrown failure retains the checkpoint for the next save attempt.
     */
    public ResearchWorld(ResearchRegistry registry,
                         ResearchPermissionPolicy permissions, Consumer<ResearchWorldSnapshot> writer) {
        this.registry = registry;
        this.permissions = permissions;
        this.writer = writer;
        admission = new TeamResearchStart(registry, permissions);
        eligibility = new ResearchEligibility(registry);
        downloadSources = new ResearchDownloadSources(registry);
    }

    private static void releaseReceipt(ResearchDownloadReceipt receipt, ResearchDownloadInventory inventory,
                                       ItemDeliveryAdapter adapter, BooleanSupplier current) {
        if (receipt.intent().machineInventory() && inventory.find(receipt.operationId()) == null) return;
        var elements13 = receipt.fragments();
        for (int index13 = 0; index13 < elements13.size(); index13++) {
            var fragment = elements13.get(index13);
            if (!current.getAsBoolean()) return;
            adapter.release(fragment);
        }
        if (receipt.intent().machineInventory() && receipt.outcome() == ResearchDownloadReceipt.Outcome.COMPLETED
            && current.getAsBoolean()) inventory.removeCommitted(receipt.operationId());
    }

    private static boolean sameNonce(ResearchDownloadOperation.Intent first, ResearchDownloadOperation.Intent second) {
        return first.playerId().equals(second.playerId()) && first.nonce().equals(second.nonce());
    }

    private static ResearchDownloadResult downloadResult(ResearchDownloadResult.Status status, @Nullable UUID id) {
        return new ResearchDownloadResult(status, id, null);
    }

    private static ResearchRewardClaimer.Result rewardResult(ResearchRewardClaimer.Status status) {
        return new ResearchRewardClaimer.Result(status, status.name(), null);
    }

    @Nullable
    private static ManualResearchInventory inventoryFor(ManualResearchSession session, Function<UUID, ManualResearchInventory> inventories) {
        UUID player = session.materialIntent() == null ? session.contributor() : session.materialIntent().contributor();
        return player == null ? null : inventories.apply(player);
    }

    @Nullable
    private static Map<String, ObjectSet<UUID>> validateMemberships(Map<UUID, OwnerTeamResolution> captures) {
        var memberships = new Object2ObjectOpenHashMap<String, ObjectSet<UUID>>(captures.size());
        for (var resolution : captures.values()) {
            if (resolution.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE) return null;
            if (resolution.accepted()) memberships.putIfAbsent(resolution.teamId(), resolution.members());
        }
        return memberships;
    }

    private static TeamResearchStart.Result staleStart() {
        return new TeamResearchStart.Result(TeamResearchStart.Status.STALE_CONTEXT, null, null);
    }

    private static boolean queued(TeamResearchQueue queue, TeamResearchQueue.Key key) {
        var elements12 = queue.queueSnapshot();
        for (int index12 = 0; index12 < elements12.size(); index12++) {
            var entry = elements12.get(index12);
            if (entry.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static ResearchDiskImportResult diskTerminalResult(ResearchDiskOperation operation) {
        return diskResult(operation.status() == ResearchDiskOperation.Status.ABORTED
            ? ResearchDiskImportResult.Status.ABORTED : ResearchDiskImportResult.Status.COMPLETED, operation.id());
    }

    private static ResearchDiskImportResult diskResult(ResearchDiskImportResult.Status status, @Nullable UUID id) {
        return new ResearchDiskImportResult(status, id, null);
    }

    private List<TeamProgress> teamSnapshot() {
        if (capturedTeams == null) capturedTeams = new ObjectArrayList<>(teams.values());
        return capturedTeams;
    }

    public long serverTime() {
        requireOwner();
        return serverTime;
    }

    public boolean savePending() {
        requireOwner();
        return pendingSave != null;
    }

    public boolean busy() {
        requireOwner();
        return activeTick != null || executing;
    }

    public boolean faulted() {
        requireOwner();
        return fatalFailure != null;
    }

    /**
     * Owner-bound read model for an open research overview. Returned values are borrowed read-only.
     */
    public List<ResearchProgress.Snapshot> retainedProgress(String teamId) {
        requireOwner();
        var team = teams.get(teamId);
        return team == null ? List.of() : team.queue.snapshot().progress();
    }

    public int otherMemberLevel(Set<UUID> members, UUID actor, ResourceLocation id) {
        requireOwner();
        int level = 0;
        for (var member : members) {
            if (member.equals(actor)) continue;
            var source = players.get(member);
            if (source != null) level = Math.max(level, source.completedLevel(id));
        }
        return level;
    }

    public boolean canForget(UUID actor, ResourceLocation id) {
        requireOwner();
        var source = players.get(actor);
        return source != null && source.canWriteResearch(id);
    }

    /**
     * Explicit administrator completion uses the ordinary propagation path and creates reward eligibility.
     */
    public ResearchLevelResult completeForEditor(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                                 ResourceLocation id, int targetLevel, long time,
                                                 Set<UUID> online, Function<UUID, ManualResearchInventory> inventories,
                                                 BooleanSupplier current) {
        return operate(time, false, () -> {
            if (!permission.serverOperator() || permissions.can(ResearchPermissionPolicy.Action.MODIFY_ANY_RESEARCH_LEVEL, permission)
                != ResearchPermissionPolicy.Decision.ALLOWED) return ResearchLevelResult.PERMISSION_DENIED;
            if (!membership.accepted() || !membership.teamId().equals(permission.targetTeamId()) || !current.getAsBoolean())
                return ResearchLevelResult.STALE_CONTEXT;
            var definition = registry.require(id);
            if (definition.specialOnly() || targetLevel < 1 || targetLevel > definition.maxLevel())
                return ResearchLevelResult.OUT_OF_RANGE;
            var team = captureTeam(membership.teamId(), membership.members());
            if (levelWritersBusy()) return ResearchLevelResult.BUSY;
            var targets = new Object2IntOpenHashMap<ResourceLocation>();
            var pending = new ObjectArrayList<ResourceLocation>();
            targets.put(id, targetLevel);
            pending.add(id);
            for (int cursor = 0; cursor < pending.size(); cursor++) {
                var researchId = pending.get(cursor);
                var research = registry.require(researchId);
                int required = targets.getInt(researchId);
                for (int stageIndex = 0; stageIndex < research.stages().size(); stageIndex++) {
                    var stage = research.stages().get(stageIndex);
                    if (stage.firstLevel() > required) break;
                    for (var entry : stage.prerequisites().object2IntEntrySet()) {
                        int previous = targets.getInt(entry.getKey());
                        if (previous < entry.getIntValue()) {
                            targets.put(entry.getKey(), entry.getIntValue());
                            pending.add(entry.getKey());
                        }
                    }
                }
            }
            for (var entry : targets.object2IntEntrySet()) {
                var researchId = entry.getKey();
                var research = registry.require(researchId);
                if (research.specialOnly()) return ResearchLevelResult.OUT_OF_RANGE;
                if (team.research.completedLevel(researchId) > 0 && !team.research.canWriteResearch(researchId))
                    return ResearchLevelResult.SOURCE_RESERVED;
                for (var member : membership.members())
                    if (!players.get(member).canWriteResearch(researchId)) return ResearchLevelResult.SOURCE_RESERVED;
            }
            if (!current.getAsBoolean()) return ResearchLevelResult.STALE_CONTEXT;
            var completion = new TeamResearchCompletion(registry);
            for (var entry : targets.object2IntEntrySet()) {
                var prepared = completion.prepare(entry.getKey(), entry.getIntValue(), membership.members(), players, team.research, time);
                if (prepared == null) return ResearchLevelResult.BUSY;
                try {
                    prepared.commit();
                    prepared.complete();
                } catch (RuntimeException | Error failure) {
                    if (prepared.resolution() == ReversibleTransaction.Resolution.REVERSIBLE) prepared.rollback();
                    throw failure;
                }
            }
            var rows = team.queue.queueSnapshot();
            for (int index = rows.size() - 1; index >= 0; index--) {
                var row = rows.get(index);
                if (targets.getInt(row.key().researchId()) < row.key().targetLevel()) continue;
                var manual = team.manual.session(row.key().queueEntryId());
                if (manual == null) team.queue.remove(row.key(), time);
                else team.manual.cancelInvalidated(row.key().queueEntryId(),
                    new ManualResearch.Context(time, membership.members(), online, current), inventoryFor(manual, inventories));
            }
            dirty = true;
            persist();
            return ResearchLevelResult.APPLIED;
        });
    }

    private static void commitEditorChange(ReversibleTransaction prepared) {
        try {
            prepared.commit();
            prepared.complete();
        } catch (RuntimeException | Error failure) {
            if (prepared.resolution() == ReversibleTransaction.Resolution.REVERSIBLE) prepared.rollback();
            throw failure;
        }
    }

    public ResearchLevelResult setAllForEditor(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                               boolean complete, long time, Set<UUID> online,
                                               Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current) {
        return operate(time, false, () -> {
            if (!permission.serverOperator() || permissions.can(ResearchPermissionPolicy.Action.MODIFY_ANY_RESEARCH_LEVEL, permission)
                != ResearchPermissionPolicy.Decision.ALLOWED) return ResearchLevelResult.PERMISSION_DENIED;
            if (!membership.accepted() || !membership.teamId().equals(permission.targetTeamId()) || !current.getAsBoolean())
                return ResearchLevelResult.STALE_CONTEXT;
            var team = captureTeam(membership.teamId(), membership.members());
            if (levelWritersBusy()) return ResearchLevelResult.BUSY;
            var definitions = registry.sortedById();
            for (var definition : definitions) {
                if (definition.specialOnly()) continue;
                var id = definition.id();
                if (!team.research.canWriteResearch(id)) return ResearchLevelResult.SOURCE_RESERVED;
                if (pendingLevelCompletion(id, null, membership.teamId())) return ResearchLevelResult.BUSY;
                for (var member : membership.members()) {
                    if (!players.get(member).canWriteResearch(id)) return ResearchLevelResult.SOURCE_RESERVED;
                    if (pendingLevelCompletion(id, member, null)) return ResearchLevelResult.BUSY;
                }
            }
            if (!current.getAsBoolean()) return ResearchLevelResult.STALE_CONTEXT;
            dirty = true;
            var completion = new TeamResearchCompletion(registry);
            for (var definition : definitions) {
                if (definition.specialOnly()) continue;
                var id = definition.id();
                if (complete) {
                    var prepared = completion.prepare(id, definition.maxLevel(), membership.members(), players, team.research, time);
                    if (prepared == null)
                        throw new IllegalStateException("Prechecked editor completion lost its source: " + id);
                    commitEditorChange(prepared);
                } else {
                    for (var member : membership.members()) {
                        var source = players.get(member);
                        if (source.completedLevel(id) == 0) continue;
                        var prepared = source.tryPrepareForgetResearch(id, 1);
                        if (prepared == null)
                            throw new IllegalStateException("Prechecked editor forget lost its source: " + id);
                        commitEditorChange(prepared);
                    }
                    int legacy = team.research.completedLevel(id);
                    if (legacy > 0) {
                        var prepared = team.research.tryPrepareSourceMutation(id, legacy, 0, null);
                        if (prepared == null)
                            throw new IllegalStateException("Prechecked editor forget lost its team source: " + id);
                        commitEditorChange(prepared);
                    }
                }
            }
            var rows = team.queue.queueSnapshot();
            for (int index = rows.size() - 1; index >= 0; index--) {
                var row = rows.get(index);
                var manual = team.manual.session(row.key().queueEntryId());
                if (manual == null) team.queue.remove(row.key(), time);
                else team.manual.cancelInvalidated(row.key().queueEntryId(),
                    new ManualResearch.Context(time, membership.members(), online, current), inventoryFor(manual, inventories));
            }
            persist();
            return ResearchLevelResult.APPLIED;
        });
    }

    public ManualResearch.Status pauseManual(OwnerTeamResolution membership, UUID actor, long entryId,
                                             long time, Set<UUID> online, BooleanSupplier current) {
        return operate(time, false, () -> {
            if (!membership.accepted() || !membership.members().contains(actor) || !current.getAsBoolean())
                return ManualResearch.Status.STALE_CONTEXT;
            var team = teams.get(membership.teamId());
            var session = team == null ? null : team.manual.session(entryId);
            if (session == null || !actor.equals(session.contributor()))
                return ManualResearch.Status.CONTRIBUTOR_UNAVAILABLE;
            return team.manual.pause(entryId, new ManualResearch.Context(time, membership.members(), online, current));
        });
    }

    public @Nullable ResearchStationView stationView(ResearchPermissionContext permission, ResearchStations stations, long time) {
        return stationView(permission, stations, time, _ -> null);
    }

    public @Nullable ResearchStationView stationView(ResearchPermissionContext permission, ResearchStations stations, long time,
                                                     Function<UUID, ManualResearchInventory> inventories) {
        requireOwner();
        requireHealthy();
        if (busy()) return null;
        var id = permission.targetTeamId();
        boolean visible = permissions.can(ResearchPermissionPolicy.Action.REQUEST_QUEUE_STATE, permission) == ResearchPermissionPolicy.Decision.ALLOWED;
        boolean canManage = visible && !savePending()
            && permissions.can(ResearchPermissionPolicy.Action.MANAGE_RESEARCH_QUEUE, permission) == ResearchPermissionPolicy.Decision.ALLOWED;
        var team = id == null ? null : teams.get(id);
        var work = !visible || team == null ? null : team.queue.activeProgress();
        if (visible && team != null) {
            var elements2 = team.manual.sessions();
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var session = elements2.get(index2);
                if (session.materialIntent() != null || session.completionIntent() != null) {
                    canManage = false;
                    break;
                }
            }
        }
        var current = work == null ? null : work.snapshot();
        var result = team == null ? null : team.station.result();
        boolean advanced = work != null && result != null && result.time() == time && result.key() != null
            && result.key().target().equals(work.target());
        var available = ImmutableResourceAmounts.empty();
        if (work != null) {
            if (work.stage().researchTier() == 0) {
                var head = team.queue.activeHead();
                var manual = head == null ? null : team.manual.session(head.key().queueEntryId());
                var inventory = manual == null ? null : inventoryFor(manual, inventories);
                if (inventory != null) {
                    var contributor = manual.materialIntent() == null ? manual.contributor() : manual.materialIntent().contributor();
                    if (!inventory.playerId().equals(contributor))
                        throw new IllegalStateException("Research material view captured a different contributor");
                    available = inventory.availableMaterials(current.remainingMaterials());
                }
            } else
                available = stations.availableMaterials(id, work.stage().researchTier(), current.remainingMaterials());
        }
        return new ResearchStationView(id == null ? "" : id, visible, canManage,
            visible && team != null ? team.queue.queueSnapshot() : List.of(), current,
            work == null ? 0 : work.stage().durationTicks(), available,
            work == null ? Reference2DoubleMaps.emptyMap() : work.totals(),
            advanced ? result.grants() : Reference2DoubleMaps.emptyMap(), advanced ? result.capacity() : Reference2DoubleMaps.emptyMap(),
            advanced ? result.stations().size() : 0);
    }

    public boolean manageQueue(OwnerTeamResolution membership, ResearchPermissionContext permission, long entryId,
                               ResearchStationView.Action action, long time, Set<UUID> online,
                               Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current) {
        return operate(time, false, () -> {
            if (!membership.accepted() || !membership.teamId().equals(permission.targetTeamId()) || !current.getAsBoolean()
                || permissions.can(ResearchPermissionPolicy.Action.MANAGE_RESEARCH_QUEUE, permission) != ResearchPermissionPolicy.Decision.ALLOWED)
                return false;
            var team = teams.get(membership.teamId());
            if (team == null || team.queue.busy()) return false;
            var rows = team.queue.queueSnapshot();
            int index = -1;
            for (int cursor = 0; cursor < rows.size(); cursor++)
                if (rows.get(cursor).key().queueEntryId() == entryId) {
                    index = cursor;
                    break;
                }
            if (index < 0) return false;

            var elements = team.manual.sessions();
            for (int index1 = 0; index1 < elements.size(); index1++) {
                var session = elements.get(index1);
                if (session.materialIntent() != null || session.completionIntent() != null) return false;
            }

            var key = rows.get(index).key();
            if (action == ResearchStationView.Action.REMOVE) {
                var session = team.manual.session(entryId);
                if (session != null) {
                    var context = new ManualResearch.Context(time, membership.members(), online, current);
                    return team.manual.cancelInvalidated(entryId, context, inventoryFor(session, inventories)) == ManualResearch.Status.CANCELLED;
                }
                team.queue.remove(key, time);
            } else {
                int destination = switch (action) {
                    case UP -> index - 1;
                    case DOWN -> index + 1;
                    case CURRENT -> 0;
                    case REMOVE -> throw new IllegalStateException();
                };
                if (!team.queue.move(key, destination, time)) return false;
            }
            dirty = true;
            persist();
            return true;
        });
    }

    /**
     * Read-only snapshot; changing an authoritative source still requires this owner's checkpoint.
     */
    public ResearchWorldSnapshot snapshot() {
        requireOwner();
        requireHealthy();
        if (activeTick != null) {
            throw new IllegalStateException("Research tick still owns the world");
        }
        return snapshotState();
    }

    /**
     * Display capture for the platform's scheduler MAIN action, using its current actor and membership.
     * Reads never create sources, flush a save or retry a material receipt. Admission conditions run
     * only on an actual start request; structural targets are deliberately weaker display facts.
     */
    @Nullable
    public ResearchPlayerView project(UUID actor, OwnerTeamResolution membership,
                                      ResearchPermissionContext permission, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
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
            if (team != null && (team.queue.busy() || team.research.busy() || team.manual.checkpointPending()))
                return null;
            var memberSources = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>((membership.accepted() ? membership.members().size() : 0));
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
            var personalDecision = permissions.can(ResearchPermissionPolicy.Action.VIEW_OWN_RESEARCH, permission);
            var viewDecision = ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM;
            var queueDecision = viewDecision;
            var startDecision = viewDecision;
            var blacklistDecision = viewDecision;
            if (membership.accepted()) {
                viewDecision = permissions.can(ResearchPermissionPolicy.Action.VIEW_TEAM_RESEARCH, permission);
                queueDecision = permissions.can(ResearchPermissionPolicy.Action.REQUEST_QUEUE_STATE, permission);
                startDecision = permissions.can(ResearchPermissionPolicy.Action.START_RESEARCH, permission);
                blacklistDecision = permissions.can(ResearchPermissionPolicy.Action.BLACKLIST_TEAM_RESEARCH, permission);
            }
            var claims = new ObjectArrayList<ResearchPlayerView.ClaimSummary>();
            var personalLevels = Object2IntMaps.<ResourceLocation>emptyMap();
            if (personalDecision == ResearchPermissionPolicy.Decision.ALLOWED && ownSnapshot != null) {
                personalLevels = ownSnapshot.completedLevels();
                var elements24 = ownSnapshot.rewardClaims();
                for (int index24 = 0; index24 < elements24.size(); index24++) {
                    var claim = elements24.get(index24);
                    var choices = new ObjectArrayList<ResourceLocation>();
                    var elements15 = registry.require(claim.key().researchId()).rewardGroupsForLevel(claim.key().level());
                    for (int index15 = 0; index15 < elements15.size(); index15++) {
                        var group = elements15.get(index15);
                        choices.add(group.id());
                    }
                    claims.add(new ResearchPlayerView.ClaimSummary(claim.key().researchId(), claim.key().level(),
                        claim.status(), claim.selectedGroupId(), choices));
                }
            }
            var sharedLevels = Object2IntMaps.<ResourceLocation>emptyMap();
            var structuralTargets = new Object2IntOpenHashMap<ResourceLocation>();
            var blacklist = ObjectSets.<ResourceLocation>emptySet();
            if (viewDecision == ResearchPermissionPolicy.Decision.ALLOWED) {
                blacklist = team == null ? ObjectSets.emptySet() : team.blacklistSnapshot();
                var structural = eligibility.calculateStructural(teamId, membership.members(), memberSources,
                    teamSnapshot == null ? Object2IntMaps.emptyMap() : teamSnapshot.levels(), blacklist);
                sharedLevels = structural.sharedLevels();
                var elements23 = registry.all();
                for (int index23 = 0; index23 < elements23.size(); index23++) {
                    var definition = elements23.get(index23);
                    int completed = structural.completedLevel(definition.id());
                    if (completed < definition.maxLevel() && eligibility.structurallyEligible(structural, definition.id(), completed + 1)) {
                        structuralTargets.put(definition.id(), completed + 1);
                    }
                }
            }
            var queueRows = new ObjectArrayList<ResearchPlayerView.QueueRow>();
            Reference2DoubleMap<NetworkResourceType> numericProgress = Reference2DoubleMaps.emptyMap();
            var manualRows = new ObjectArrayList<ResearchPlayerView.ManualRow>();
            long queueEpoch = 0;
            if (queueDecision == ResearchPermissionPolicy.Decision.ALLOWED && queueSnapshot != null) {
                queueEpoch = queueSnapshot.epoch();
                var elements20 = queueSnapshot.entries();
                for (int index20 = 0; index20 < elements20.size(); index20++) {
                    var entry = elements20.get(index20);
                    queueRows.add(new ResearchPlayerView.QueueRow(entry.key().queueEntryId(),
                        entry.key().researchId(), entry.key().targetLevel(), entry.requestedBy(), entry.createdAtWorldTime()));
                }
                numericProgress = queueSnapshot.resourceProgress();
                for (int index16 = 0; index16 < manualSnapshots.size(); index16++) {
                    var manual = manualSnapshots.get(index16);
                    manualRows.add(new ResearchPlayerView.ManualRow(manual.queueKey().queueEntryId(),
                        manual.status(), manual.pauseReason(), manual.contributor(), manual.work()));
                }
            }
            var status = !membership.accepted()
                ? membership.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE
                ? ResearchPlayerView.TeamStatus.UNAVAILABLE : ResearchPlayerView.TeamStatus.MISSING_TEAM
                : viewDecision == ResearchPermissionPolicy.Decision.ALLOWED || queueDecision == ResearchPermissionPolicy.Decision.ALLOWED
                ? ResearchPlayerView.TeamStatus.AVAILABLE : ResearchPlayerView.TeamStatus.DENIED;
            var projectedTeam = new ResearchPlayerView.Team(status, teamId == null ? "" : teamId, viewDecision, queueDecision,
                startDecision, blacklistDecision, sharedLevels, structuralTargets, blacklist, queueEpoch, queueRows, numericProgress, manualRows);
            var result = new ResearchPlayerView(actor, serverTime, pendingSave != null, personalDecision, personalLevels, projectedTeam, claims,
                permissions.can(ResearchPermissionPolicy.Action.FORGET_PERSONAL_RESEARCH, permission),
                permissions.can(ResearchPermissionPolicy.Action.MODIFY_ANY_RESEARCH_LEVEL, permission));
            if (!current.getAsBoolean() || !sourcesCurrent(sourceSnapshots)) return null;
            if (team != null && (team.queue.busy() || team.research.busy() || team.manual.checkpointPending()
                || !team.research.snapshot().equals(teamSnapshot) || !team.queue.snapshot().equals(queueSnapshot)
                || !team.manual.sessions().equals(manualSnapshots))) return null;
            return result;
        } finally {
            executing = false;
        }
    }

    /**
     * Capture an operation's cloud authority independently of GUI view permissions. The caller's
     * proof must retain the captured membership and permission through the eventual backend write.
     */
    @Nullable
    public CloudCapture captureCloud(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                     ResearchPermissionPolicy.Action action, BooleanSupplier current) {
        requireOwner();
        if (!membership.accepted()) return null;
        if (!membership.ownerId().equals(permission.actorId()) || !membership.teamId().equals(permission.actorTeamId())
            || !membership.teamId().equals(permission.targetTeamId())) {
            throw new IllegalArgumentException("Research cloud must target the actor's captured team");
        }
        return captureCloudCurrent(membership, permission, action, current);
    }

    /**
     * System synchronization has no player requester and does not invoke a player permission policy.
     */
    @Nullable
    public CloudCapture captureCloud(OwnerTeamResolution membership, BooleanSupplier current) {
        requireOwner();
        return membership.accepted() ? captureCloudCurrent(membership, null, null, current) : null;
    }

    private @Nullable CloudCapture captureCloudCurrent(OwnerTeamResolution membership, @Nullable ResearchPermissionContext permission,
                                                       @Nullable ResearchPermissionPolicy.Action action, BooleanSupplier current) {
        if (!cloudAvailable()) return null;
        executing = true;
        try {
            if (!current.getAsBoolean()) return null;
            var sources = new ObjectArrayList<CloudSource>(membership.members().size());
            var members = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>(membership.members().size());
            for (var member : membership.members()) {
                var source = players.get(member);
                if (source != null && source.busy()) return null;
                sources.add(new CloudSource(member, source, source == null ? null : source.snapshot()));
                members.put(member, source == null ? new PlayerResearchState(member) : source);
            }
            var team = teams.get(membership.teamId());
            if (team != null && (team.queue.busy() || team.research.busy() || team.manual.checkpointPending()))
                return null;
            var teamSnapshot = team == null ? null : team.research.snapshot();
            var knownMembers = team == null ? ObjectSets.<UUID>emptySet() : team.knownMembers;
            var blacklist = team == null ? ObjectSets.<ResourceLocation>emptySet() : team.blacklistSnapshot();
            var decision = permission == null ? ResearchPermissionPolicy.Decision.ALLOWED
                : permissions.can(action, permission);
            var view = decision == ResearchPermissionPolicy.Decision.ALLOWED
                ? eligibility.calculateStructural(membership.teamId(), membership.members(), members,
                teamSnapshot == null ? Object2IntMaps.emptyMap() : teamSnapshot.levels(), blacklist) : null;
            BooleanSupplier sourceCurrent = () -> cloudSourcesCurrent(membership.teamId(), sources, team, teamSnapshot, knownMembers, blacklist);
            if (!current.getAsBoolean() || !sourceCurrent.getAsBoolean()) return null;
            if (decision != ResearchPermissionPolicy.Decision.ALLOWED)
                return new CloudCapture(decision, null, () -> false);
            return new CloudCapture(decision, view, () -> {
                requireOwner();
                if (!cloudAvailable()) return false;
                executing = true;
                try {
                    return current.getAsBoolean() && sourceCurrent.getAsBoolean();
                } finally {
                    executing = false;
                }
            });
        } finally {
            executing = false;
        }
    }

    private boolean cloudAvailable() {
        return !executing && activeTick == null && !closing && !closed && fatalFailure == null;
    }

    private boolean cloudSourcesCurrent(String teamId, ObjectList<CloudSource> sources, @Nullable TeamProgress team,
                                        @Nullable TeamOwnedResearch.Snapshot teamSnapshot, ObjectSet<UUID> knownMembers,
                                        ObjectSet<ResourceLocation> blacklist) {
        if (activeTick != null || closing || closed || fatalFailure != null || teams.get(teamId) != team) return false;
        for (int index19 = 0; index19 < sources.size(); index19++) {
            var captured = sources.get(index19);
            var source = players.get(captured.player());
            if (source != captured.source() || source != null && (source.busy() || source.snapshot() != captured.snapshot()))
                return false;
        }
        return team == null || !team.queue.busy() && !team.research.busy() && !team.manual.checkpointPending()
            && team.research.snapshot() == teamSnapshot && team.knownMembers.equals(knownMembers) && team.blacklist.equals(blacklist);
    }

    /**
     * Exposes an existing source to an authoritative integration; never implicitly creates one.
     */
    public PlayerResearchState player(UUID id) {
        return operate(serverTime, false, () -> {
            var player = players.get(id);
            if (player == null) {
                throw new IllegalArgumentException("Unknown research player " + id);
            }
            dirty = true;
            return player;
        });
    }

    /**
     * Restore all sources before any manual session; invalid input never publishes a partial world.
     */
    public void restore(ResearchWorldSnapshot saved) {
        operate(saved.serverTime(), false, () -> {
            if (!players.isEmpty() || !teams.isEmpty()) {
                throw new IllegalStateException("Research world has already been initialized");
            }
            var restoredPlayers = new Object2ObjectLinkedOpenHashMap<UUID, PlayerResearchState>(saved.players().size());
            var elements17 = saved.players();
            for (int index17 = 0; index17 < elements17.size(); index17++) {
                var snapshot = elements17.get(index17);
                var state = new PlayerResearchState(snapshot.playerUuid());
                state.restore(snapshot);
                restoredPlayers.put(snapshot.playerUuid(), state);
            }
            var restoredTeams = new Object2ObjectLinkedOpenHashMap<String, TeamProgress>(saved.teams().size());
            var elements25 = saved.teams();
            for (int index25 = 0; index25 < elements25.size(); index25++) {
                var snapshot = elements25.get(index25);
                var team = new TeamProgress(snapshot.teamId(), TeamResearchQueue.restore(registry, snapshot.queue()), restoredPlayers);
                team.research.restore(snapshot.research());
                team.knownMembers = snapshot.knownMembers();
                team.blacklist.addAll(snapshot.blacklist());
                team.capturedBlacklist = null;
                team.manual.restore(snapshot.manualSessions());
                restoredTeams.put(snapshot.teamId(), team);
            }
            var elements26 = saved.downloads();
            for (int index26 = 0; index26 < elements26.size(); index26++) {
                var operation = elements26.get(index26);
                if (operation.status() == ResearchDownloadOperation.Status.PREPARED || operation.status() == ResearchDownloadOperation.Status.MATERIAL_COMMITTED) {
                    var reservation = downloadSources.reserve(operation.sources(), operation.id(), restoredPlayers, restoredTeams.get(operation.sources().teamId()).research);
                    if (reservation != ResearchDownloadSources.Status.READY)
                        throw new IllegalArgumentException("Saved download source reservation is inconsistent: " + reservation);
                }
            }
            players = restoredPlayers;
            teams.putAll(restoredTeams);
            capturedTeams = null;
            var elements7 = saved.downloads();
            for (int index7 = 0; index7 < elements7.size(); index7++) {
                var operation = elements7.get(index7);
                downloads.put(operation.id(), operation);
                indexDownloadTarget(operation.intent(), true);
            }
            var elements9 = saved.downloadReceipts();
            for (int index9 = 0; index9 < elements9.size(); index9++) {
                var receipt = elements9.get(index9);
                downloadReceipts.put(receipt.operationId(), receipt);
                indexDownloadTarget(receipt.intent(), false);
            }
            var elements8 = saved.diskImports();
            for (int index8 = 0; index8 < elements8.size(); index8++) {
                var operation = elements8.get(index8);
                addDiskImport(operation);
            }
            dirty = true; // ACTIVE sessions were intentionally changed to PAUSED during restore.
            return null;
        });
    }

    /**
     * Active saved teams, queued requesters and pending contributors must be resolved even offline.
     */
    public ObjectSet<UUID> trackedPlayers() {
        requireOwner();
        var tracked = new ObjectOpenHashSet<UUID>();
        for (var team : teams.values()) {
            if (team.queue.size() == 0 && team.manual.sessions().isEmpty()) {
                continue;
            }
            tracked.addAll(team.knownMembers);
            var elements11 = team.queue.queueSnapshot();
            for (int index11 = 0; index11 < elements11.size(); index11++) {
                var entry = elements11.get(index11);
                tracked.add(entry.requestedBy());
            }

            var elements5 = team.manual.sessions();
            for (int index5 = 0; index5 < elements5.size(); index5++) {
                var session = elements5.get(index5);
                if (session.contributor() != null) {
                    tracked.add(session.contributor());
                }
                if (session.materialIntent() != null) {
                    tracked.add(session.materialIntent().contributor());
                }
                if (session.completionIntent() != null) {
                    tracked.addAll(session.completionIntent().baselines().keySet());
                }
            }

        }
        for (var operation : downloads.values()) {
            tracked.add(operation.intent().playerId());
            tracked.addAll(operation.sources().members());
        }
        for (var operation : diskImports.values()) {
            if (operation.terminal()) continue;
            tracked.add(operation.intent().playerId());
            tracked.add(operation.target().ownerId());
        }
        return tracked;
    }

    /**
     * Frozen obligations include original offline sources; reads never advance receipts or grant membership.
     */
    public ObjectList<ResearchDownloadOperation> pendingDownloadOperations(UUID player) {
        requireOwner();
        var result = new ObjectArrayList<ResearchDownloadOperation>();
        for (var operation : downloads.values())
            if (operation.intent().playerId().equals(player)) result.add(operation);
        return result;
    }

    /**
     * Confirmed fragments remain available for later entity loading and idempotent tag cleanup.
     */
    public ObjectList<ResearchDownloadReceipt> downloadReceipts(UUID player) {
        requireOwner();
        var result = new ObjectArrayList<ResearchDownloadReceipt>();
        for (var receipt : downloadReceipts.values())
            if (receipt.intent().playerId().equals(player)) result.add(receipt);
        return result;
    }

    /**
     * Original inventories awaiting physical recovery. The server owner may remove an entry only
     * after pendingDownloadInventory is false and the physical inventory has no transfer evidence.
     * Permanent nonce receipts remain stored independently of this runtime index.
     */
    public Set<ResearchDataCenters.Target> downloadInventoryTargets(UUID player) {
        requireOwner();
        var targets = downloadInventoryTargets.get(player);
        return targets == null ? Set.of() : targets.keySet();
    }

    /**
     * The server owner may remove an actor after retiring all of that actor's physical targets.
     */
    public Set<UUID> downloadInventoryActors() {
        requireOwner();
        return downloadInventoryTargets.keySet();
    }

    public boolean pendingDownloadInventory(UUID player, ResearchDataCenters.Target target) {
        requireOwner();
        var targets = downloadInventoryTargets.get(player);
        return targets != null && targets.getInt(target) > 0;
    }

    private void indexDownloadTarget(ResearchDownloadOperation.Intent intent, boolean pending) {
        if (intent.machineInventory()) {
            var targets = downloadInventoryTargets.computeIfAbsent(intent.playerId(), _ -> new Object2IntOpenHashMap<>());
            if (pending) targets.addTo(intent.target(), 1);
            else targets.putIfAbsent(intent.target(), 0);
        }
    }

    /**
     * Executes one addon conversion only for a new nonce, freezes its full native output, then saves
     * every phase through this world's writer. Authorization is rechecked until material commit;
     * actorCurrent controls only the original player's current delivery capability thereafter.
     */
    public ResearchDownloadResult requestItemDownload(ResearchDownloadOperation.Intent intent, OwnerTeamResolution membership,
                                                      ResearchPermissionContext permission, @Nullable DataCenterResearchSnapshot database, ResearchDownloadInventory inventory,
                                                      Function<ResearchDownloadMaterial, ResearchItemDownloadEvaluation> evaluate,
                                                      @Nullable ResearchDownloadSources.Plan expectedSources, @Nullable UUID candidateOperationId,
                                                      BooleanSupplier candidateCurrent,
                                                      long time, ItemDeliveryAdapter delivery,
                                                      BooleanSupplier current, BooleanSupplier actorCurrent) {
        requireOwner();
        requireHealthy();
        if (executing || activeTick != null || closing || closed)
            return downloadResult(ResearchDownloadResult.Status.BUSY, null);
        if (!intent.playerId().equals(inventory.playerId()))
            throw new IllegalArgumentException("Download inventory belongs to another player");
        if (database != null && !intent.databaseId().equals(database.dataCenterId())) {
            throw new IllegalArgumentException("Download database differs from its frozen request identity");
        }
        if (intent.machineInventory() != inventory.machineInventory())
            throw new IllegalArgumentException("Download inventory differs from its saved target kind");
        return operate(time, false, () -> {
            for (var receipt : downloadReceipts.values()) {
                if (!sameNonce(receipt.intent(), intent)) continue;
                if (!receipt.intent().equals(intent))
                    return downloadResult(ResearchDownloadResult.Status.NONCE_CONFLICT, receipt.operationId());
                releaseReceipt(receipt, inventory, delivery, actorCurrent);
                return downloadResult(receipt.outcome() == ResearchDownloadReceipt.Outcome.COMPLETED
                    ? ResearchDownloadResult.Status.COMPLETED : ResearchDownloadResult.Status.ABORTED, receipt.operationId());
            }
            for (var operation : downloads.values()) {
                if (!sameNonce(operation.intent(), intent)) continue;
                if (!operation.intent().equals(intent))
                    return downloadResult(ResearchDownloadResult.Status.NONCE_CONFLICT, operation.id());
                return advanceDownload(operation.id(), inventory, delivery,
                    () -> current.getAsBoolean() && permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission)
                        == ResearchPermissionPolicy.Decision.ALLOWED, actorCurrent);
            }
            if (!candidateCurrent.getAsBoolean() || !current.getAsBoolean())
                return downloadResult(ResearchDownloadResult.Status.STALE_CONTEXT, null);
            if (!membership.accepted() || !intent.playerId().equals(membership.ownerId())
                || !intent.teamId().equals(membership.teamId()) || !intent.playerId().equals(permission.actorId())
                || !intent.teamId().equals(permission.actorTeamId()) || !intent.teamId().equals(permission.targetTeamId())) {
                return downloadResult(ResearchDownloadResult.Status.PERMISSION_DENIED, null);
            }
            if (permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission) != ResearchPermissionPolicy.Decision.ALLOWED) {
                return downloadResult(ResearchDownloadResult.Status.PERMISSION_DENIED, null);
            }
            if (!inventory.operations().isEmpty()) return downloadResult(ResearchDownloadResult.Status.BUSY, null);
            for (var pending : downloads.values())
                if (pending.intent().playerId().equals(intent.playerId()))
                    return downloadResult(ResearchDownloadResult.Status.BUSY, pending.id());
            for (var pending : activeDiskImports.values())
                if (pending.intent().playerId().equals(intent.playerId()))
                    return downloadResult(ResearchDownloadResult.Status.BUSY, pending.id());
            var team = captureTeam(intent.teamId(), membership.members());
            var definition = registry.require(intent.researchId());
            definition.stageForLevel(intent.level());
            if (!downloadRulesCurrent(team, definition, intent.level(), membership.members()))
                return downloadResult(ResearchDownloadResult.Status.SOURCE_REJECTED, null);
            var id = candidateOperationId == null ? UUID.randomUUID() : candidateOperationId;
            var material = inventory.capture(id, intent.slot());
            if (material == null) return downloadResult(ResearchDownloadResult.Status.MISSING_MATERIAL, null);
            if (!id.equals(material.operationId()) || !intent.playerId().equals(material.playerId()) || material.slot() != intent.slot()
                || material.status() != ResearchDownloadMaterial.Status.PREPARED)
                throw new IllegalArgumentException("Captured material changed download identity");
            var output = evaluate.apply(material);
            if (output.status() != ResearchItemDownloadEvaluation.Status.SUCCESS)
                return downloadResult(output.status() == ResearchItemDownloadEvaluation.Status.NO_EVENT
                    ? ResearchDownloadResult.Status.NO_EVENT : ResearchDownloadResult.Status.EVENT_REJECTED, null);
            var candidate = output.output();
            long amount = candidate.getCount();
            var nativeOutput = candidate.serializeNBT();
            nativeOutput.setByte("Count", (byte) 1);
            if (!delivery.canOutput(nativeOutput, amount))
                return downloadResult(ResearchDownloadResult.Status.OUTPUT_BLOCKED, null);
            var source = downloadSources.evaluate(definition, output.researchLevel(), output.levelCost(), membership, database, players, team.research);
            if (source.plan() == null)
                return new ResearchDownloadResult(ResearchDownloadResult.Status.SOURCE_REJECTED, null, source.status());
            if (expectedSources != null && !expectedSources.equals(source.plan()))
                return downloadResult(ResearchDownloadResult.Status.STALE_CONTEXT, null);
            if (!current.getAsBoolean()) return downloadResult(ResearchDownloadResult.Status.STALE_CONTEXT, null);
            if (!downloadRulesCurrent(team, definition, intent.level(), membership.members()) || !inventory.matches(material.inventoryBefore())) {
                return downloadResult(ResearchDownloadResult.Status.STALE_CONTEXT, null);
            }
            var reserved = downloadSources.reserve(source.plan(), id, players, team.research);
            if (reserved != ResearchDownloadSources.Status.READY)
                return new ResearchDownloadResult(ResearchDownloadResult.Status.SOURCE_REJECTED, null, reserved);
            downloads.put(id, new ResearchDownloadOperation(id, intent, source.plan(), material, nativeOutput, amount, time,
                ResearchDownloadOperation.Status.PREPARED, false, ObjectList.of()));
            indexDownloadTarget(intent, true);
            dirty = true;
            persist();
            return advanceDownload(id, inventory, delivery,
                () -> current.getAsBoolean() && permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission)
                    == ResearchPermissionPolicy.Decision.ALLOWED, actorCurrent);
        });
    }

    /**
     * Read-only payment projection. A missing team has no direct levels and is not created here.
     */
    public ResearchDownloadSources.Evaluation previewItemDownloadSources(ResearchDefinition definition, int level, int cost,
                                                                         OwnerTeamResolution membership,
                                                                         @Nullable DataCenterResearchSnapshot database,
                                                                         TeamResearchView cloud) {
        requireOwner();
        var team = teams.get(membership.teamId());
        var evaluated = downloadSources.evaluate(definition, level, cost, membership, database, players,
            team == null ? null : team.research);
        if (evaluated.plan() == null) return evaluated;
        return new ResearchDownloadSources.Evaluation(evaluated.status(), evaluated.plan(),
            effectiveDownloadLevel(definition, evaluated.teamLevelBefore(), cloud),
            effectiveDownloadLevel(definition, evaluated.teamLevelAfter(), cloud));
    }

    private static int effectiveDownloadLevel(ResearchDefinition definition, int raw, TeamResearchView cloud) {
        if (cloud.blacklistedResearchIds().contains(definition.id())) return 0;
        int candidate = raw;
        while (candidate > 0) {
            var stage = definition.stageForLevel(candidate);
            boolean allowed = true;
            for (var prerequisite : stage.prerequisites().object2IntEntrySet()) {
                if (cloud.completedLevel(prerequisite.getKey()) < prerequisite.getIntValue()) {
                    allowed = false;
                    break;
                }
            }
            if (allowed) return candidate;
            candidate = stage.firstLevel() - 1;
        }
        return 0;
    }

    /**
     * Retry original obligations. A false authorization preserves PREPARED without guessing revocation.
     */
    public ObjectList<ResearchDownloadResult> recoverItemDownloads(UUID player, @Nullable ResearchDataCenters.Target target,
                                                                   ResearchDownloadInventory inventory,
                                                                   ItemDeliveryAdapter delivery, long time, BooleanSupplier actorCurrent, Predicate<ResearchDownloadOperation> authorized) {
        requireOwner();
        requireHealthy();
        if (!player.equals(inventory.playerId()))
            throw new IllegalArgumentException("Download recovery inventory belongs to another player");
        if (executing || activeTick != null || closing || closed)
            return ObjectList.of(downloadResult(ResearchDownloadResult.Status.BUSY, null));
        return operate(time, false, () -> {
            var result = new ObjectArrayList<ResearchDownloadResult>();

            var elements3 = new ObjectArrayList<ResearchDownloadOperation>();
            for (var operation : downloads.values())
                if (operation.intent().playerId().equals(player) && sameDownloadInventory(operation.intent(), target))
                    elements3.add(operation);
            for (int index3 = 0; index3 < elements3.size(); index3++) {
                var operation = elements3.get(index3);
                result.add(advanceDownload(operation.id(), inventory, delivery,
                    () -> authorized.test(downloads.get(operation.id())), actorCurrent));
            }

            for (var receipt : downloadReceipts.values())
                if (receipt.intent().playerId().equals(player) && sameDownloadInventory(receipt.intent(), target))
                    releaseReceipt(receipt, inventory, delivery, actorCurrent);
            var elements22 = inventory.operations();
            for (int index22 = 0; index22 < elements22.size(); index22++) {
                var material = elements22.get(index22);
                if (!downloads.containsKey(material.operationId()) && !diskImports.containsKey(material.operationId()))
                    result.add(downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, material.operationId()));
            }
            return result;
        });
    }

    private static boolean sameDownloadInventory(ResearchDownloadOperation.Intent intent, @Nullable ResearchDataCenters.Target target) {
        return intent.machineInventory() ? target != null && target.equals(intent.target()) : target == null;
    }

    private ResearchDownloadResult advanceDownload(UUID id, ResearchDownloadInventory inventory, ItemDeliveryAdapter delivery,
                                                   BooleanSupplier authorized, BooleanSupplier actorCurrent) {
        var operation = downloads.get(id);
        var team = teams.get(operation.sources().teamId());
        if (operation.status() == ResearchDownloadOperation.Status.ABORTING) {
            var receipt = inventory.find(id);
            if (receipt != null && (!operation.material().sameOperation(receipt) || receipt.status() != ResearchDownloadMaterial.Status.PREPARED)
                || !inventory.matches(operation.material().inventoryBefore()))
                return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
            inventory.removePrepared(id);
            return finishDownload(operation, ResearchDownloadReceipt.aborted(operation));
        }
        if (operation.status() == ResearchDownloadOperation.Status.PREPARED) {
            var reserved = downloadSources.reserve(operation.sources(), id, players, team.research);
            if (reserved != ResearchDownloadSources.Status.READY)
                return new ResearchDownloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id, reserved);
            var receipt = inventory.find(id);
            if (receipt != null && !operation.material().sameOperation(receipt))
                return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
            if (receipt == null || receipt.status() == ResearchDownloadMaterial.Status.PREPARED) {
                if (!actorCurrent.getAsBoolean() || !authorized.getAsBoolean()
                    || !downloadRulesCurrent(team, registry.require(operation.intent().researchId()), operation.intent().level(), operation.sources().members())) {
                    return downloadResult(ResearchDownloadResult.Status.PENDING, id);
                }
                if (!inventory.matches(operation.material().inventoryBefore()))
                    return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
                if (!operation.materialArmed()) {
                    operation = operation.armed();
                    saveDownload(operation);
                }
                if (receipt == null) inventory.recordPrepared(operation.material());
                if (!actorCurrent.getAsBoolean() || !authorized.getAsBoolean())
                    return downloadResult(ResearchDownloadResult.Status.PENDING, id);
                if (!inventory.consume(operation.material()))
                    return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
                receipt = inventory.find(id);
            }
            if (!operation.materialArmed() || !operation.material().sameOperation(receipt) || receipt.status() != ResearchDownloadMaterial.Status.COMMITTED) {
                return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
            }
            inventory.persistCommitted(id);
            operation = operation.materialCommitted(receipt);
            saveDownload(operation);
        }
        if (operation.status() == ResearchDownloadOperation.Status.MATERIAL_COMMITTED) {
            var receipt = inventory.find(id);
            if (!operation.material().sameOperation(receipt) || receipt.status() != ResearchDownloadMaterial.Status.COMMITTED) {
                return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
            }
            inventory.persistCommitted(id);
            var reservation = downloadSources.reserve(operation.sources(), id, players, team.research);
            if (reservation != ResearchDownloadSources.Status.READY)
                return new ResearchDownloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id, reservation);
            var preparation = downloadSources.prepare(operation.sources(), id, players, team.research);
            if (preparation.prepared() == null)
                return new ResearchDownloadResult(ResearchDownloadResult.Status.PENDING, id, preparation.status());
            var held = preparation.prepared();
            activeDownload = held;
            boolean committed = false;
            try {
                held.commit();
                committed = true;
                operation = operation.sourcesCommitted();
                saveDownload(operation);
            } finally {
                if (committed) held.complete();
                activeDownload = null;
            }
            releaseDurableDownloads();
        }
        if (operation.status() == ResearchDownloadOperation.Status.SOURCES_COMMITTED) {
            // This phase is already durable. Source levels may subsequently rise or fall normally.
            if (!operation.intent().machineInventory()) inventory.removeCommitted(id);
            if (!actorCurrent.getAsBoolean()) return downloadResult(ResearchDownloadResult.Status.PENDING, id);
            operation = operation.plan(delivery.plan(new DownloadOutputKey(operation.intent().playerId(), id), operation.output(), operation.amount()));
            saveDownload(operation);
        }
        if (operation.status() == ResearchDownloadOperation.Status.DELIVERING) {
            if (!operation.intent().machineInventory()) inventory.removeCommitted(id);
            var status = ItemDelivery.advance(() -> downloads.get(id).fragments(),
                fragment -> saveDownload(downloads.get(id).replace(fragment)), delivery, actorCurrent);
            if (status == ItemDelivery.Status.COMPLETED) {
                var completed = finishDownload(downloads.get(id), ResearchDownloadReceipt.completed(downloads.get(id)));
                if (operation.intent().machineInventory()) inventory.removeCommitted(id);
                return completed;
            }
            return downloadResult(status == ItemDelivery.Status.RECOVERY_ERROR ? ResearchDownloadResult.Status.RECOVERY_ERROR : ResearchDownloadResult.Status.PENDING, id);
        }
        return downloadResult(ResearchDownloadResult.Status.PENDING, id);
    }

    /**
     * Explicit cancellation is available only while exact before evidence proves no material effect.
     */
    public ResearchDownloadResult abortItemDownload(UUID player, UUID id, ResearchDownloadInventory inventory, long time) {
        return operate(time, false, () -> {
            var operation = downloads.get(id);
            if (operation == null || !operation.intent().playerId().equals(player) || !player.equals(inventory.playerId())) {
                return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
            }
            var receipt = inventory.find(id);
            if (operation.status() != ResearchDownloadOperation.Status.PREPARED || receipt != null
                && (!operation.material().sameOperation(receipt) || receipt.status() != ResearchDownloadMaterial.Status.PREPARED)
                || !inventory.matches(operation.material().inventoryBefore()))
                return downloadResult(ResearchDownloadResult.Status.RECOVERY_ERROR, id);
            operation = operation.aborting();
            saveDownload(operation);
            inventory.removePrepared(id);
            return finishDownload(operation, ResearchDownloadReceipt.aborted(operation));
        });
    }

    private boolean downloadRulesCurrent(TeamProgress team, ResearchDefinition definition, int level, Collection<UUID> members) {
        if (team.blacklist.contains(definition.id()) || team.research.busy() || team.queue.busy() || membersBusy(members))
            return false;
        var sources = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
        for (var member : members) sources.put(member, players.get(member));
        var view = eligibility.calculateStructural(team.id, members, sources, team.research.completedLevelsSnapshot(), team.blacklist);
        var elements14 = definition.mutexRulesForLevel(level);
        for (int index14 = 0; index14 < elements14.size(); index14++) {
            var rule = elements14.get(index14);
            if (view.completedLevel(rule.researchId()) >= rule.minLevel()) return false;
        }
        return true;
    }

    private void saveDownload(ResearchDownloadOperation operation) {
        if (!executing || !downloads.containsKey(operation.id()))
            throw new IllegalStateException("Download checkpoint lost its operation owner");
        downloads.put(operation.id(), operation);
        dirty = true;
        persist();
    }

    private ResearchDownloadResult finishDownload(ResearchDownloadOperation operation, ResearchDownloadReceipt receipt) {
        downloads.remove(operation.id());
        downloadReceipts.put(operation.id(), receipt);
        if (operation.intent().machineInventory()) {
            downloadInventoryTargets.get(operation.intent().playerId()).addTo(operation.intent().target(), -1);
        }
        dirty = true;
        persist();
        var released = downloadSources.release(operation.sources(), operation.id(), players, teams.get(operation.sources().teamId()).research);
        if (released != ResearchDownloadSources.Status.READY)
            throw new IllegalStateException("Terminal download retains a source writer");
        return downloadResult(receipt.outcome() == ResearchDownloadReceipt.Outcome.COMPLETED ? ResearchDownloadResult.Status.COMPLETED : ResearchDownloadResult.Status.ABORTED, operation.id());
    }

    private void releaseDurableDownloads() {
        if (pendingSave != null || activeDownload != null) return;
        for (var operation : downloads.values()) {
            if (operation.reservesSources()) continue;
            var status = downloadSources.release(operation.sources(), operation.id(), players, teams.get(operation.sources().teamId()).research);
            if (status != ResearchDownloadSources.Status.READY)
                throw new IllegalStateException("Durable download retains a source writer: " + status);
        }
        for (var receipt : downloadReceipts.values()) {
            var status = downloadSources.release(receipt.sources(), receipt.operationId(), players, teams.get(receipt.sources().teamId()).research);
            if (status != ResearchDownloadSources.Status.READY)
                throw new IllegalStateException("Terminal download retains a source writer: " + status);
        }
    }

    /**
     * The original single-slot and physical database obligations remain visible through cleanup.
     */
    public ObjectList<ResearchDiskOperation> pendingDiskImports(UUID player) {
        requireOwner();
        var result = new ObjectArrayList<ResearchDiskOperation>();
        for (var operation : activeDiskImports.values())
            if (operation.intent().playerId().equals(player)) result.add(operation);
        return result;
    }

    public @Nullable ResearchDiskOperation diskImport(UUID player, UUID id) {
        requireOwner();
        var operation = diskImports.get(id);
        return operation != null && operation.intent().playerId().equals(player) ? operation : null;
    }

    public @Nullable ResearchDiskOperation diskImportNonce(UUID player, UUID nonce) {
        requireOwner();
        var nonces = diskImportNonces.get(player);
        var id = nonces == null ? null : nonces.get(nonce);
        return id == null ? null : diskImports.get(id);
    }

    public ObjectList<ResearchDiskOperation> pendingMachineDiskImports() {
        requireOwner();
        var result = new ObjectArrayList<ResearchDiskOperation>();
        for (var operation : activeDiskImports.values())
            if (operation.intent().machineInventory()) result.add(operation);
        return result;
    }

    public ResearchDiskImportResult requestDiskImport(ResearchDiskOperation.Intent intent, OwnerTeamResolution membership,
                                                      ResearchPermissionContext permission, ResearchDataCenters.Target target,
                                                      DataCenterStorage storage, ResearchDownloadInventory inventory,
                                                      Function<ResearchDownloadMaterial, ResearchItemImportEvaluation> evaluate,
                                                      @Nullable ResearchDownloadMaterial candidate, ItemDeliveryAdapter delivery, long time,
                                                      BooleanSupplier authorized, BooleanSupplier physicalCurrent,
                                                      BooleanSupplier actorCurrent) {
        requireOwner();
        requireHealthy();
        if (executing || activeTick != null || closing || closed)
            return diskResult(ResearchDiskImportResult.Status.BUSY, null);
        if (!intent.playerId().equals(inventory.playerId()) || !intent.databaseId().equals(target.dataCenterId()))
            throw new IllegalArgumentException("Disk import capability differs from its request");
        if (intent.machineInventory() != inventory.machineInventory())
            throw new IllegalArgumentException("Import inventory differs from its frozen physical target");
        return operate(time, false, () -> {
            var knownNonces = diskImportNonces.get(intent.playerId());
            var knownId = knownNonces == null ? null : knownNonces.get(intent.nonce());
            if (knownId != null) {
                var operation = diskImports.get(knownId);
                if (!operation.intent().equals(intent))
                    return diskResult(ResearchDiskImportResult.Status.NONCE_CONFLICT, operation.id());
                if (operation.terminal()) return diskTerminalResult(operation);
                return diskResult(ResearchDiskImportResult.Status.PENDING, operation.id());
            }
            if (!(storage instanceof ResearchDiskStorage disks))
                return diskResult(ResearchDiskImportResult.Status.UNSUPPORTED_STORAGE, null);
            if (!disks.diskPersistenceAvailable())
                return diskResult(ResearchDiskImportResult.Status.UNSUPPORTED_STORAGE, null);
            if (!authorized.getAsBoolean() || !physicalCurrent.getAsBoolean() || !actorCurrent.getAsBoolean())
                return diskResult(ResearchDiskImportResult.Status.STALE_CONTEXT, null);
            if (!membership.accepted() || !intent.playerId().equals(membership.ownerId()) || !intent.teamId().equals(membership.teamId())
                || !membership.members().contains(target.ownerId()) || !intent.playerId().equals(permission.actorId())
                || !intent.teamId().equals(permission.actorTeamId()) || !intent.teamId().equals(permission.targetTeamId())
                || permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission) != ResearchPermissionPolicy.Decision.ALLOWED)
                return diskResult(ResearchDiskImportResult.Status.PERMISSION_DENIED, null);
            if (!inventory.operations().isEmpty()) return diskResult(ResearchDiskImportResult.Status.BUSY, null);
            for (var operation : downloads.values())
                if (operation.intent().playerId().equals(intent.playerId()))
                    return diskResult(ResearchDiskImportResult.Status.BUSY, operation.id());
            for (var operation : activeDiskImports.values())
                if (operation.intent().playerId().equals(intent.playerId()))
                    return diskResult(ResearchDiskImportResult.Status.BUSY, operation.id());
            var team = captureTeam(intent.teamId(), membership.members());
            if (membersBusy(membership.members()) || team.research.busy() || team.queue.busy())
                return diskResult(ResearchDiskImportResult.Status.BUSY, null);
            var id = candidate == null ? UUID.randomUUID() : candidate.operationId();
            var material = candidate == null ? inventory.capture(id, intent.slot()) : candidate;
            if (material == null) return diskResult(ResearchDiskImportResult.Status.MISSING_DISK, null);
            if (!id.equals(material.operationId()) || !intent.playerId().equals(material.playerId()) || intent.slot() != material.slot()
                || material.status() != ResearchDownloadMaterial.Status.PREPARED)
                throw new IllegalArgumentException("Captured disk changed its exact material identity");
            var evaluated = evaluate.apply(material);
            if (evaluated == null || evaluated.amount() < 1 || evaluated.output().isEmpty())
                return diskResult(ResearchDiskImportResult.Status.NO_RECIPE, null);
            if (!delivery.canOutput(evaluated.output(), evaluated.amount()))
                return diskResult(ResearchDiskImportResult.Status.OUTPUT_BLOCKED, null);
            var data = evaluated.data();
            var sources = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
            for (var member : membership.members()) sources.put(member, players.get(member));
            var cloud = eligibility.calculateStructural(team.id, membership.members(), sources, team.research.completedLevelsSnapshot(), team.blacklist);
            var prepared = ResearchDiskImport.prepare(id, intent.playerId(), intent.teamId(), data, disks.snapshot(), cloud);
            if (prepared.commit() == null)
                return new ResearchDiskImportResult(ResearchDiskImportResult.Status.DISK_REJECTED, null, prepared.result());
            if (!authorized.getAsBoolean() || !physicalCurrent.getAsBoolean() || !actorCurrent.getAsBoolean() || !inventory.matches(material.inventoryBefore()))
                return diskResult(ResearchDiskImportResult.Status.STALE_CONTEXT, null);
            long amount = evaluated.amount();
            var nativeOutput = evaluated.output();
            var operation = new ResearchDiskOperation(intent, target, prepared.commit(), material, time, ResearchDiskOperation.Status.PREPARED, false,
                nativeOutput, amount, ObjectList.of());
            addDiskImport(operation);
            dirty = true;
            persist();
            return advanceDiskImport(id, inventory, delivery, new ResearchDiskRecovery(disks, target, physicalCurrent, membership, permission, authorized), actorCurrent);
        });
    }

    /**
     * Authority is optional after the database effect; recovery always uses the frozen physical target.
     */
    public ResearchDiskImportResult recoverDiskImport(UUID player, UUID id, ResearchDownloadInventory inventory,
                                                      ItemDeliveryAdapter delivery, ResearchDiskRecovery recovery, long time, BooleanSupplier actorCurrent) {
        requireOwner();
        requireHealthy();
        if (!player.equals(inventory.playerId()))
            throw new IllegalArgumentException("Disk recovery inventory belongs to another player");
        if (executing || activeTick != null || closing || closed)
            return diskResult(ResearchDiskImportResult.Status.BUSY, id);
        return operate(time, false, () -> {
            var operation = diskImports.get(id);
            if (operation == null || !operation.intent().playerId().equals(player) || !operation.target().equals(recovery.target())
                || operation.intent().machineInventory() != inventory.machineInventory()
                || !operation.commit().before().dataCenterId().equals(recovery.storage().snapshot().dataCenterId()))
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            return advanceDiskImport(id, inventory, delivery, recovery, actorCurrent);
        });
    }

    private ResearchDiskImportResult advanceDiskImport(UUID id, ResearchDownloadInventory inventory,
                                                       ItemDeliveryAdapter delivery, ResearchDiskRecovery recovery, BooleanSupplier actorCurrent) {
        var operation = diskImports.get(id);
        if (operation.terminal()) return diskTerminalResult(operation);
        if (operation.status() == ResearchDiskOperation.Status.ABORTING) return finishDiskAbort(operation, inventory);
        if (!actorCurrent.getAsBoolean() || !recovery.physicalCurrent().getAsBoolean())
            return diskResult(ResearchDiskImportResult.Status.PENDING, id);
        var material = inventory.find(id);
        if (material != null && !operation.material().sameOperation(material))
            return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
        var storage = recovery.storage();
        if (operation.status() == ResearchDiskOperation.Status.PREPARED) {
            var receipt = storage.receipt(id);
            if (receipt != null && !receipt.equals(operation.commit()))
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            if (receipt != null && !operation.databaseArmed())
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            if (receipt == null) {
                if (operation.databaseArmed()) return diskResult(ResearchDiskImportResult.Status.PENDING, id);
                if (!recovery.authorized().getAsBoolean())
                    return diskResult(ResearchDiskImportResult.Status.PENDING, id);
                var cloud = diskImportCloud(operation, recovery);
                if (cloud == null) return diskResult(ResearchDiskImportResult.Status.PENDING, id);
                if (material != null && material.status() != ResearchDownloadMaterial.Status.PREPARED
                    || !inventory.matches(operation.material().inventoryBefore()))
                    return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
                inventory.recordPrepared(operation.material());
                if (!recovery.authorized().getAsBoolean() || !recovery.physicalCurrent().getAsBoolean() || !actorCurrent.getAsBoolean())
                    return diskResult(ResearchDiskImportResult.Status.PENDING, id);
                boolean firstAttempt = !operation.databaseArmed();
                if (firstAttempt) {
                    operation = operation.armed();
                    saveDiskImport(operation);
                }
                var result = storage.commitDisk(operation.commit(), cloud,
                    () -> recovery.authorized().getAsBoolean() && recovery.physicalCurrent().getAsBoolean() && actorCurrent.getAsBoolean());
                if (!result.accepted()) {
                    if (firstAttempt && storage.receipt(id) == null) {
                        operation = operation.transition(ResearchDiskOperation.Status.ABORTING);
                        saveDiskImport(operation);
                        return finishDiskAbort(operation, inventory);
                    }
                    return diskResult(ResearchDiskImportResult.Status.PENDING, id);
                }
            } else if (material == null) {
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            }
            if (!operation.commit().equals(storage.receipt(id)))
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            storage.persistDisk(operation.commit(), recovery.physicalCurrent());
            operation = operation.transition(ResearchDiskOperation.Status.DATABASE_COMMITTED);
            saveDiskImport(operation);
        }
        if (operation.status() == ResearchDiskOperation.Status.DATABASE_COMMITTED) {
            if (!operation.commit().equals(storage.receipt(id)))
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            material = inventory.find(id);
            if (!operation.material().sameOperation(material))
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            if (!actorCurrent.getAsBoolean()) return diskResult(ResearchDiskImportResult.Status.PENDING, id);
            if (material.status() == ResearchDownloadMaterial.Status.PREPARED) {
                if (!inventory.matches(material.inventoryBefore()))
                    return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
                if (!inventory.consume(material)) return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            }
            inventory.persistCommitted(id);
            material = inventory.find(id);
            if (!operation.material().sameOperation(material) || material.status() != ResearchDownloadMaterial.Status.COMMITTED)
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            operation = operation.delivering(delivery.plan(new DownloadOutputKey(operation.intent().playerId(), id), operation.output(), operation.amount()));
            saveDiskImport(operation);
        }
        if (operation.status() == ResearchDiskOperation.Status.DELIVERING) {
            var status = ItemDelivery.advance(() -> diskImports.get(id).fragments(),
                fragment -> saveDiskImport(diskImports.get(id).replace(fragment)), delivery, actorCurrent);
            if (status != ItemDelivery.Status.COMPLETED)
                return diskResult(status == ItemDelivery.Status.RECOVERY_ERROR ? ResearchDiskImportResult.Status.RECOVERY_ERROR : ResearchDiskImportResult.Status.PENDING, id);
            operation = diskImports.get(id).transition(ResearchDiskOperation.Status.COMPLETED);
            saveDiskImport(operation);
        }
        if (operation.status() == ResearchDiskOperation.Status.COMPLETED) {
            // Both effects are durable. Missing cleanup retries save their removal without reapplying either effect.
            inventory.removeCommitted(id);
            storage.clearDiskReceipt(operation.commit(), recovery.physicalCurrent());
            saveDiskImport(operation.transition(ResearchDiskOperation.Status.RELEASED));
            return diskResult(ResearchDiskImportResult.Status.COMPLETED, id);
        }
        return diskResult(ResearchDiskImportResult.Status.PENDING, id);
    }

    private void saveDiskImport(ResearchDiskOperation operation) {
        if (!executing || !diskImports.containsKey(operation.id()))
            throw new IllegalStateException("Disk checkpoint lost its world owner");
        diskImports.put(operation.id(), operation);
        if (operation.terminal()) activeDiskImports.remove(operation.id());
        else activeDiskImports.put(operation.id(), operation);
        dirty = true;
        persist();
    }

    private void addDiskImport(ResearchDiskOperation operation) {
        diskImports.put(operation.id(), operation);
        if (!operation.terminal()) activeDiskImports.put(operation.id(), operation);
        diskImportNonces.computeIfAbsent(operation.intent().playerId(), _ -> new Object2ObjectOpenHashMap<>())
                        .put(operation.intent().nonce(), operation.id());
    }

    /**
     * Cancellation uses the world's durable proof that no database call was armed.
     */
    public ResearchDiskImportResult abortDiskImport(UUID player, UUID id, ResearchDownloadInventory inventory, long time) {
        requireOwner();
        requireHealthy();
        if (!player.equals(inventory.playerId()))
            throw new IllegalArgumentException("Disk cancellation belongs to another player");
        if (executing || activeTick != null || closing || closed)
            return diskResult(ResearchDiskImportResult.Status.BUSY, id);
        return operate(time, false, () -> {
            var operation = diskImports.get(id);
            if (operation == null || !operation.intent().playerId().equals(player)
                || operation.intent().machineInventory() != inventory.machineInventory())
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            if (operation.terminal()) return diskTerminalResult(operation);
            if (operation.status() == ResearchDiskOperation.Status.ABORTING)
                return finishDiskAbort(operation, inventory);
            var material = inventory.find(id);
            if (operation.status() != ResearchDiskOperation.Status.PREPARED || operation.databaseArmed()
                || material != null && (!operation.material().sameOperation(material) || material.status() != ResearchDownloadMaterial.Status.PREPARED)
                || !inventory.matches(operation.material().inventoryBefore()))
                return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, id);
            operation = operation.transition(ResearchDiskOperation.Status.ABORTING);
            saveDiskImport(operation);
            return finishDiskAbort(operation, inventory);
        });
    }

    private ResearchDiskImportResult finishDiskAbort(ResearchDiskOperation operation, ResearchDownloadInventory inventory) {
        var material = inventory.find(operation.id());
        if (material != null && (!operation.material().sameOperation(material) || material.status() != ResearchDownloadMaterial.Status.PREPARED)
            || !inventory.matches(operation.material().inventoryBefore()))
            return diskResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, operation.id());
        inventory.removePrepared(operation.id());
        var aborted = operation.transition(ResearchDiskOperation.Status.ABORTED);
        saveDiskImport(aborted);
        return diskTerminalResult(aborted);
    }

    private @Nullable TeamResearchView diskImportCloud(ResearchDiskOperation operation, ResearchDiskRecovery recovery) {
        var membership = recovery.membership();
        var permission = recovery.permission();
        var intent = operation.intent();
        if (membership == null || permission == null || !membership.accepted()
            || !intent.playerId().equals(membership.ownerId()) || !intent.teamId().equals(membership.teamId())
            || !membership.members().contains(operation.target().ownerId()) || !intent.playerId().equals(permission.actorId())
            || !intent.teamId().equals(permission.actorTeamId()) || !intent.teamId().equals(permission.targetTeamId())
            || permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission) != ResearchPermissionPolicy.Decision.ALLOWED)
            return null;
        var team = captureTeam(intent.teamId(), membership.members());
        if (membersBusy(membership.members()) || team.research.busy() || team.queue.busy()) return null;
        var sources = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
        for (var member : membership.members()) sources.put(member, players.get(member));
        return eligibility.calculateStructural(team.id, membership.members(), sources, team.research.completedLevelsSnapshot(), team.blacklist);
    }

    public void playerLogin(UUID player, long time) {
        operate(time, false, () -> {
            ensurePlayer(player);
            if (dirty) {
                persist();
            }
            return null;
        });
    }

    /**
     * Claims this player's permanent eligibility through the world's single durable writer. No team
     * membership or current research level is required: forgetting and changing teams retain rewards.
     * The exact reward session is the only busy source admitted to its own intermediate checkpoints.
     */
    public ResearchRewardClaimer.Result claimReward(UUID actor, ResourceLocation id, int level,
                                                    ResourceLocation group, long time, ItemDeliveryAdapter delivery, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        if (activeTick != null && !executing) {
            return rewardResult(ResearchRewardClaimer.Status.BUSY);
        }
        return operate(time, false, () -> {
            if (!current.getAsBoolean()) {
                return rewardResult(ResearchRewardClaimer.Status.STALE_CONTEXT);
            }
            var definition = registry.require(id);
            definition.stageForLevel(level);
            var player = players.get(actor);
            if (player == null) {
                return rewardResult(ResearchRewardClaimer.Status.MISSING_CLAIM);
            }
            for (var source : players.values()) {
                if (source.busy()) {
                    return rewardResult(ResearchRewardClaimer.Status.BUSY);
                }
            }
            for (var team : teams.values()) {
                if (team.research.busy() || team.queue.busy()) {
                    return rewardResult(ResearchRewardClaimer.Status.BUSY);
                }
            }
            var key = new RewardClaim.Key(actor, id, level);
            var reward = player.tryRewardSession(key);
            if (reward == null) {
                return rewardResult(ResearchRewardClaimer.Status.BUSY);
            }
            try (reward) {
                activeReward = reward;
                return ResearchRewardClaimer.claim(new ResearchRewardClaimer.Request(player, definition, level,
                    group, time, claim -> rewardCheckpoint(player, reward, claim), delivery, current), reward);
            } finally {
                activeReward = null;
            }
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

    /**
     * Logout pauses every matching active session, including work waiting behind another queue head.
     */
    public void playerLogout(UUID player, long time, Set<UUID> remainingOnline) {
        operate(time, false, () -> {
            if (remainingOnline.contains(player)) {
                throw new IllegalArgumentException("Logout snapshot still contains the leaving player");
            }
            for (var team : teams.values()) {
                var context = new ManualResearch.Context(time, team.knownMembers, remainingOnline, () -> true);

                var elements4 = team.manual.sessions();
                for (int index4 = 0; index4 < elements4.size(); index4++) {
                    var session = elements4.get(index4);
                    if (session.status() == ManualResearchSession.Status.ACTIVE && player.equals(session.contributor())) {
                        team.manual.pause(session.queueKey().queueEntryId(), context, ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE);
                    }
                }

            }
            if (dirty) {
                persist();
            }
            return null;
        });
    }

    /**
     * Admission uses structural facts and invokes the real requester condition exactly at start.
     */
    @SuppressWarnings("DataFlowIssue")
    public TeamResearchStart.Result start(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                          ResourceLocation id, int target, TeamResearchStart.Environment environment,
                                          long time, Set<UUID> online, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        if (activeTick != null && !executing) {
            return new TeamResearchStart.Result(TeamResearchStart.Status.QUEUE_BUSY, null, null);
        }
        return operate(time, false, () -> {
            if (!membership.accepted() || !current.getAsBoolean()) {
                return staleStart();
            }
            if (!membership.teamId().equals(permission.targetTeamId())) {
                throw new IllegalArgumentException("Admission target team changed");
            }
            var definition = registry.require(id);
            boolean manual = definition.stageForLevel(target).researchTier() == 0;
            if (manual && (!membership.members().contains(permission.actorId()) || !online.contains(permission.actorId()))) {
                return staleStart();
            }
            var team = captureTeam(membership.teamId(), membership.members());
            ensurePlayer(permission.actorId());
            if (team.queue.busy() || team.research.busy() || membersBusy(team.knownMembers)) {
                return new TeamResearchStart.Result(TeamResearchStart.Status.QUEUE_BUSY, null, null);
            }
            var capturedSources = new ObjectArrayList<PlayerResearchState.Snapshot>(team.knownMembers.size());
            for (var member : team.knownMembers) {
                capturedSources.add(players.get(member).snapshot());
            }
            var capturedTeam = team.research.snapshot();
            BooleanSupplier proof = () -> current.getAsBoolean() && capturedTeam.equals(team.research.snapshot())
                && !team.research.busy() && sourcesCurrent(capturedSources);
            var view = structural(team, false);
            var result = admission.start(new TeamResearchStart.Request(team.queue, view, permission, id, target, environment, time, proof));
            if (result.status() != TeamResearchStart.Status.STARTED) {
                if (dirty) {
                    persist();
                }
                return result;
            }
            var entry = result.entry();
            dirty = true;
            if (!manual) {
                persist();
                return result;
            }
            try {
                var status = team.manual.begin(entry, permission.actorId(), new ManualResearch.Context(time, team.knownMembers, online, proof));
                if (status == ManualResearch.Status.STARTED) {
                    return result;
                }
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
                        if (queued(team.queue, entry.key())) {
                            team.queue.removeUnstarted(entry.key());
                        }
                        persist();
                    } catch (RuntimeException | Error cleanup) {
                        if (failure != cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                    }
                }
                throw failure;
            }
        });
    }

    public ManualResearch.Status takeover(OwnerTeamResolution membership, UUID actor, ResourceLocation id, int target,
                                          long time, Set<UUID> online, BooleanSupplier current) {
        return takeover(membership, actor, id, target, 0, time, online, current);
    }

    /**
     * A nonzero entry ID binds takeover to the original manual session shown to the requester.
     */
    public ManualResearch.Status takeover(OwnerTeamResolution membership, UUID actor, ResourceLocation id, int target,
                                          long expectedEntryId, long time, Set<UUID> online, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        if (expectedEntryId < 0) throw new IllegalArgumentException("Invalid expected research queue entry");
        if (activeTick != null && !executing) {
            return ManualResearch.Status.RECOVERY_PENDING;
        }
        return operate(time, false, () -> {
            if (!membership.accepted() || !current.getAsBoolean()) {
                return ManualResearch.Status.STALE_CONTEXT;
            }
            var team = teams.get(membership.teamId());
            if (team == null) {
                return ManualResearch.Status.STALE_CONTEXT;
            }
            captureTeam(membership.teamId(), membership.members());
            var elements27 = team.queue.queueSnapshot();
            for (int index27 = 0; index27 < elements27.size(); index27++) {
                var entry = elements27.get(index27);
                if (entry.key().researchId().equals(id) && entry.key().targetLevel() == target) {
                    if (expectedEntryId != 0 && expectedEntryId != entry.key().queueEntryId())
                        return ManualResearch.Status.STALE_CONTEXT;
                    if (team.manual.session(entry.key().queueEntryId()) == null) {
                        return ManualResearch.Status.STALE_CONTEXT;
                    }
                    return team.manual.takeover(entry.key().queueEntryId(), actor, new ManualResearch.Context(time, team.knownMembers, online, current));
                }
            }
            return ManualResearch.Status.STALE_CONTEXT;
        });
    }

    /**
     * Forget the suffix starting at forgottenLevel; existing rewards and paid work are retained.
     */
    public ResearchLevelResult forgetPersonal(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                              ResourceLocation id, int forgottenLevel, long time, BooleanSupplier current) {
        return forgetPersonal(membership, permission, id, forgottenLevel, -1, time, current);
    }

    /**
     * expectedBefore binds a displayed personal level; -1 is the ordinary Java API without a view.
     */
    public ResearchLevelResult forgetPersonal(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                              ResourceLocation id, int forgottenLevel, int expectedBefore,
                                              long time, BooleanSupplier current) {
        return changePlayerLevel(membership, permission, id, forgottenLevel, expectedBefore, true, time, current);
    }

    /**
     * Absolute personal source repair, including zero, without creating reward eligibility.
     */
    public ResearchLevelResult setPlayerResearchLevel(OwnerTeamResolution target, ResearchPermissionContext permission,
                                                      ResourceLocation id, int level, long time, BooleanSupplier current) {
        return changePlayerLevel(target, permission, id, level, -1, false, time, current);
    }

    private ResearchLevelResult changePlayerLevel(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                                  ResourceLocation id, int level, int expectedBefore, boolean forget,
                                                  long time, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        if (expectedBefore < -1) throw new IllegalArgumentException("Invalid expected personal level");
        if (activeTick != null && !executing) return ResearchLevelResult.RECOVERY_PENDING;
        return operate(time, false, () -> {
            var definition = registry.require(id);
            var target = membership.ownerId();
            if (!membership.accepted() || !membership.teamId().equals(permission.targetTeamId())
                || !target.equals(permission.personalTarget()) || forget && !target.equals(permission.actorId())
                || !current.getAsBoolean()) return ResearchLevelResult.STALE_CONTEXT;
            var action = forget ? ResearchPermissionPolicy.Action.FORGET_PERSONAL_RESEARCH : ResearchPermissionPolicy.Action.MODIFY_ANY_RESEARCH_LEVEL;
            if (permissions.can(action, permission) != ResearchPermissionPolicy.Decision.ALLOWED)
                return ResearchLevelResult.PERMISSION_DENIED;
            if (definition.specialOnly()) return ResearchLevelResult.SOURCE_MISMATCH;
            if (level < (forget ? 1 : 0) || level > definition.maxLevel()) return ResearchLevelResult.OUT_OF_RANGE;
            var previous = players.get(target);
            int before = previous == null ? 0 : previous.completedLevel(id);
            if (expectedBefore >= 0 && expectedBefore != before) return ResearchLevelResult.STALE_CONTEXT;
            int after = forget ? Math.min(before, level - 1) : level;
            if (after == before) return ResearchLevelResult.UNCHANGED;
            if (levelWritersBusy()) return ResearchLevelResult.BUSY;
            if (previous != null && previous.sourceReserved(id)) return ResearchLevelResult.SOURCE_RESERVED;
            if (after < before && pendingLevelCompletion(id, target, null)) return ResearchLevelResult.BUSY;
            var source = previous == null ? new PlayerResearchState(target) : previous;
            var baseline = source.snapshot();
            var receipt = after > before ? source.tryPrepareCompletion(id, true, after, IntSets.emptySet(), time)
                : forget ? source.tryPrepareForgetResearch(id, level) : source.tryPrepareSourceMutation(id, before, after, null);
            if (receipt == null) return ResearchLevelResult.BUSY;
            activePlayerLevel = receipt;
            try {
                return applyLevelChange(receipt, () -> {
                        if (!receipt.owns(source) || !baseline.equals(receipt.snapshot()))
                            throw new IllegalStateException("Personal level operation lost its exact source baseline");
                        return current.getAsBoolean()
                            && permissions.can(action, permission) == ResearchPermissionPolicy.Decision.ALLOWED && players.get(target) == previous;
                    },
                    () -> {
                        if (previous == null) players.put(target, source);
                        captureTeam(membership.teamId(), membership.members());
                    });
            } finally {
                activePlayerLevel = null;
            }
        });
    }

    /**
     * Set each current member's ordinary level; historical IDs can still repair their legacy source.
     */
    public ResearchLevelResult setTeamResearchLevel(String target, Collection<UUID> members, ResearchPermissionContext permission,
                                                    ResourceLocation id, int level, long time, BooleanSupplier current) {
        requireOwner();
        requireHealthy();
        if (target.isBlank() || !target.equals(target.trim()))
            throw new IllegalArgumentException("Invalid target team");
        var capturedMembers = new ObjectOpenHashSet<>(members);
        if (activeTick != null && !executing) return ResearchLevelResult.RECOVERY_PENDING;
        return operate(time, false, () -> {
            var definition = registry.require(id);
            if (!target.equals(permission.targetTeamId()) || permission.targetPlayerId() != null || !current.getAsBoolean())
                return ResearchLevelResult.STALE_CONTEXT;
            var action = ResearchPermissionPolicy.Action.MODIFY_ANY_RESEARCH_LEVEL;
            if (permissions.can(action, permission) != ResearchPermissionPolicy.Decision.ALLOWED)
                return ResearchLevelResult.PERMISSION_DENIED;
            if (definition.specialOnly()) return ResearchLevelResult.SOURCE_MISMATCH;
            if (level < 0 || level > definition.maxLevel()) return ResearchLevelResult.OUT_OF_RANGE;
            var previous = teams.get(target);
            if (!capturedMembers.isEmpty())
                return setMemberLevels(target, capturedMembers, previous, id, level, time, current);
            // The explicit historical-source repair API remains available for old worlds with no live membership.
            if (definition.deliveryPolicy() != ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                return ResearchLevelResult.SOURCE_MISMATCH;
            int before = previous == null ? 0 : previous.research.completedLevel(id);
            if (level == before) return ResearchLevelResult.UNCHANGED;
            if (levelWritersBusy()) return ResearchLevelResult.BUSY;
            if (previous != null && previous.research.sourceReserved(id)) return ResearchLevelResult.SOURCE_RESERVED;
            if (level < before && pendingLevelCompletion(id, null, target)) return ResearchLevelResult.BUSY;
            var team = previous == null ? new TeamProgress(target, new TeamResearchQueue(registry, target), players) : previous;
            var source = team.research;
            var baseline = source.snapshot();
            var receipt = level > before ? source.tryPrepareCompletion(id, level) : source.tryPrepareSourceMutation(id, before, level, null);
            if (receipt == null) return ResearchLevelResult.BUSY;
            activeTeamLevel = receipt;
            try {
                return applyLevelChange(receipt, () -> {
                        if (!receipt.owns(source) || !baseline.equals(receipt.snapshot()))
                            throw new IllegalStateException("Team level operation lost its exact source baseline");
                        return current.getAsBoolean()
                            && permissions.can(action, permission) == ResearchPermissionPolicy.Decision.ALLOWED && teams.get(target) == previous;
                    },
                    () -> {
                        if (previous == null) {
                            teams.put(target, team);
                            capturedTeams = null;
                        }
                        captureTeam(target, capturedMembers);
                    });
            } finally {
                activeTeamLevel = null;
            }
        });
    }

    private ResearchLevelResult setMemberLevels(String target, ObjectSet<UUID> members, @Nullable TeamProgress previous,
                                                ResourceLocation id, int level, long time, BooleanSupplier current) {
        if (levelWritersBusy()) return ResearchLevelResult.BUSY;
        int legacyLevel = previous == null ? 0 : previous.research.completedLevel(id);
        if (legacyLevel > 0) {
            if (previous.research.sourceReserved(id)) return ResearchLevelResult.SOURCE_RESERVED;
            if (pendingLevelCompletion(id, null, target)) return ResearchLevelResult.BUSY;
        }
        var sources = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
        for (var member : members) {
            var source = players.get(member);
            int before = source == null ? 0 : source.completedLevel(id);
            if (before == level) continue;
            if (source != null && source.sourceReserved(id)) return ResearchLevelResult.SOURCE_RESERVED;
            if (before > level && pendingLevelCompletion(id, member, null)) return ResearchLevelResult.BUSY;
            sources.put(member, source == null ? new PlayerResearchState(member) : source);
        }
        if (sources.isEmpty() && legacyLevel == 0) return ResearchLevelResult.UNCHANGED;
        if (!current.getAsBoolean()) return ResearchLevelResult.STALE_CONTEXT;
        var receipts = new Object2ObjectOpenHashMap<UUID, PlayerResearchState.Completion>();
        var transactions = new ObjectArrayList<ReversibleTransaction>();
        boolean committed = false;
        try {
            for (var entry : sources.object2ObjectEntrySet()) {
                var source = entry.getValue();
                int before = source.completedLevel(id);
                var receipt = level > before ? source.tryPrepareCompletion(id, true, level, IntSets.emptySet(), time)
                    : source.tryPrepareSourceMutation(id, before, level, null);
                if (receipt == null) return ResearchLevelResult.BUSY;
                receipts.put(entry.getKey(), receipt);
                transactions.add(receipt);
            }
            if (legacyLevel > 0) {
                activeTeamLevel = previous.research.tryPrepareSourceMutation(id, legacyLevel, 0, null);
                if (activeTeamLevel == null) return ResearchLevelResult.BUSY;
                transactions.add(activeTeamLevel);
            }
            activeMemberLevels = receipts;
            if (!current.getAsBoolean()) return ResearchLevelResult.STALE_CONTEXT;
            for (var transaction : transactions) transaction.commit();
            committed = true;
            for (var entry : sources.object2ObjectEntrySet()) players.putIfAbsent(entry.getKey(), entry.getValue());
            captureTeam(target, members);
            dirty = true;
            persist();
            return ResearchLevelResult.APPLIED;
        } finally {
            try {
                for (int index = transactions.size() - 1; index >= 0; index--) {
                    if (committed) transactions.get(index).complete();
                    else transactions.get(index).rollback();
                }
            } finally {
                activeMemberLevels = null;
                activeTeamLevel = null;
            }
        }
    }

    private boolean levelWritersBusy() {
        for (var player : players.values())
            if (player.busy() && (activePlayerLevel == null || !activePlayerLevel.owns(player))) return true;
        for (var team : teams.values())
            if (team.research.busy() && (activeTeamLevel == null || !activeTeamLevel.owns(team.research))
                || team.queue.busy() || team.manual.checkpointPending()) return true;
        return false;
    }

    private boolean pendingLevelCompletion(ResourceLocation id, @Nullable UUID player, @Nullable String teamId) {
        for (var team : teams.values()) {
            if (teamId != null && !teamId.equals(team.id)) continue;
            if (team.manual.blocksSourceReduction(id, player)) return true;
        }
        return false;
    }

    /**
     * An IO fault retains committed state and pendingSave; only precommit faults compensate.
     */
    private ResearchLevelResult applyLevelChange(ReversibleTransaction receipt, BooleanSupplier current, Runnable publish) {
        boolean committed = false;
        try {
            if (!current.getAsBoolean()) {
                receipt.rollback();
                return ResearchLevelResult.STALE_CONTEXT;
            }
            if (levelWritersBusy()) {
                receipt.rollback();
                return ResearchLevelResult.BUSY;
            }
            receipt.commit();
            committed = true;
            publish.run();
            dirty = true;
            persist();
            receipt.complete();
            return ResearchLevelResult.APPLIED;
        } catch (RuntimeException | Error failure) {
            try {
                if (committed) receipt.complete();
                else receipt.rollback();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Persist a permission-checked blacklist; the next tick settles pending work before pruning it.
     */
    public ResearchPermissionPolicy.Decision setBlacklisted(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                                            ResourceLocation id, boolean value, long time, BooleanSupplier current) {
        return operate(time, false, () -> {
            registry.require(id);
            if (!membership.accepted() || !membership.teamId().equals(permission.targetTeamId()) || !current.getAsBoolean()) {
                return ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER;
            }
            var decision = permissions.can(ResearchPermissionPolicy.Action.BLACKLIST_TEAM_RESEARCH, permission);
            if (decision != ResearchPermissionPolicy.Decision.ALLOWED) {
                return decision;
            }
            if (!current.getAsBoolean()) {
                return ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER;
            }
            var team = captureTeam(membership.teamId(), membership.members());
            boolean changed = value ? team.blacklist.add(id) : team.blacklist.remove(id);
            if (changed) team.capturedBlacklist = null;
            if (changed) {
                dirty = true;
            }
            if (dirty) {
                persist();
            }
            return decision;
        });
    }

    /**
     * One call per server END boundary, after worker exit; unavailable membership never means teardown.
     */
    public void tick(long time, Map<UUID, OwnerTeamResolution> captures, Set<UUID> online,
                     Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current) {
        operate(time, false, () -> {
            var memberships = validateMemberships(captures);
            if (memberships == null || !current.getAsBoolean()) {
                return null;
            }
            for (var entry : memberships.entrySet()) {
                captureTeam(entry.getKey(), entry.getValue());
            }
            for (var team : teams.values()) {
                if (team.queue.size() == 0 && team.manual.sessions().isEmpty()) {
                    continue;
                }
                var members = memberships.getOrDefault(team.id, ObjectSets.emptySet());
                if (!members.equals(team.knownMembers)) {
                    team.knownMembers = members;
                    dirty = true;
                }
            }
            var elements28 = teamSnapshot();
            for (int index28 = 0; index28 < elements28.size(); index28++) {
                var team = elements28.get(index28);
                if (!current.getAsBoolean()) {
                    break;
                }
                if (team.queue.size() == 0 && team.manual.sessions().isEmpty()) {
                    continue;
                }
                if (team.queue.busy() || team.research.busy() || membersBusy(team.knownMembers)) {
                    continue;
                }
                var context = new ManualResearch.Context(time, team.knownMembers, online, current);
                if (!reconcile(team, context, inventories)) {
                    continue;
                }
                var head = team.queue.activeHead();
                if (head == null || registry.require(head.key().researchId()).stageForLevel(head.key().targetLevel()).researchTier() != 0) {
                    continue;
                }
                var session = team.manual.session(head.key().queueEntryId());
                if (session == null) {
                    throw new IllegalStateException("Tier-zero queue head has no manual session");
                }
                team.manual.advance(head.key().queueEntryId(), context, inventoryFor(session, inventories));
            }
            if (checkpointRequested) persist();
            return null;
        });
    }

    /**
     * Captures no business effects; the scheduler owns this execution before its first advance.
     * The completed capture map and online set are borrowed read-only until execution has exited.
     */
    @SuppressWarnings("ReturnOfInnerClass")
    // The retained scheduler execution deliberately owns this world's pending work.
    public PlannedExecution prepareTick(long time, Map<UUID, OwnerTeamResolution> captures, Set<UUID> online,
                                        Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current,
                                        ResearchStations stations, Runnable refresh,
                                        PlannedDeadline forwardDeadline) {
        requireOwner();
        requireHealthy();
        if (activeTick != null || executing || closed || closing) {
            throw new IllegalStateException("Research owner already has a tick or is closing");
        }
        return new StationTick(time, captures, online, inventories, current, stations, refresh, forwardDeadline);
    }

    private boolean reconcile(TeamProgress team, ManualResearch.Context context, Function<UUID, ManualResearchInventory> inventories) {
        // Cancellation can complete an already-paid head and change subsequent eligibility. The queue is bounded.
        for (int round = 0; round <= TeamResearchQueue.CAPACITY; round++) {
            var view = structural(team, team.knownMembers.isEmpty());
            boolean changed = false;
            boolean pending = false;

            var elements6 = team.manual.sessions();
            for (int index6 = 0; index6 < elements6.size(); index6++) {
                var session = elements6.get(index6);
                var key = session.queueKey();
                if (!eligibility.structurallyEligible(view, key.researchId(), key.targetLevel())) {
                    var result = team.manual.cancelInvalidated(key.queueEntryId(), context, inventoryFor(session, inventories));
                    if (result == ManualResearch.Status.CANCELLED || result == ManualResearch.Status.COMPLETED) {
                        changed = true;
                    } else {
                        pending = true;
                    }
                }
            }

            if (pending || !context.current().getAsBoolean()) {
                return false;
            }
            if (changed) {
                continue;
            }
            boolean needsPrune = false;
            var elements21 = team.queue.queueSnapshot();
            for (int index21 = 0; index21 < elements21.size(); index21++) {
                var entry = elements21.get(index21);
                if (!eligibility.structurallyEligible(view, entry.key().researchId(), entry.key().targetLevel())) {
                    needsPrune = true;
                    break;
                }
            }
            if (!needsPrune) {
                return true;
            }
            var prune = team.queue.preparePrune(view);
            boolean removed = !prune.removed().isEmpty();
            try {
                prune.commit();
            } catch (RuntimeException | Error failure) {
                try {
                    prune.rollback();
                } catch (RuntimeException | Error cleanup) {
                    if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
            prune.complete();
            if (removed) {
                dirty = true;
            }
            return true;
        }
        throw new IllegalStateException("Manual cancellation did not converge within its bounded queue");
    }

    private TeamProgress captureTeam(String id, ObjectSet<UUID> members) {
        for (var member : members) {
            ensurePlayer(member);
        }
        var team = teams.get(id);
        if (team == null) {
            team = new TeamProgress(id, new TeamResearchQueue(registry, id), players);
            teams.put(id, team);
            capturedTeams = null;
            dirty = true;
        }
        if (!members.equals(team.knownMembers)) {
            team.knownMembers = members;
            dirty = true;
        }
        return team;
    }

    private void ensurePlayer(UUID id) {
        if (!players.containsKey(id)) {
            players.put(id, new PlayerResearchState(id));
            dirty = true;
        }
    }

    private TeamResearchView structural(TeamProgress team, boolean disappeared) {
        var members = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>(team.knownMembers.size());
        for (var id : team.knownMembers) {
            members.put(id, players.get(id));
        }
        Set<ResourceLocation> blacklist = team.blacklistSnapshot();
        if (disappeared) {
            var blocked = new ObjectOpenHashSet<>(blacklist);
            var elements10 = registry.all();
            for (int index10 = 0; index10 < elements10.size(); index10++) {
                var definition = elements10.get(index10);
                blocked.add(definition.id());
            }
            blacklist = blocked;
        }
        return eligibility.calculateStructural(team.id, team.knownMembers, members, team.research.completedLevelsSnapshot(), blacklist);
    }

    private boolean membersBusy(Collection<UUID> members) {
        for (var id : members) {
            if (players.get(id).busy()) {
                return true;
            }
        }
        return false;
    }

    private boolean sourcesCurrent(ObjectList<PlayerResearchState.Snapshot> snapshots) {
        for (int index18 = 0; index18 < snapshots.size(); index18++) {
            var snapshot = snapshots.get(index18);
            var current = players.get(snapshot.playerUuid());
            if (current == null || current.busy() || !current.snapshot().equals(snapshot)) {
                return false;
            }
        }
        return true;
    }

    private void manualCheckpoint(TeamProgress team, ManualResearch.CheckpointState state, boolean durable) {
        if (!executing || teams.get(team.id) != team || !state.queue().equals(team.queue.snapshot())
            || !state.team().equals(team.research.snapshot()) || !state.sessions().equals(team.manual.sessions())
            || !sourcesCurrent(state.players())) {
            throw new IllegalStateException("Manual checkpoint does not own the world records it supplied");
        }
        // Sources are shared authoritative objects. Preserve every other player; never replace the global table.
        dirty = true;
        if (durable) persist();
    }

    /**
     * World-save hook: unchanged research does no disk work; passive progress waits for this boundary.
     * Material and delivery checkpoints continue to use the synchronous writer directly.
     */
    public void saveChanged(long time) {
        requireOwner();
        requireHealthy();
        if (time < serverTime) throw new IllegalArgumentException("Research save time moved backwards");
        if (executing || activeTick != null || activeReward != null || activeDownload != null) {
            checkpointRequested = true;
            return;
        }
        operate(time, true, () -> {
            if (dirty || checkpointRequested) persist();
            return null;
        });
    }

    public void checkpoint(long time) {
        requireOwner();
        requireHealthy();
        if (time < serverTime) {
            throw new IllegalArgumentException("Research checkpoint time moved backwards");
        }
        if (activeTick != null || activeReward != null || activeDownload != null) {
            checkpointRequested = true;
            return;
        }
        operate(time, true, () -> {
            persist();
            return null;
        });
    }

    /**
     * Stop accepting work before the final write; a failed write retains this owner for retry.
     */
    public void close(long time) {
        requireOwner();
        if (closed) {
            return;
        }
        requireHealthy();
        if (executing) {
            throw new IllegalStateException("Research close cannot reenter an operation");
        }
        if (time < serverTime) {
            throw new IllegalArgumentException("Research close time moved backwards");
        }
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
        var playerSnapshots = new ObjectArrayList<PlayerResearchState.Snapshot>(players.size());
        for (var player : players.values()) playerSnapshots.add(playerSnapshot(player, reward));
        var teamSnapshots = new ObjectArrayList<ResearchWorldSnapshot.Team>(teams.size());
        for (var team : teams.values()) {
            teamSnapshots.add(new ResearchWorldSnapshot.Team(team.id, team.knownMembers, team.blacklistSnapshot(),
                teamResearchSnapshot(team), team.queue.snapshot(), team.manual.sessions()));
        }
        return new ResearchWorldSnapshot(serverTime, playerSnapshots, teamSnapshots, new ObjectArrayList<>(downloads.values()), new ObjectArrayList<>(downloadReceipts.values()), new ObjectArrayList<>(diskImports.values()));
    }

    private PlayerResearchState.Snapshot playerSnapshot(PlayerResearchState player, @Nullable PlayerResearchState.RewardSession reward) {
        if (activePlayerLevel != null && activePlayerLevel.owns(player)) return activePlayerLevel.snapshot();
        if (activeMemberLevels != null) {
            var mutation = activeMemberLevels.get(player.playerUuid());
            if (mutation != null && mutation.owns(player)) return mutation.snapshot();
        }
        if (activeDownload != null && activeDownload.owns(player)) return activeDownload.snapshot(player);
        if (reward != null && reward == activeReward && reward.owns(player, reward.requireCurrent().key()))
            return reward.snapshot();
        if (player.busy())
            throw new IllegalStateException("Cannot snapshot a research source owned by another transaction");
        return player.snapshot();
    }

    private TeamOwnedResearch.Snapshot teamResearchSnapshot(TeamProgress team) {
        boolean changedLevel = activeTeamLevel != null && activeTeamLevel.owns(team.research);
        boolean downloaded = activeDownload != null && activeDownload.owns(team.research);
        if (team.research.busy() && !downloaded && !changedLevel)
            throw new IllegalStateException("Cannot snapshot a pending team research mutation");
        return changedLevel ? activeTeamLevel.snapshot() : downloaded ? activeDownload.snapshot(team.research) : team.research.snapshot();
    }

    private void persist() {
        persist(null);
    }

    private void persist(@Nullable PlayerResearchState.RewardSession reward) {
        if (pendingSave != null) {
            throw new IllegalStateException("Previous world checkpoint still owns its snapshot");
        }
        pendingSave = snapshotState(reward);
        flushPending();
    }

    private void flushPending() {
        if (pendingSave == null) {
            return;
        }
        writer.accept(pendingSave);
        pendingSave = null;
        dirty = false;
        checkpointRequested = false;
        releaseDurableDownloads();
    }

    private <T> T operate(long time, boolean allowClosing, Supplier<T> action) {
        requireOwner();
        requireHealthy();
        if (executing || activeTick != null || closed || closing && !allowClosing) {
            throw new IllegalStateException("Research owner is busy or closing");
        }
        if (time < serverTime) {
            throw new IllegalArgumentException("Research server time moved backwards");
        }
        executing = true;
        try {
            flushPending();
            releaseDurableDownloads();
            for (var team : teams.values()) {
                team.manual.retryCheckpoint();
            }
            serverTime = time;
            return action.get();
        } finally {
            executing = false;
        }
    }

    private void requireHealthy() {
        if (fatalFailure != null) {
            throw new IllegalStateException("Research world retains a fatal station material failure", fatalFailure);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Research world requires its owner thread");
        }
    }

    /**
     * A detached cloud view and a revocable owner-side proof; denied operations expose no levels.
     */
    public record CloudCapture(ResearchPermissionPolicy.Decision decision, @Nullable TeamResearchView view,
                               BooleanSupplier current) {
    }

    private record CloudSource(UUID player, @Nullable PlayerResearchState source,
                               @Nullable PlayerResearchState.Snapshot snapshot) {
    }

    private final class TeamProgress {
        private final String id;
        private final TeamResearchQueue queue;
        private final TeamOwnedResearch research;
        private final ManualResearch manual;
        private final ResearchStationWork station;
        private final ObjectSet<ResourceLocation> blacklist = new ObjectOpenHashSet<>();
        @Nullable
        private ObjectSet<ResourceLocation> capturedBlacklist;
        private ObjectSet<UUID> knownMembers = ObjectSets.emptySet();

        private TeamProgress(String id, TeamResearchQueue queue, Map<UUID, PlayerResearchState> sources) {
            this.id = id;
            this.queue = queue;
            research = new TeamOwnedResearch(id, registry);
            manual = new ManualResearch(queue, research, sources, new ManualResearch.Checkpoint() {
                @Override
                public void persist(ManualResearch.CheckpointState state) {
                    manualCheckpoint(TeamProgress.this, state, true);
                }

                @Override
                public void progressed(ManualResearch.CheckpointState state) {
                    manualCheckpoint(TeamProgress.this, state, false);
                }
            });
            station = new ResearchStationWork(queue, research, sources);
        }

        private ObjectSet<ResourceLocation> blacklistSnapshot() {
            if (capturedBlacklist == null) capturedBlacklist = blacklist.isEmpty() ? ObjectSets.emptySet()
                : new ObjectOpenHashSet<>(blacklist);
            return capturedBlacklist;
        }
    }

    /**
     * Each team settles at most once per server tick; resource reservations finish within the current boundary.
     */
    private final class StationTick implements PlannedExecution {
        private final long time;
        private final Map<UUID, OwnerTeamResolution> captures;
        private final Set<UUID> online;
        private final Function<UUID, ManualResearchInventory> inventories;
        private final BooleanSupplier current;
        private final ResearchStations stations;
        private final Runnable refresh;
        private final PlannedDeadline deadline;
        private boolean stopped, finished, stationOperation;

        private StationTick(long time, Map<UUID, OwnerTeamResolution> captures, Set<UUID> online,
                            Function<UUID, ManualResearchInventory> inventories, BooleanSupplier current,
                            ResearchStations stations, Runnable refresh, PlannedDeadline deadline) {
            if (time < serverTime) throw new IllegalArgumentException("Research time moved backwards");
            this.time = time;
            this.captures = captures;
            this.online = online;
            this.inventories = inventories;
            this.current = current;
            this.stations = stations;
            this.refresh = refresh;
            this.deadline = deadline;
        }

        @Override
        @SuppressWarnings("DataFlowIssue")
        public boolean advance(PlannedDeadline cleanupDeadline) {
            requireOwner();
            if (finished) return true;
            requireHealthy();
            if (executing || activeTick != null && activeTick != this)
                throw new IllegalStateException("Research owner is busy");
            activeTick = this;
            executing = true;
            try {
                flushPending();
                for (var team : teams.values()) team.manual.retryCheckpoint();
                var memberships = validateMemberships(captures);
                if (memberships != null && forward()) {
                    serverTime = time;
                    for (var entry : memberships.entrySet()) captureTeam(entry.getKey(), entry.getValue());
                    var elements29 = teamSnapshot();
                    for (int index29 = 0; index29 < elements29.size(); index29++) {
                        var team = elements29.get(index29);
                        var members = memberships.getOrDefault(team.id, ObjectSets.emptySet());
                        if (!members.equals(team.knownMembers)) {
                            team.knownMembers = members;
                            dirty = true;
                        }
                        if (!forward()) break;
                        if (team.queue.size() == 0 || team.queue.busy() || team.research.busy() || membersBusy(members))
                            continue;
                        var context = new ManualResearch.Context(time, members, online, this::forward);
                        if (!reconcile(team, context, inventories)) continue;
                        var head = team.queue.activeHead();
                        if (head == null) continue;
                        int tier = registry.require(head.key().researchId()).researchTierForLevel(head.key().targetLevel());
                        if (tier == 0) {
                            var session = team.manual.session(head.key().queueEntryId());
                            team.manual.advance(head.key().queueEntryId(), context, inventoryFor(session, inventories));
                        } else {
                            refresh.run();
                            if (!forward()) break;
                            var source = stations.capture(team.id, tier);
                            if (source == null) continue;
                            stationOperation = true;
                            var result = team.station.advance(source, new ResearchStationWork.Context(time, members, this::forward));
                            stationOperation = false;
                            if (result.status() != ResearchStationWork.Status.WAITING) dirty = true;
                        }
                    }
                }
                if (checkpointRequested) persist();
                finished = true;
                activeTick = null;
                return true;
            } catch (RuntimeException | Error failure) {
                stopped = true;
                if (pendingSave == null && stationOperation) {
                    fatalFailure = failure;
                    finished = true;
                    activeTick = null;
                }
                throw failure;
            } finally {
                executing = false;
            }
        }

        private boolean forward() {
            return !stopped && !closing && !closed && !deadline.expired(System.nanoTime()) && current.getAsBoolean();
        }

        @Override
        public boolean finished() {
            requireOwner();
            return finished;
        }

        @Override
        public void cancel(Reason reason) {
            requireOwner();
            stopped = true;
        }

        @Override
        public void awaitExit() {
            requireOwner();
        }
    }
}
