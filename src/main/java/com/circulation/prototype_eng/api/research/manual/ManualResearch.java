package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchCompletion;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.research.manual.ManualMaterials;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Owner-thread manual research execution. Materials are paid separately; target progress retains time and remaining amounts.
 * Player receipts and world state are saved separately. A failed checkpoint retains ownership until saving succeeds.
 */
@SuppressWarnings("unused") // Public library entry point.
public final class ManualResearch {
    public static final long INACTIVITY_TIMEOUT = 1_200;
    private final Thread owner = Thread.currentThread();
    private final TeamResearchQueue queue;
    private final TeamOwnedResearch team;
    private final Map<UUID, PlayerResearchState> players;
    private final Checkpoint checkpoint;
    private final TeamResearchCompletion completion;
    private final Object2ObjectOpenHashMap<TeamResearchQueue.Key, ManualMaterials> compiledMaterials = new Object2ObjectOpenHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<ManualResearchSession> sessions = new Long2ObjectLinkedOpenHashMap<>();
    private @Nullable ObjectList<ManualResearchSession> sessionSnapshot;
    private final Long2ObjectLinkedOpenHashMap<Status> terminalAwaitingResult = new Long2ObjectLinkedOpenHashMap<>();
    // Tracks operations whose consume call has not begun in this process. Restart recovery requires player receipts.
    private final ObjectOpenHashSet<UUID> unpreparedOperations = new ObjectOpenHashSet<>();
    @Nullable
    private CheckpointState pendingCheckpoint;
    private boolean pendingProgressCheckpoint;
    private boolean executing;

    public ManualResearch(TeamResearchQueue queue, TeamOwnedResearch team,
                          Map<UUID, PlayerResearchState> players, Checkpoint checkpoint) {
        this.queue = queue;
        this.team = team;
        this.players = players;
        this.checkpoint = checkpoint;
        if (queue.registry() != team.registry() || !queue.teamId().equals(team.teamId())) {
            throw new IllegalArgumentException("Manual research sources belong to different teams");
        }
        completion = new TeamResearchCompletion(queue.registry());
    }

    @SuppressWarnings("DataFlowIssue")
    private void validateMaterialPlan(ManualResearchSession session, ManualMaterials materials) {
        var intent = session.materialIntent();
        if (intent == null) return;
        materials.validateWork(intent.expectedWork());
        var work = queue.progress(session.queueKey().researchId(), session.queueKey().targetLevel());
        long elapsed = work.snapshot().elapsedTicks();
        if (intent.elapsedTicks() < elapsed || intent.elapsedTicks() - elapsed > 1 || intent.elapsedTicks() > work.stage().durationTicks())
            throw new IllegalArgumentException("Manual material time differs from saved progress");
        boolean completes = intent.elapsedTicks() == work.stage().durationTicks() && materials.complete(intent.expectedWork());
        boolean hasCompletion = session.completionIntent() != null;
        if (session.status() != ManualResearchSession.Status.MATERIAL_ABORT_RECEIPT_PENDING && hasCompletion != completes)
            throw new IllegalArgumentException("Manual completion differs from its paid target");
    }

    private static boolean isCompletion(ManualResearchSession.Status status) {
        return status == ManualResearchSession.Status.COMPLETION_PENDING || status == ManualResearchSession.Status.COMPLETION_PROPAGATED
            || status == ManualResearchSession.Status.QUEUE_HEAD_REMOVED;
    }

    private static void requireInventory(ManualResearchInventory inventory, UUID player) {
        if (!inventory.playerId().equals(player)) {
            throw new IllegalArgumentException("Manual material inventory belongs to another contributor");
        }
    }

    private static void requireReceipt(ManualResearchSession session, ManualResearchMaterialReceipt receipt) {
        if (!receipt.sessionId().equals(session.id()) || !receipt.teamId().equals(session.teamId())
            || !receipt.queueKey().equals(session.queueKey()) || !receipt.intent().equals(session.materialIntent())) {
            throw new IllegalStateException("Manual material receipt does not match the exact world intent");
        }
    }

