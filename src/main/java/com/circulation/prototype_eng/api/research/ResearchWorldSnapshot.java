package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadReceipt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.station.ResearchStationWork;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * One immutable world checkpoint, including every offline player source needed for recovery.
 * Lists are completed snapshots borrowed unchanged for this checkpoint's lifetime, including
 * pending save retries. Producers finish populating them before handoff; consumers never mutate them.
 */
public record ResearchWorldSnapshot(long serverTime, ObjectList<PlayerResearchState.Snapshot> players,
                                    ObjectList<Team> teams,
                                    ObjectList<ResearchDownloadOperation> downloads,
                                    ObjectList<ResearchDownloadReceipt> downloadReceipts,
                                    ObjectList<ResearchDiskOperation> diskImports) {
    public ResearchWorldSnapshot(long serverTime, ObjectList<PlayerResearchState.Snapshot> players, ObjectList<Team> teams) {
        this(serverTime, players, teams, ObjectList.of(), ObjectList.of(), ObjectList.of());
    }

    /**
     * Host checkpoint without disk obligations.
     */
    public ResearchWorldSnapshot(long serverTime, ObjectList<PlayerResearchState.Snapshot> players, ObjectList<Team> teams,
                                 ObjectList<ResearchDownloadOperation> downloads, ObjectList<ResearchDownloadReceipt> downloadReceipts) {
        this(serverTime, players, teams, downloads, downloadReceipts, ObjectList.of());
    }

    public ResearchWorldSnapshot {
        if (serverTime < 0) {
            throw new IllegalArgumentException("Negative research world time");
        }
        var playerIds = new ObjectOpenHashSet<UUID>(players.size());
        for (var player : players) {
            if (!playerIds.add(player.playerUuid())) {
                throw new IllegalArgumentException("Duplicate research world player");
            }
            for (var claim : player.rewardClaims()) {
                if (claim.eligibleAtWorldTime() > serverTime || claim.claimingAtWorldTime().orElse(0) > serverTime
                    || claim.claimedAtWorldTime().orElse(0) > serverTime) {
                    throw new IllegalArgumentException("Reward event exceeds research world time");
                }
            }
        }
        var teamIds = new ObjectOpenHashSet<String>(teams.size());
        var sessionIds = new ObjectOpenHashSet<UUID>();
        var operations = new ObjectOpenHashSet<UUID>();
        for (var team : teams) {
            if (!teamIds.add(team.teamId())) {
                throw new IllegalArgumentException("Duplicate research world team");
            }
            if (team.queue().updatedAtWorldTime() > serverTime) {
                throw new IllegalArgumentException("Research queue exceeds world time");
            }
            if (team.stationWork().lastGrantedTime() > serverTime) {
                throw new IllegalArgumentException("Station grant exceeds research world time");
            }
            for (var member : team.knownMembers()) {
                requirePlayer(playerIds, member);
            }
            for (var entry : team.queue().entries()) {
                requirePlayer(playerIds, entry.requestedBy());
            }
            for (var session : team.manualSessions()) {
                if (!sessionIds.add(session.id())) {
                    throw new IllegalArgumentException("Duplicate research world session");
                }
                if (session.lastActivity() > serverTime) {
                    throw new IllegalArgumentException("Manual session exceeds world time");
                }
                if (session.contributor() != null) {
                    requirePlayer(playerIds, session.contributor());
                }
                var material = session.materialIntent();
                if (material != null) {
                    requirePlayer(playerIds, material.contributor());
                    if (!operations.add(material.operationId())) {
                        throw new IllegalArgumentException("Duplicate world material operation");
                    }
                }
                var completion = session.completionIntent();
                if (completion != null) {
                    for (var member : completion.baselines().keySet()) {
                        requirePlayer(playerIds, member);
                    }
                }
            }
        }
        var nonces = new ObjectOpenHashSet<RequestNonce>(downloads.size());
        for (var operation : downloads) {
            if (operation.createdAt() > serverTime || !operations.add(operation.id())
                || !nonces.add(new RequestNonce(operation.intent().playerId(), operation.intent().nonce()))) {
                throw new IllegalArgumentException("Duplicate or future world download operation");
            }
            requirePlayer(playerIds, operation.intent().playerId());
            if (!teamIds.contains(operation.sources().teamId()))
                throw new IllegalArgumentException("Download lost its original team source");
            for (var member : operation.sources().members()) requirePlayer(playerIds, member);
        }
        for (var receipt : downloadReceipts) {
            if (!operations.add(receipt.operationId()) || !nonces.add(new RequestNonce(receipt.intent().playerId(), receipt.intent().nonce()))) {
                throw new IllegalArgumentException("Duplicate terminal world download operation");
            }
            requirePlayer(playerIds, receipt.intent().playerId());
            if (!teamIds.contains(receipt.sources().teamId()))
                throw new IllegalArgumentException("Terminal download lost its original team source");
            for (var member : receipt.sources().members()) requirePlayer(playerIds, member);
        }
        var diskNonces = new ObjectOpenHashSet<RequestNonce>(diskImports.size());
        var diskPlayers = new ObjectOpenHashSet<UUID>(diskImports.size());
        for (var operation : diskImports) {
            if (operation.createdAt() > serverTime || !operations.add(operation.id())
                || !diskNonces.add(new RequestNonce(operation.intent().playerId(), operation.intent().nonce()))
                || !operation.terminal() && !diskPlayers.add(operation.intent().playerId()))
                throw new IllegalArgumentException("Duplicate or future world disk operation");
            requirePlayer(playerIds, operation.intent().playerId());
            requirePlayer(playerIds, operation.target().ownerId());
            if (!teamIds.contains(operation.intent().teamId()))
                throw new IllegalArgumentException("Disk import lost its original team");
        }
    }

    private static void requirePlayer(ObjectSet<UUID> players, UUID id) {
        if (!players.contains(id)) {
            throw new IllegalArgumentException("Research world is missing an authoritative player source: " + id);
        }
    }

    private static <T> ObjectSet<T> frozenSet(ObjectSet<T> values) {
        var copy = new ObjectLinkedOpenHashSet<>(Objects.requireNonNull(values));
        for (var value : copy) {
            Objects.requireNonNull(value);
        }
        return ObjectSets.unmodifiable(copy);
    }

    private record RequestNonce(UUID player, UUID nonce) {
    }

    /**
     * knownMembers is retained history, so a former member may also be known by another team.
     */
    public record Team(String teamId, ObjectSet<UUID> knownMembers, ObjectSet<ResourceLocation> blacklist,
                       TeamOwnedResearch.Snapshot research, TeamResearchQueue.Snapshot queue,
                       ObjectList<ManualResearchSession> manualSessions, ResearchStationWork.Saved stationWork) {
        /**
         * Manual-only hosts retain an explicit empty station checkpoint.
         */
        public Team(String teamId, ObjectSet<UUID> knownMembers, ObjectSet<ResourceLocation> blacklist,
                    TeamOwnedResearch.Snapshot research, TeamResearchQueue.Snapshot queue,
                    ObjectList<ManualResearchSession> manualSessions) {
            this(teamId, knownMembers, blacklist, research, queue, manualSessions, new ResearchStationWork.Saved(-1, null));
        }

        public Team {
            Objects.requireNonNull(teamId);
            if (teamId.isBlank() || !teamId.equals(teamId.trim())) {
                throw new IllegalArgumentException("Invalid research team id");
            }
            knownMembers = frozenSet(knownMembers);
            blacklist = frozenSet(blacklist);
            Objects.requireNonNull(research);
            Objects.requireNonNull(queue);
            Objects.requireNonNull(stationWork);
            if (!teamId.equals(research.teamId()) || !teamId.equals(queue.teamId())) {
                throw new IllegalArgumentException("Research team sources have different owners");
            }
            var queueIds = new LongOpenHashSet(queue.entries().size());
            var queueKeys = new ObjectOpenHashSet<TeamResearchQueue.Key>(queue.entries().size());
            for (var entry : queue.entries()) {
                if (!queueIds.add(entry.key().queueEntryId()) || !queueKeys.add(entry.key())) {
                    throw new IllegalArgumentException("Duplicate saved queue entry key");
                }
            }
            var segment = stationWork.segment();
            if (segment != null && (!segment.key().equals(queue.activeHead()) || !queueKeys.contains(segment.key())
                || segment.key().queueEntryId() >= queue.nextEntryId())) {
                throw new IllegalArgumentException("Station materials lost their exact allocated queue head");
            }
            var sessionIds = new ObjectOpenHashSet<UUID>(manualSessions.size());
            var sessionEntries = new LongOpenHashSet(manualSessions.size());
            for (var session : manualSessions) {
                if (!teamId.equals(session.teamId()) || !sessionIds.add(session.id())
                    || !sessionEntries.add(session.queueKey().queueEntryId())) {
                    throw new IllegalArgumentException("Manual session owner or identity differs");
                }
                if (session.queueKey().queueEntryId() >= queue.nextEntryId()) {
                    throw new IllegalArgumentException("Manual session queue id was never allocated");
                }
                switch (session.status()) {
                    case QUEUE_HEAD_REMOVED -> {
                        if (queueIds.contains(session.queueKey().queueEntryId())) {
                            throw new IllegalArgumentException("Removed manual queue entry is still present");
                        }
                    }
                    case COMPLETION_PENDING, COMPLETION_PROPAGATED -> {
                        if (!session.queueKey().equals(queue.activeHead()) || !queueKeys.contains(session.queueKey())) {
                            throw new IllegalArgumentException("Manual completion lost its exact queue head");
                        }
                    }
                    default -> {
                        if (!queueKeys.contains(session.queueKey())) {
                            throw new IllegalArgumentException("Manual session lost its exact queue entry");
                        }
                    }
                }
            }
        }
    }
}
