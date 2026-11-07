package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchCompletion;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.research.manual.ManualMaterials;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * PN's tier-zero execution, on the authoritative owner's thread. One instance owns a team's manual
 * sessions. Admission is performed by TeamResearchStart; begin binds its exact queued entry before
 * the host saves it. Each successful advance consumes one item from every unfinished material row.
 * <p>Player receipts and world checkpoints are distinct persistence boundaries. Unknown material
 * outcomes retain their intent. Checkpoint failures escape unchanged and must be retried before any
 * further operation; the queue, team and affected player sources must remain exclusively owned while
 * a checkpoint is pending. The host supplies current membership and one persisted server timeline.
 */
@SuppressWarnings("unused") // Public library execution entry; server persistence owns its instances.
public final class ManualResearch {
    public static final long INACTIVITY_TIMEOUT = 1_200;
    private final Thread owner = Thread.currentThread();
    private final TeamResearchQueue queue;
    private final TeamOwnedResearch team;
    private final Map<UUID, PlayerResearchState> players;
    private final Checkpoint checkpoint;
    private final TeamResearchCompletion completion;
    private final Function<TeamResearchQueue.Key, ManualMaterials> materialCompiler;
    private final Object2ObjectOpenHashMap<TeamResearchQueue.Key, ManualMaterials> compiledMaterials = new Object2ObjectOpenHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<ManualResearchSession> sessions = new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<Status> terminalAwaitingResult = new Long2ObjectLinkedOpenHashMap<>();
    // Live-process proof only: execution has not yet called consume for these exact operations.
    private final ObjectOpenHashSet<UUID> unpreparedOperations = new ObjectOpenHashSet<>();
    @Nullable private CheckpointState pendingCheckpoint;
    private boolean executing;

    public ManualResearch(TeamResearchQueue queue, TeamOwnedResearch team,
                          Map<UUID, PlayerResearchState> players, Checkpoint checkpoint) {
        this(queue, team, players, checkpoint,
            key -> ManualMaterials.from(queue.registry().require(key.researchId()).stageForLevel(key.targetLevel()).materialRequirements()));
    }

    /** Separates definition compilation from execution; the public entry always uses validated research materials. */
    ManualResearch(TeamResearchQueue queue, TeamOwnedResearch team, Map<UUID, PlayerResearchState> players,
                   Checkpoint checkpoint, Function<TeamResearchQueue.Key, ManualMaterials> materialCompiler) {
        this.queue = Objects.requireNonNull(queue);
        this.team = Objects.requireNonNull(team);
        this.players = Objects.requireNonNull(players);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.materialCompiler = Objects.requireNonNull(materialCompiler);
        if (queue.registry() != team.registry() || !queue.teamId().equals(team.teamId())) {
            throw new IllegalArgumentException("Manual research sources belong to different teams");
        }
        completion = new TeamResearchCompletion(queue.registry());
    }

    public ObjectList<ManualResearchSession> sessions() {
        requireOwner();
        return ObjectLists.unmodifiable(new ObjectArrayList<>(sessions.values()));
    }

    @Nullable public ManualResearchSession session(long queueEntryId) { requireOwner(); return sessions.get(queueEntryId); }
    public boolean checkpointPending() { requireOwner(); return pendingCheckpoint != null; }

    /** Restore after restoring the owning queue and research sources. Active work requires takeover. */
    public void restore(Collection<ManualResearchSession> saved) {
        guarded(() -> {
            if (!sessions.isEmpty()) { throw new IllegalStateException("Manual sessions have already been installed"); }
            var restored = new Long2ObjectLinkedOpenHashMap<ManualResearchSession>();
            var ids = new ObjectOpenHashSet<UUID>();
            for (var original : saved) {
                var session = Objects.requireNonNull(original).restored();
                validateSession(session);
                if (!ids.add(session.id()) || restored.putIfAbsent(session.queueKey().queueEntryId(), session) != null) {
                    throw new IllegalArgumentException("Duplicate restored manual session");
                }
            }
            sessions.putAll(restored);
            return null;
        });
    }