    private static void validateTime(ManualResearchSession session, Context context) {
        if (context.serverTime() < session.lastActivity()) {
            throw new IllegalArgumentException("Manual research server time moved backwards");
        }
    }

    /**
     * Returns a stable session list borrowed read-only until the next session change.
     */
    public ObjectList<ManualResearchSession> sessions() {
        requireOwner();
        if (sessionSnapshot == null) sessionSnapshot = new ObjectArrayList<>(sessions.values());
        return sessionSnapshot;
    }

    /**
     * Frozen recipients retain their paid completion baseline until the original session settles.
     */
    public boolean blocksSourceReduction(ResourceLocation researchId, @Nullable UUID player) {
        requireOwner();
        for (var session : sessions.values())
            if (session.queueKey().researchId().equals(researchId) && session.completionIntent() != null
                && (player == null || session.completionIntent().baselines().containsKey(player))) return true;
        return false;
    }

    @Nullable
    public ManualResearchSession session(long queueEntryId) {
        requireOwner();
        return sessions.get(queueEntryId);
    }

    public boolean checkpointPending() {
        requireOwner();
        return pendingCheckpoint != null;
    }

    /**
     * Restore after restoring the owning queue and research sources. Active work requires takeover.
     */
    public void restore(Collection<ManualResearchSession> saved) {
        guarded(() -> {
            if (!sessions.isEmpty()) {
                throw new IllegalStateException("Manual sessions have already been installed");
            }
            var restored = new Long2ObjectLinkedOpenHashMap<ManualResearchSession>(saved.size());
            var ids = new ObjectOpenHashSet<UUID>(saved.size());
            for (var original : saved) {
                var session = original.restored();
                validateSession(session);
                if (!ids.add(session.id()) || restored.putIfAbsent(session.queueKey().queueEntryId(), session) != null) {
                    throw new IllegalArgumentException("Duplicate restored manual session");
                }
            }
            sessions.putAll(restored);
            sessionSnapshot = null;
            return null;
        });
    }

    /**
     * Binds the actual admission result; callers must not save an unbound tier-zero admission.
     */
    @SuppressWarnings("DataFlowIssue")
    public Status begin(TeamResearchQueue.Entry entry, UUID actor, Context context) {
        return guarded(() -> {
            if (queue.busy() || team.busy()) {
                return Status.RECOVERY_PENDING;
            }
            if (!context.current().getAsBoolean()) {
                return Status.STALE_CONTEXT;
            }
            if (!context.members().contains(actor) || !context.onlinePlayers().contains(actor)) {
                return Status.CONTRIBUTOR_UNAVAILABLE;
            }
            if (!entry.requestedBy().equals(actor) || context.serverTime() < entry.createdAtWorldTime()) {
                throw new IllegalArgumentException("Manual admission actor or timeline changed");
            }
            if (!requireQueued(entry.key()).equals(entry)) {
                throw new IllegalArgumentException("Manual admission is not the queue's actual entry");
            }
            if (sessions.containsKey(entry.key().queueEntryId())) {
                return Status.ALREADY_PRESENT;
            }
            var materials = materials(entry.key());
            var progress = queue.progress(entry.key().researchId(), entry.key().targetLevel());
            var session = ManualResearchSession.newActive(UUID.randomUUID(), queue.teamId(), entry.key(), actor, context.serverTime())
                                               .withWork(materials.workForRemaining(progress.snapshot().remainingMaterials()));
            put(session);
            persist(ObjectList.of());
            return Status.STARTED;
        });
    }

    /**
     * Same-team takeover preserves all paid work and does not re-run the admission condition.
     */
    public Status takeover(long queueEntryId, UUID actor, Context context) {
        return guarded(() -> {
            var session = requireSession(queueEntryId);
            validateTime(session, context);
            if (queue.busy() || team.busy()) {
                return Status.RECOVERY_PENDING;
            }
            if (!context.current().getAsBoolean()) {
                return Status.STALE_CONTEXT;
            }
            if (!context.members().contains(actor) || !context.onlinePlayers().contains(actor)) {
                return Status.CONTRIBUTOR_UNAVAILABLE;
            }
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

    /**
     * Pauses an active contributor at any queue position, including an offline non-head session.
     */
    public Status pause(long queueEntryId, Context context, ManualResearchSession.PauseReason reason) {
        return guarded(() -> {
            var session = requireSession(queueEntryId);
            validateTime(session, context);
            if (queue.busy() || team.busy()) {
                return Status.RECOVERY_PENDING;
            }
            if (!context.current().getAsBoolean()) {
                return Status.STALE_CONTEXT;
            }
            if (session.status() == ManualResearchSession.Status.PAUSED) {
                return Status.PAUSED;
            }
            if (session.status() != ManualResearchSession.Status.ACTIVE) {
                return Status.RECOVERY_PENDING;
            }
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

    /**
     * Removes one structurally invalid target after resolving its exact paid material and completion evidence.
     */
    public Status cancelInvalidated(long queueEntryId, Context context, @Nullable ManualResearchInventory inventory) {
        return guarded(() -> {
            if (!context.current().getAsBoolean()) {
                return Status.STALE_CONTEXT;
            }
            var terminal = terminalAwaitingResult.remove(queueEntryId);
            if (terminal != null) {
                return terminal;
            }
            var session = requireSession(queueEntryId);
            validateTime(session, context);
            if (queue.busy() || team.busy()) {
                return Status.RECOVERY_PENDING;
            }
            var materials = materials(session.queueKey());
            materials.validateWork(session.work());
            validateMaterialPlan(session, materials);
            if (isCompletion(session.status())) {
                if (session.status() != ManualResearchSession.Status.QUEUE_HEAD_REMOVED) {
                    requireQueued(session.queueKey());
                    if (!isHead(session.queueKey())) {
                        return Status.COMPLETION_PENDING;
                    }
                }
                return finishCompletion(session, materials);
            }
            requireQueued(session.queueKey());
            if (session.materialIntent() == null) {
                return removeInvalidated(session, context);
            }
            if (!isHead(session.queueKey())) {
                return Status.RECOVERY_PENDING;
            }
            return recoverMaterial(session, context, inventory, materials, true);
        });
    }

    /**
     * Retry a failed world checkpoint without planning, consuming or propagating anything again.
     */
    public void retryCheckpoint() {
        guarded(() -> null);
    }

    @SuppressWarnings("DataFlowIssue")
    private Status advance(ManualResearchSession session, Context context, @Nullable ManualResearchInventory inventory) {
        validateTime(session, context);
        if (queue.busy() || team.busy()) {
            return Status.RECOVERY_PENDING;
        }
        var materials = materials(session.queueKey());
        materials.validateWork(session.work());
        validateMaterialPlan(session, materials);
        if (isCompletion(session.status())) {
            return finishCompletion(session, materials);
        }
        requireQueued(session.queueKey());
        if (!isHead(session.queueKey())) {
            return Status.WAITING_FOR_TURN;
        }
        if (session.materialIntent() != null) {
            return recoverMaterial(session, context, inventory, materials);
        }
        if (!context.current().getAsBoolean()) {
            return Status.STALE_CONTEXT;
        }
        if (session.status() == ManualResearchSession.Status.PAUSED) {
            return Status.PAUSED;
        }
        var contributor = session.contributor();
        if (!context.members().contains(contributor)) {
            return pause(session, ManualResearchSession.PauseReason.CONTRIBUTOR_TEAM_CHANGED, context.serverTime());
        }
        if (!context.onlinePlayers().contains(contributor)) {
            return pause(session, ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE, context.serverTime());
        }
        if (context.serverTime() - session.lastActivity() >= INACTIVITY_TIMEOUT) {
            return pause(session, ManualResearchSession.PauseReason.SESSION_TIMEOUT, context.serverTime());
        }
        if (inventory == null) {
            return Status.CONTRIBUTOR_UNAVAILABLE;
        }
        requireInventory(inventory, contributor);
        if (inventory.findBySession(session.id()) != null) {
            throw new IllegalStateException("Active session has an outstanding material receipt");
        }
        if (queue.advancedAt(context.serverTime())) return Status.WAITING_FOR_TURN;
        var progress = queue.activeProgress();
        var step = progress.plan(List.of(new ResearchProgress.StationRate(contributor, Reference2DoubleMaps.emptyMap())),
            Reference2DoubleMaps.emptyMap(), inventory.availableMaterials(progress.snapshot().remainingMaterials()));
        if (step == null) return Status.NO_MATERIALS;
        var plan = materials.plan(session.work(), step.payments(), step.complete(), context.serverTime());
        var frozen = plan.completesResearch() ? freezeCompletion(session, context) : null;
        if (plan.completesResearch() && frozen == null) return Status.COMPLETION_PENDING;
        if (!context.current().getAsBoolean()) return Status.STALE_CONTEXT;
        if (plan.increments().isEmpty()) {
            queue.recordManualProgress(session.queueKey(), step.after().elapsedTicks(), step.after().remainingMaterials(), context.serverTime());
            session = session.resume(contributor, context.serverTime());
            if (frozen != null) session = session.beginCompletion(frozen);
            put(session);
            if (frozen == null) defer();
            else persist(ObjectList.of());
            return frozen == null ? Status.ADVANCED : finishCompletion(session, materials);
        }
        var intent = new ManualResearchSession.MaterialIntent(UUID.randomUUID(), contributor, plan.sequence(),
            plan.increments(), plan.expectedWork(), step.after().elapsedTicks(), context.serverTime());
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

    @SuppressWarnings("DataFlowIssue")
    private Status recoverMaterial(ManualResearchSession session, Context context,
                                   @Nullable ManualResearchInventory inventory, ManualMaterials materials, boolean invalidated) {
        var intent = session.materialIntent();
        if (inventory == null || !context.onlinePlayers().contains(intent.contributor())) {
            return Status.RECOVERY_PENDING;
        }
        requireInventory(inventory, intent.contributor());
        var receipt = inventory.find(intent.operationId());
        if (!invalidated && session.status() == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING
            && receipt == null && unpreparedOperations.contains(intent.operationId())) {
            receipt = new ManualResearchMaterialReceipt(session.id(), session.teamId(), session.queueKey(), intent,
                inventory.snapshotInventory(), null, ManualResearchMaterialReceipt.Status.PREPARED, context.serverTime(), -1, -1);
            inventory.recordPrepared(receipt);
        }
        if (receipt != null) {
            unpreparedOperations.remove(intent.operationId());
            requireReceipt(session, receipt);
        }
        if (invalidated && !context.current().getAsBoolean()) {
            return Status.STALE_CONTEXT;
        }
        boolean committedPersisted = false;
        if (session.status() == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING) {
            // A missing receipt cannot prove that an earlier process never consumed the materials.
            if (receipt == null) {
                if (!invalidated || !unpreparedOperations.contains(intent.operationId())) {
                    return Status.RECOVERY_PENDING;
                }
                session = session.abortPrepared(intent.operationId(), ManualResearchSession.PauseReason.RESEARCH_INVALIDATED, context.serverTime());
                put(session);
                persist(ObjectList.of());
            } else if (receipt.status() == ManualResearchMaterialReceipt.Status.PREPARED) {
                if (!inventory.matches(receipt.inventoryBefore())) {
                    return Status.RECOVERY_PENDING;
                }
                if (!context.current().getAsBoolean()) {
                    return Status.STALE_CONTEXT;
                }
                if (invalidated || !context.members().contains(intent.contributor())) {
                    var reason = invalidated ? ManualResearchSession.PauseReason.RESEARCH_INVALIDATED
                        : ManualResearchSession.PauseReason.CONTRIBUTOR_TEAM_CHANGED;
                    session = session.abortPrepared(intent.operationId(), reason, context.serverTime());
                    put(session);
                    persist(ObjectList.of());
                } else {
                    requireHead(session.queueKey());
                    if (!inventory.consume(receipt, materials.quantities(intent.pendingWork()), context.serverTime())) {
                        return Status.RECOVERY_PENDING;
                    }
                    committedPersisted = true;
                    requireHead(session.queueKey());
                    receipt = inventory.find(intent.operationId());
                    requireReceipt(session, receipt);
                }
            }
            if (session.status() == ManualResearchSession.Status.MATERIAL_COMMIT_PENDING) {
                if (receipt.status() != ManualResearchMaterialReceipt.Status.COMMITTED) {
                    throw new IllegalStateException("Pending material intent has an incompatible player receipt");
                }
                if (!committedPersisted) {
                    inventory.persistCommitted(intent.operationId());
                }
                committedPersisted = true;
                session = session.markMaterialCommitted(intent.operationId());
                put(session);
                persist(ObjectList.of());
            }
        }
        if (session.status() == ManualResearchSession.Status.MATERIAL_ABORT_RECEIPT_PENDING) {
            if (receipt != null && (receipt.status() != ManualResearchMaterialReceipt.Status.PREPARED
                || !inventory.matches(receipt.inventoryBefore()))) {
                return Status.RECOVERY_PENDING;
            }
            boolean cancel = invalidated || session.pauseReason() == ManualResearchSession.PauseReason.RESEARCH_INVALIDATED;
            if (cancel && !context.current().getAsBoolean()) {
                return Status.STALE_CONTEXT;
            }
            inventory.removePrepared(intent.operationId());
            unpreparedOperations.remove(intent.operationId());
            session = session.finishAbort(intent.operationId(), context.serverTime());
            put(session);
            persist(ObjectList.of());
            return cancel ? removeInvalidated(session, context) : Status.PAUSED;
        }
        if (session.status() == ManualResearchSession.Status.MATERIAL_COMMITTED) {
            if (invalidated && receipt == null) {
                return Status.RECOVERY_PENDING;
            }
            if (receipt == null || receipt.status() != ManualResearchMaterialReceipt.Status.COMMITTED) {
                throw new IllegalStateException("Committed material intent lost its player receipt");
            }
            if (!inventory.matches(receipt.inventoryAfter())) {
                return Status.RECOVERY_PENDING;
            }
            if (!committedPersisted) {
                inventory.persistCommitted(intent.operationId());
            }
            session = session.settleMaterial(intent.operationId(), context.serverTime());
            queue.recordManualProgress(session.queueKey(), intent.elapsedTicks(), materials.remaining(session.work()), context.serverTime());
            put(session);
            persist(ObjectList.of());
        }
        if (session.status() != ManualResearchSession.Status.MATERIAL_SETTLED_RECEIPT_PENDING) {
            throw new IllegalStateException("Unsupported manual material recovery state");
        }
        if (!session.work().equals(intent.expectedWork())) {
            throw new IllegalStateException("Manual settlement changed its promised work");
        }
        if (receipt != null) {
            if (receipt.status() == ManualResearchMaterialReceipt.Status.PREPARED) {
                throw new IllegalStateException("Settled material work has an uncommitted player receipt");
            }
            if (!inventory.matches(receipt.inventoryAfter())) {
                return Status.RECOVERY_PENDING;
            }
            if (receipt.status() == ManualResearchMaterialReceipt.Status.COMMITTED) {
                inventory.confirmSettlement(intent.operationId(), context.serverTime());
            }
        }

        inventory.removeConfirmed(intent.operationId());
        session = session.finishMaterialReceipt(intent.operationId(), context.serverTime());
        put(session);
        persist(ObjectList.of());
        if (isCompletion(session.status())) {
            return finishCompletion(session, materials);
        }
        return invalidated ? removeInvalidated(session, context) : Status.ADVANCED;
    }

    private Status removeInvalidated(ManualResearchSession session, Context context) {
        if (!context.current().getAsBoolean()) {
            return Status.STALE_CONTEXT;
        }
        if (queue.busy() || team.busy()) {
            return Status.RECOVERY_PENDING;
        }
        queue.removeUnstarted(session.queueKey());
        long entryId = session.queueKey().queueEntryId();
        sessions.remove(entryId);
        sessionSnapshot = null;
        compiledMaterials.remove(session.queueKey());
        terminalAwaitingResult.put(entryId, Status.CANCELLED);
        persist(ObjectList.of());
        terminalAwaitingResult.remove(entryId);
        return Status.CANCELLED;
    }

    @SuppressWarnings("DataFlowIssue")
    private Status finishCompletion(ManualResearchSession session, ManualMaterials materials) {
        if (!materials.complete(session.work())) {
            throw new IllegalStateException("Manual completion contains unpaid material work");
        }
        var intent = session.completionIntent();
        var members = intent.baselines().keySet();
        if (session.status() != ManualResearchSession.Status.QUEUE_HEAD_REMOVED) {
            requireHead(session.queueKey());
        }
        if (queue.busy()) {
            return Status.COMPLETION_PENDING;
        }

        var prepared = completion.prepareFrozen(session.queueKey().researchId(), session.queueKey().targetLevel(), members,
            players, team, intent.worldTime(), intent.baselines());
        if (prepared == null) {
            return Status.COMPLETION_PENDING;
        }
        try {
            prepared.commit();
        } catch (RuntimeException | Error failure) {
            try {
                prepared.rollback();
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
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
            if (queue.busy()) {
                return Status.COMPLETION_PENDING;
            }

            queue.completeHead(session.queueKey());
            session = session.markQueueHeadRemoved();
            put(session);
            persist(members);
        }
        if (session.status() != ManualResearchSession.Status.QUEUE_HEAD_REMOVED) {
            throw new IllegalStateException("Unsupported manual completion recovery state");
        }
        if (queued(session.queueKey())) {
            throw new IllegalStateException("Completed queue entry reappeared");
        }
        sessions.remove(session.queueKey().queueEntryId());
        sessionSnapshot = null;
        compiledMaterials.remove(session.queueKey());
        terminalAwaitingResult.put(session.queueKey().queueEntryId(), Status.COMPLETED);
        persist(members);
        terminalAwaitingResult.remove(session.queueKey().queueEntryId());
        return Status.COMPLETED;
    }

    @Nullable
    private ManualResearchSession.CompletionIntent freezeCompletion(ManualResearchSession session, Context context) {
        if (team.busy()) {
            return null;
        }
        boolean teamOwned = queue.registry().require(session.queueKey().researchId()).deliveryPolicy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY;
        var baselines = new Object2IntOpenHashMap<UUID>(context.members().size());
        baselines.defaultReturnValue(-1);
        for (var member : context.members()) {
            var player = requirePlayer(member);
            if (player.busy()) {
                return null;
            }
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
        if (definition.specialOnly() || stage.researchTier() != 0) {
            throw new IllegalArgumentException("Queued research is not ordinary manual research");
        }
        var materials = compiledMaterials.get(key);
        if (materials == null) {
            materials = ManualMaterials.from(stage.materialRequirements());
            compiledMaterials.put(key, materials);
        }
        return materials;
    }

    private void validateSession(ManualResearchSession session) {
        if (!session.teamId().equals(queue.teamId())) {
            throw new IllegalArgumentException("Manual session belongs to a different team");
        }
        var materials = materials(session.queueKey());
        materials.validateWork(session.work());
        validateMaterialPlan(session, materials);
        if (isCompletion(session.status()) && !materials.complete(session.work())) {
            throw new IllegalArgumentException("Restored completion contains unpaid material work");
        }
        if (session.status() == ManualResearchSession.Status.QUEUE_HEAD_REMOVED) {
            if (queued(session.queueKey())) {
                throw new IllegalArgumentException("Removed manual queue entry is still present");
            }
        } else {
            requireQueued(session.queueKey());
        }
    }

    private void put(ManualResearchSession session) {
        sessions.put(session.queueKey().queueEntryId(), session);
        sessionSnapshot = null;
    }

    private ManualResearchSession requireSession(long entryId) {
        var session = sessions.get(entryId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown manual queue entry: " + entryId);
        }
        return session;
    }

    private PlayerResearchState requirePlayer(UUID id) {
        var player = players.get(id);
        if (player == null || !player.playerUuid().equals(id)) {
            throw new IllegalArgumentException("Missing authoritative player research state: " + id);
        }
        return player;
    }

    private boolean queued(TeamResearchQueue.Key key) {
        var elements = queue.queueSnapshot();
        for (int index = 0; index < elements.size(); index++) {
            var entry = elements.get(index);
            if (entry.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private TeamResearchQueue.Entry requireQueued(TeamResearchQueue.Key key) {
        var elements2 = queue.queueSnapshot();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var entry = elements2.get(index2);
            if (entry.key().equals(key)) {
                return entry;
            }
        }
        throw new IllegalStateException("Manual session lost its exact queue entry");
    }

    private boolean isHead(TeamResearchQueue.Key key) {
        var head = queue.activeHead();
        return head != null && head.key().equals(key);
    }

    private void requireHead(TeamResearchQueue.Key key) {
        if (!isHead(key)) {
            throw new IllegalStateException("Manual completion lost its exact queue head");
        }
    }

    private void persist(Collection<UUID> affectedPlayers) {
        persist(affectedPlayers, false);
    }

    private void defer() {
        persist(ObjectList.of(), true);
    }

    private void persist(Collection<UUID> affectedPlayers, boolean progressOnly) {
        if (pendingCheckpoint != null) {
            throw new IllegalStateException("A previous world checkpoint still requires recovery");
        }
        var snapshots = new ObjectArrayList<PlayerResearchState.Snapshot>(affectedPlayers.size());
        for (var id : affectedPlayers) {
            snapshots.add(requirePlayer(id).snapshot());
        }
        pendingCheckpoint = new CheckpointState(sessions(), queue.snapshot(), team.snapshot(), snapshots);
        pendingProgressCheckpoint = progressOnly;
        flushCheckpoint();
    }

    private void flushCheckpoint() {
        if (pendingCheckpoint == null) {
            return;
        }
        requireCheckpointOwnership(pendingCheckpoint);
        if (pendingProgressCheckpoint) checkpoint.progressed(pendingCheckpoint);
        else checkpoint.persist(pendingCheckpoint);
        requireCheckpointOwnership(pendingCheckpoint);
        pendingCheckpoint = null;
        pendingProgressCheckpoint = false;
    }

    private void requireCheckpointOwnership(CheckpointState expected) {
        if (queue.busy() || team.busy() || !expected.sessions().equals(sessions())
            || !expected.queue().equals(queue.snapshot()) || !expected.team().equals(team.snapshot())) {
            throw new IllegalStateException("Pending manual checkpoint lost exclusive ownership of its world state");
        }
        var elements3 = expected.players();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var player = elements3.get(index3);
            var current = requirePlayer(player.playerUuid());
            if (current.busy() || !current.snapshot().equals(player)) {
                throw new IllegalStateException("Pending manual checkpoint lost exclusive ownership of a player source");
            }
        }
    }

    private <T> T guarded(Supplier<T> action) {
        requireOwner();
        if (executing) {
            throw new IllegalStateException("Manual research execution cannot reenter");
        }
        executing = true;
        try {
            flushCheckpoint();
            return action.get();
        } finally {
            executing = false;
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Manual research requires its owner thread");
        }
    }

    public enum Status {
        STARTED, ALREADY_PRESENT, RESUMED, ADVANCED, COMPLETED, CANCELLED, PAUSED, WAITING_FOR_TURN,
        NO_MATERIALS, CONTRIBUTOR_UNAVAILABLE, STALE_CONTEXT, RECOVERY_PENDING, COMPLETION_PENDING
    }

    /**
     * Persist all supplied world-owned records together; throwing retains an exact retry checkpoint.
     */
    @FunctionalInterface
    public interface Checkpoint {
        void persist(CheckpointState state);

        /**
         * Pure elapsed work can wait for the owning world's normal save. Standalone callers retain durable checkpoints by default.
         */
        default void progressed(CheckpointState state) {
            persist(state);
        }
    }

    /**
     * Membership sets are borrowed read-only for the lifetime of this operation.
     */
    public record Context(long serverTime, Set<UUID> members, Set<UUID> onlinePlayers, BooleanSupplier current) {
    }

    /**
     * Players are a merge set of affected sources, not a replacement for the world's player table.
     * Lists are borrowed immutable snapshots and must remain unchanged until this checkpoint is
     * discarded, including while a failed save retains it for retry. Consumers must not mutate them.
     */
    public record CheckpointState(ObjectList<ManualResearchSession> sessions, TeamResearchQueue.Snapshot queue,
                                  TeamOwnedResearch.Snapshot team, ObjectList<PlayerResearchState.Snapshot> players) {
    }
}