    /** Binds the actual admission result; callers must not save an unbound tier-zero admission. */
    public Status begin(TeamResearchQueue.Entry entry, UUID actor, Context context) {
        return guarded(() -> {
            Objects.requireNonNull(entry);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(context);
            if (queue.busy() || team.busy()) { return Status.RECOVERY_PENDING; }
            if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
            if (!context.members().contains(actor) || !context.onlinePlayers().contains(actor)) { return Status.CONTRIBUTOR_UNAVAILABLE; }
            if (!entry.requestedBy().equals(actor) || context.serverTime() < entry.createdAtWorldTime()) {
                throw new IllegalArgumentException("Manual admission actor or timeline changed");
            }
            if (!requireQueued(entry.key()).equals(entry)) { throw new IllegalArgumentException("Manual admission is not the queue's actual entry"); }
            if (sessions.containsKey(entry.key().queueEntryId())) { return Status.ALREADY_PRESENT; }
            materials(entry.key());
            var session = ManualResearchSession.newActive(UUID.randomUUID(), queue.teamId(), entry.key(), actor, context.serverTime());
            put(session);
            persist(ObjectList.of());
            return Status.STARTED;
        });
    }

    /** Same-team takeover preserves all paid work and does not re-run the admission condition. */
    public Status takeover(long queueEntryId, UUID actor, Context context) {
        return guarded(() -> {
            var session = requireSession(queueEntryId);
            validateTime(session, context);
            if (queue.busy() || team.busy()) { return Status.RECOVERY_PENDING; }
            if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
            if (!context.members().contains(actor) || !context.onlinePlayers().contains(actor)) { return Status.CONTRIBUTOR_UNAVAILABLE; }
            if (session.status() != ManualResearchSession.Status.ACTIVE && session.status() != ManualResearchSession.Status.PAUSED) {
                return Status.RECOVERY_PENDING;
            }
            requireQueued(session.queueKey());
            put(session.resume(actor, context.serverTime()));
            persist(ObjectList.of());
            return Status.RESUMED;
        });
    }

    public Status pause(long queueEntryId, Context context) {
        return pause(queueEntryId, context, ManualResearchSession.PauseReason.EXPLICIT);
    }

    /** Pauses an active contributor at any queue position, including an offline non-head session. */
    public Status pause(long queueEntryId, Context context, ManualResearchSession.PauseReason reason) {
        return guarded(() -> {
            Objects.requireNonNull(reason);
            var session = requireSession(queueEntryId);
            validateTime(session, context);
            if (queue.busy() || team.busy()) { return Status.RECOVERY_PENDING; }
            if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
            if (session.status() == ManualResearchSession.Status.PAUSED) { return Status.PAUSED; }
            if (session.status() != ManualResearchSession.Status.ACTIVE) { return Status.RECOVERY_PENDING; }
            return pause(session, reason, context.serverTime());
        });
    }

    /**
     * Advances or recovers the exact queue head. Recovery uses the original material contributor's
     * inventory, even after that player leaves the team; only an uncommitted intent may be aborted.
     * A null/offline inventory is normal and keeps recovery pending.
     */
    public Status advance(long queueEntryId, Context context, @Nullable ManualResearchInventory inventory) {
        return guarded(() -> {
            var terminal = terminalAwaitingResult.remove(queueEntryId);
            return terminal != null ? terminal : advance(requireSession(queueEntryId), context, inventory);
        });
    }

    /** Removes one structurally invalid target after resolving its exact paid material and completion evidence. */
    public Status cancelInvalidated(long queueEntryId, Context context, @Nullable ManualResearchInventory inventory) {
        return guarded(() -> {
            Objects.requireNonNull(context);
            if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
            var terminal = terminalAwaitingResult.remove(queueEntryId);
            if (terminal != null) { return terminal; }
            var session = requireSession(queueEntryId);
            validateTime(session, context);
            if (queue.busy() || team.busy()) { return Status.RECOVERY_PENDING; }
            var materials = materials(session.queueKey());
            materials.validateWork(session.work());
            validateMaterialPlan(session, materials);
            if (isCompletion(session.status())) {
                if (session.status() != ManualResearchSession.Status.QUEUE_HEAD_REMOVED) {
                    requireQueued(session.queueKey());
                    if (!isHead(session.queueKey())) { return Status.COMPLETION_PENDING; }
                }
                return finishCompletion(session, materials);
            }
            requireQueued(session.queueKey());
            if (session.materialIntent() == null) { return removeInvalidated(session, context); }
            if (!isHead(session.queueKey())) { return Status.RECOVERY_PENDING; }
            return recoverMaterial(session, context, inventory, materials, true);
        });
    }

    /** Retry a failed world checkpoint without planning, consuming or propagating anything again. */
    public void retryCheckpoint() { guarded(() -> null); }

    private Status advance(ManualResearchSession session, Context context, @Nullable ManualResearchInventory inventory) {
        validateTime(session, context);
        if (queue.busy() || team.busy()) { return Status.RECOVERY_PENDING; }
        var materials = materials(session.queueKey());
        materials.validateWork(session.work());
        validateMaterialPlan(session, materials);
        if (isCompletion(session.status())) { return finishCompletion(session, materials); }
        requireQueued(session.queueKey());
        if (!isHead(session.queueKey())) { return Status.WAITING_FOR_TURN; }
        if (session.materialIntent() != null) { return recoverMaterial(session, context, inventory, materials); }
        if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
        if (session.status() == ManualResearchSession.Status.PAUSED) { return Status.PAUSED; }
        var contributor = Objects.requireNonNull(session.contributor());
        if (!context.members().contains(contributor)) {
            return pause(session, ManualResearchSession.PauseReason.CONTRIBUTOR_TEAM_CHANGED, context.serverTime());
        }
        if (!context.onlinePlayers().contains(contributor)) {
            return pause(session, ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE, context.serverTime());
        }
        if (context.serverTime() - session.lastActivity() >= INACTIVITY_TIMEOUT) {
            return pause(session, ManualResearchSession.PauseReason.SESSION_TIMEOUT, context.serverTime());
        }
        if (inventory == null) { return Status.CONTRIBUTOR_UNAVAILABLE; }
        requireInventory(inventory, contributor);
        if (inventory.findBySession(session.id()) != null) { throw new IllegalStateException("Active session has an outstanding material receipt"); }
        var plan = materials.plan(session.work());
        if (plan.increments().isEmpty()) { throw new IllegalStateException("Active manual session has already completed its material work"); }
        if (!inventory.hasMaterials(plan.quantities())) { return Status.NO_MATERIALS; }
        var frozen = plan.completesResearch() ? freezeCompletion(session, context) : null;
        if (plan.completesResearch() && frozen == null) { return Status.COMPLETION_PENDING; }
        if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
        var intent = new ManualResearchSession.MaterialIntent(UUID.randomUUID(), contributor, plan.sequence(), plan.increments(), plan.expectedWork());
        session = session.beginMaterial(intent, context.serverTime(), frozen);
        unpreparedOperations.add(intent.operationId());
        put(session);
        persist(ObjectList.of());
        return recoverMaterial(session, context, inventory, materials);
    }

    private Status recoverMaterial(ManualResearchSession session, Context context,
                                   @Nullable ManualResearchInventory inventory, ManualMaterials materials) {
        return recoverMaterial(session, context, inventory, materials, false);
    }

    private Status recoverMaterial(ManualResearchSession session, Context context,
                                   @Nullable ManualResearchInventory inventory, ManualMaterials materials, boolean invalidated) {
        var intent = Objects.requireNonNull(session.materialIntent());
        if (inventory == null || !context.onlinePlayers().contains(intent.contributor())) { return Status.RECOVERY_PENDING; }
        requireInventory(inventory, intent.contributor());
        var receipt = inventory.find(intent.operationId());
        if (!invalidated && session.status() == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING
            && receipt == null && unpreparedOperations.contains(intent.operationId())) {
            receipt = new ManualResearchMaterialReceipt(session.id(), session.teamId(), session.queueKey(), intent,
                inventory.snapshotInventory(), null, ManualResearchMaterialReceipt.Status.PREPARED, context.serverTime(), -1, -1);
            inventory.recordPrepared(receipt);
        }
        if (receipt != null) { unpreparedOperations.remove(intent.operationId()); }
        if (receipt != null) { requireReceipt(session, receipt); }
        if (invalidated && !context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
        boolean committedPersisted = false;
        if (session.status() == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING) {
            // A missing receipt cannot prove that an earlier process never consumed the materials.
            if (receipt == null) {
                if (!invalidated || !unpreparedOperations.contains(intent.operationId())) { return Status.RECOVERY_PENDING; }
                session = session.abortPrepared(intent.operationId(), ManualResearchSession.PauseReason.RESEARCH_INVALIDATED, context.serverTime());
                put(session);
                persist(ObjectList.of());
            } else if (receipt.status() == ManualResearchMaterialReceipt.Status.PREPARED) {
                if (!inventory.matches(receipt.inventoryBefore())) { return Status.RECOVERY_PENDING; }
                if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
                if (invalidated || !context.members().contains(intent.contributor())) {
                    var reason = invalidated ? ManualResearchSession.PauseReason.RESEARCH_INVALIDATED
                        : ManualResearchSession.PauseReason.CONTRIBUTOR_TEAM_CHANGED;
                    session = session.abortPrepared(intent.operationId(), reason, context.serverTime());
                    put(session);
                    persist(ObjectList.of());
                } else {
                    var plan = materials.plan(session.work());
                    if (plan.sequence() != intent.sequence() || !plan.increments().equals(intent.pendingWork())
                        || !plan.expectedWork().equals(intent.expectedWork())) { throw new IllegalStateException("Manual recovery material plan changed"); }
                    requireHead(session.queueKey());
                    if (!inventory.consume(receipt, plan.quantities(), context.serverTime())) { return Status.RECOVERY_PENDING; }
                    committedPersisted = true;
                    requireHead(session.queueKey());
                    receipt = Objects.requireNonNull(inventory.find(intent.operationId()), "Consumed material receipt is missing");
                    requireReceipt(session, receipt);
                }
            }
            if (session.status() == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING) {
                Objects.requireNonNull(receipt);
                if (receipt.status() != ManualResearchMaterialReceipt.Status.COMMITTED) {
                    throw new IllegalStateException("Pending material intent has an incompatible player receipt");
                }
                if (!committedPersisted) { inventory.persistCommitted(intent.operationId()); }
                committedPersisted = true;
                session = session.markMaterialCommitted(intent.operationId());
                put(session);
                persist(ObjectList.of());
            }
        }
        if (session.status() == ManualResearchSession.Status.MATERIAL_ABORT_RECEIPT_PENDING) {
            if (receipt != null && (receipt.status() != ManualResearchMaterialReceipt.Status.PREPARED
                || !inventory.matches(receipt.inventoryBefore()))) { return Status.RECOVERY_PENDING; }
            boolean cancel = invalidated || session.pauseReason() == ManualResearchSession.PauseReason.RESEARCH_INVALIDATED;
            if (cancel && !context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
            inventory.removePrepared(intent.operationId());
            unpreparedOperations.remove(intent.operationId());
            session = session.finishAbort(intent.operationId(), context.serverTime());
            put(session);
            persist(ObjectList.of());
            return cancel ? removeInvalidated(session, context) : Status.PAUSED;
        }
        if (session.status() == ManualResearchSession.Status.MATERIAL_COMMITTED) {
            if (invalidated && receipt == null) { return Status.RECOVERY_PENDING; }
            if (receipt == null || receipt.status() != ManualResearchMaterialReceipt.Status.COMMITTED) {
                throw new IllegalStateException("Committed material intent lost its player receipt");
            }
            if (!inventory.matches(Objects.requireNonNull(receipt.inventoryAfter()))) { return Status.RECOVERY_PENDING; }
            if (!committedPersisted) { inventory.persistCommitted(intent.operationId()); }
            session = session.settleMaterial(intent.operationId(), context.serverTime());
            put(session);
            persist(ObjectList.of());
        }
        if (session.status() != ManualResearchSession.Status.MATERIAL_SETTLED_RECEIPT_PENDING) {
            throw new IllegalStateException("Unsupported manual material recovery state");
        }
        if (!session.work().equals(intent.expectedWork())) { throw new IllegalStateException("Manual settlement changed its promised work"); }
        if (receipt != null) {
            if (receipt.status() == ManualResearchMaterialReceipt.Status.PREPARED) {
                throw new IllegalStateException("Settled material work has an uncommitted player receipt");
            }
            if (!inventory.matches(Objects.requireNonNull(receipt.inventoryAfter()))) { return Status.RECOVERY_PENDING; }
            if (receipt.status() == ManualResearchMaterialReceipt.Status.COMMITTED) {
                inventory.confirmSettlement(intent.operationId(), context.serverTime());
            }
        }
        // Missing is accepted only after the world's settled state durably permits this deletion.
        inventory.removeConfirmed(intent.operationId());
        session = session.finishMaterialReceipt(intent.operationId(), context.serverTime());
        put(session);
        persist(ObjectList.of());
        if (isCompletion(session.status())) { return finishCompletion(session, materials); }
        return invalidated ? removeInvalidated(session, context) : Status.ADVANCED;
    }

    private Status removeInvalidated(ManualResearchSession session, Context context) {
        if (!context.current().getAsBoolean()) { return Status.STALE_CONTEXT; }
        if (queue.busy() || team.busy()) { return Status.RECOVERY_PENDING; }
        queue.removeUnstarted(session.queueKey());
        long entryId = session.queueKey().queueEntryId();
        sessions.remove(entryId);
        compiledMaterials.remove(session.queueKey());
        terminalAwaitingResult.put(entryId, Status.CANCELLED);
        persist(ObjectList.of());
        terminalAwaitingResult.remove(entryId);
        return Status.CANCELLED;
    }

    private Status finishCompletion(ManualResearchSession session, ManualMaterials materials) {
        if (!materials.complete(session.work())) { throw new IllegalStateException("Manual completion contains unpaid material work"); }
        var intent = Objects.requireNonNull(session.completionIntent());
        var members = intent.baselines().keySet();
        if (session.status() != ManualResearchSession.Status.QUEUE_HEAD_REMOVED) { requireHead(session.queueKey()); }
        if (queue.busy()) { return Status.COMPLETION_PENDING; }
        // PN rechecks frozen propagation on every completion recovery state; it never re-delivers rewards.
        var prepared = completion.prepareFrozen(session.queueKey().researchId(), session.queueKey().targetLevel(), members,
            players, team, intent.worldTime(), intent.baselines());
        if (prepared == null) { return Status.COMPLETION_PENDING; }
        try { prepared.commit(); }
        catch (RuntimeException | Error failure) {
            try { prepared.rollback(); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
        prepared.complete();
        if (session.status() == ManualResearchSession.Status.COMPLETION_PENDING) {
            session = session.markCompletionPropagated();
            put(session);
        }
        persist(members);
        if (session.status() == ManualResearchSession.Status.COMPLETION_PROPAGATED) {
            requireHead(session.queueKey());
            if (queue.busy()) { return Status.COMPLETION_PENDING; }
            // On restoration these snapshots came from the same world commit as PROPAGATED.
            queue.removeHead(session.queueKey());
            session = session.markQueueHeadRemoved();
            put(session);
            persist(members);
        }
        if (session.status() != ManualResearchSession.Status.QUEUE_HEAD_REMOVED) {
            throw new IllegalStateException("Unsupported manual completion recovery state");
        }
        if (queued(session.queueKey())) { throw new IllegalStateException("Completed queue entry reappeared"); }
        sessions.remove(session.queueKey().queueEntryId());
        compiledMaterials.remove(session.queueKey());
        terminalAwaitingResult.put(session.queueKey().queueEntryId(), Status.COMPLETED);
        persist(members);
        terminalAwaitingResult.remove(session.queueKey().queueEntryId());
        return Status.COMPLETED;
    }

    @Nullable private ManualResearchSession.CompletionIntent freezeCompletion(ManualResearchSession session, Context context) {
        if (team.busy()) { return null; }
        boolean teamOwned = queue.registry().require(session.queueKey().researchId()).deliveryPolicy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY;
        var baselines = new Object2IntOpenHashMap<UUID>();
        for (var member : context.members()) {
            var player = requirePlayer(member);
            if (player.busy()) { return null; }
            baselines.put(member, teamOwned ? team.completedLevel(session.queueKey().researchId()) : player.completedLevel(session.queueKey().researchId()));
        }
        return new ManualResearchSession.CompletionIntent(context.serverTime(), baselines);
    }

    private Status pause(ManualResearchSession session, ManualResearchSession.PauseReason reason, long time) {
        put(session.pause(reason, time));
        persist(ObjectList.of());
        return Status.PAUSED;
    }

    private ManualMaterials materials(TeamResearchQueue.Key key) {
        var definition = queue.registry().require(key.researchId());
        var stage = definition.stageForLevel(key.targetLevel());
        if (definition.specialOnly() || stage.researchTier() != 0) { throw new IllegalArgumentException("Queued research is not ordinary manual research"); }
        return Objects.requireNonNull(compiledMaterials.computeIfAbsent(key, materialCompiler), "Compiled manual materials");
    }

    private void validateSession(ManualResearchSession session) {
        if (!session.teamId().equals(queue.teamId())) { throw new IllegalArgumentException("Manual session belongs to a different team"); }
        var materials = materials(session.queueKey());
        materials.validateWork(session.work());
        validateMaterialPlan(session, materials);
        if (isCompletion(session.status()) && !materials.complete(session.work())) {
            throw new IllegalArgumentException("Restored completion contains unpaid material work");
        }
        if (session.status() == ManualResearchSession.Status.QUEUE_HEAD_REMOVED) {
            if (queued(session.queueKey())) { throw new IllegalArgumentException("Removed manual queue entry is still present"); }
        } else { requireQueued(session.queueKey()); }
    }

    private static void validateMaterialPlan(ManualResearchSession session, ManualMaterials materials) {
        var intent = session.materialIntent();
        if (intent == null) { return; }
        materials.validateWork(intent.expectedWork());
        var before = new Object2LongOpenHashMap<>(session.work());
        if (session.status() == ManualResearchSession.Status.MATERIAL_SETTLED_RECEIPT_PENDING) {
            for (var entry : intent.pendingWork().object2LongEntrySet()) { before.addTo(entry.getKey(), -entry.getLongValue()); }
        }
        var planned = materials.plan(before);
        boolean frozenCompletion = session.completionIntent() != null;
        if (planned.sequence() != intent.sequence() || !planned.increments().equals(intent.pendingWork())
            || !planned.expectedWork().equals(intent.expectedWork())
            || session.status() != ManualResearchSession.Status.MATERIAL_ABORT_RECEIPT_PENDING
                && frozenCompletion != planned.completesResearch()) {
            throw new IllegalArgumentException("Saved manual intent differs from the actual material step");
        }
    }

    private static boolean isCompletion(ManualResearchSession.Status status) {
        return status == ManualResearchSession.Status.COMPLETION_PENDING || status == ManualResearchSession.Status.COMPLETION_PROPAGATED
            || status == ManualResearchSession.Status.QUEUE_HEAD_REMOVED;
    }

    private void put(ManualResearchSession session) { sessions.put(session.queueKey().queueEntryId(), session); }

    private ManualResearchSession requireSession(long entryId) {
        var session = sessions.get(entryId);
        if (session == null) { throw new IllegalArgumentException("Unknown manual queue entry: " + entryId); }
        return session;
    }

    private PlayerResearchState requirePlayer(UUID id) {
        var player = players.get(id);
        if (player == null || !player.playerUuid().equals(id)) { throw new IllegalArgumentException("Missing authoritative player research state: " + id); }
        return player;
    }

    private boolean queued(TeamResearchQueue.Key key) {
        for (var entry : queue.queueSnapshot()) { if (entry.key().equals(key)) { return true; } }
        return false;
    }

    private TeamResearchQueue.Entry requireQueued(TeamResearchQueue.Key key) {
        for (var entry : queue.queueSnapshot()) { if (entry.key().equals(key)) { return entry; } }
        throw new IllegalStateException("Manual session lost its exact queue entry");
    }
    private boolean isHead(TeamResearchQueue.Key key) { return queue.activeHead().map(entry -> entry.key().equals(key)).orElse(false); }
    private void requireHead(TeamResearchQueue.Key key) { if (!isHead(key)) { throw new IllegalStateException("Manual completion lost its exact queue head"); } }
    private static void requireInventory(ManualResearchInventory inventory, UUID player) {
        if (!inventory.playerId().equals(player)) { throw new IllegalArgumentException("Manual material inventory belongs to another contributor"); }
    }

    private static void requireReceipt(ManualResearchSession session, ManualResearchMaterialReceipt receipt) {
        if (!receipt.sessionId().equals(session.id()) || !receipt.teamId().equals(session.teamId())
            || !receipt.queueKey().equals(session.queueKey()) || !receipt.intent().equals(session.materialIntent())) {
            throw new IllegalStateException("Manual material receipt does not match the exact world intent");
        }
    }

    private static void validateTime(ManualResearchSession session, Context context) {
        if (context.serverTime() < session.lastActivity()) { throw new IllegalArgumentException("Manual research server time moved backwards"); }
    }

    private void persist(Collection<UUID> affectedPlayers) {
        if (pendingCheckpoint != null) { throw new IllegalStateException("A previous world checkpoint still requires recovery"); }
        var snapshots = new ObjectArrayList<PlayerResearchState.Snapshot>();
        for (var id : affectedPlayers) { snapshots.add(requirePlayer(id).snapshot()); }
        pendingCheckpoint = new CheckpointState(sessions(), queue.snapshot(), team.snapshot(), snapshots);
        flushCheckpoint();
    }

    private void flushCheckpoint() {
        if (pendingCheckpoint == null) { return; }
        requireCheckpointOwnership(pendingCheckpoint);
        checkpoint.persist(pendingCheckpoint);
        requireCheckpointOwnership(pendingCheckpoint);
        pendingCheckpoint = null;
    }

    private void requireCheckpointOwnership(CheckpointState expected) {
        if (queue.busy() || team.busy() || !expected.sessions().equals(sessions())
            || !expected.queue().equals(queue.snapshot()) || !expected.team().equals(team.snapshot())) {
            throw new IllegalStateException("Pending manual checkpoint lost exclusive ownership of its world state");
        }
        for (var player : expected.players()) {
            var current = requirePlayer(player.playerUuid());
            if (current.busy() || !current.snapshot().equals(player)) {
                throw new IllegalStateException("Pending manual checkpoint lost exclusive ownership of a player source");
            }
        }
    }

    private <T> T guarded(Supplier<T> action) {
        requireOwner();
        if (executing) { throw new IllegalStateException("Manual research execution cannot reenter"); }
        executing = true;
        try { flushCheckpoint(); return action.get(); }
        finally { executing = false; }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Manual research requires its owner thread"); }
    }

    public record Context(long serverTime, Set<UUID> members, Set<UUID> onlinePlayers, BooleanSupplier current) {
        public Context {
            if (serverTime < 0) { throw new IllegalArgumentException("Negative server time"); }
            members = ObjectSets.unmodifiable(new ObjectOpenHashSet<>(members));
            onlinePlayers = ObjectSets.unmodifiable(new ObjectOpenHashSet<>(onlinePlayers));
            if (members.contains(null) || onlinePlayers.contains(null)) { throw new IllegalArgumentException("Null player identity"); }
            Objects.requireNonNull(current);
        }
    }

    /** Players are a merge set of affected sources, not a replacement for the world's player table. */
    public record CheckpointState(ObjectList<ManualResearchSession> sessions, TeamResearchQueue.Snapshot queue,
                                  TeamOwnedResearch.Snapshot team, ObjectList<PlayerResearchState.Snapshot> players) {
        public CheckpointState {
            sessions = ObjectLists.unmodifiable(new ObjectArrayList<>(sessions));
            players = ObjectLists.unmodifiable(new ObjectArrayList<>(players));
            Objects.requireNonNull(queue);
            Objects.requireNonNull(team);
        }
    }

    /** Persist all supplied world-owned records together; throwing retains an exact retry checkpoint. */
    @FunctionalInterface public interface Checkpoint { void persist(CheckpointState state); }

    public enum Status {
        STARTED, ALREADY_PRESENT, RESUMED, ADVANCED, COMPLETED, CANCELLED, PAUSED, WAITING_FOR_TURN,
        NO_MATERIALS, CONTRIBUTOR_UNAVAILABLE, STALE_CONTEXT, RECOVERY_PENDING, COMPLETION_PENDING
    }
}
