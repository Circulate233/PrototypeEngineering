package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;

import java.util.HexFormat;
import java.util.UUID;

/** One-way upgrade of attributable old levels. Never rewrite an inventory transaction's identity. */
final class LegacyResearchTeams {
    private LegacyResearchTeams() {
    }

    static ResearchWorldSnapshot migrate(ResearchWorldSnapshot saved, UUID localOwner, String currentFtbId, ObjectSet<String> otherFtbIds) {
        var teams = new ObjectArrayList<ResearchWorldSnapshot.Team>(saved.teams().size());
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        String personal = localOwner.toString();
        var protectedTeams = new ObjectOpenHashSet<String>();
        var protectedPlayers = new ObjectOpenHashSet<UUID>();
        for (var operation : saved.downloads()) {
            protectedTeams.add(operation.sources().teamId());
            protectedPlayers.addAll(operation.sources().members());
        }
        for (var receipt : saved.downloadReceipts()) protectedTeams.add(receipt.sources().teamId());
        for (var operation : saved.diskImports()) protectedTeams.add(operation.intent().teamId());
        for (var team : saved.teams()) {
            for (var session : team.manualSessions()) {
                if (session.materialIntent() != null || session.completionIntent() != null) {
                    protectedTeams.add(team.teamId());
                    if (session.completionIntent() != null)
                        protectedPlayers.addAll(session.completionIntent().baselines().keySet());
                }
            }
        }
        ResearchWorldSnapshot.Team candidate = null;
        int candidates = 0;
        boolean personalExists = false;
        boolean changed = false;
        for (var team : saved.teams()) {
            var id = team.teamId();
            if (id.equals(personal)) personalExists = true;
            if (protectedTeams.contains(id)) {
                teams.add(team);
                continue;
            }
            boolean empty = team.blacklist().isEmpty() && team.research().levels().isEmpty() && team.queue().entries().isEmpty()
                && team.queue().progress().isEmpty() && team.manualSessions().isEmpty();
            if (empty && shortUid(id) && !otherFtbIds.contains(id) && team.queue().nextEntryId() == 1) {
                changed = true;
                continue;
            }
            if (!otherFtbIds.contains(id) && (id.equals("0002") || id.equals(currentFtbId)) && sameOwner(team, localOwner)) {
                boolean hasWork = !team.blacklist().isEmpty() || !team.queue().entries().isEmpty() || !team.queue().progress().isEmpty()
                    || !team.manualSessions().isEmpty();
                if (hasWork) {
                    candidates++;
                    candidate = team;
                }
                if (!protectedPlayers.contains(localOwner) && !team.research().levels().isEmpty()) {
                    for (var entry : team.research().levels().object2IntEntrySet()) {
                        if (entry.getIntValue() > levels.getInt(entry.getKey())) levels.put(entry.getKey(), entry.getIntValue());
                    }
                    changed = true;
                    if (hasWork)
                        teams.add(new ResearchWorldSnapshot.Team(id, team.knownMembers(), team.blacklist(),
                            new TeamOwnedResearch.Snapshot(id, Object2IntMaps.emptyMap()), team.queue(), team.manualSessions()));
                    continue;
                }
            }
            teams.add(team);
        }
        if (!personalExists && candidates == 1 && candidate != null && (!protectedPlayers.contains(localOwner) || candidate.research().levels().isEmpty())) {
            var id = candidate.teamId();
            teams.removeIf(team -> team.teamId().equals(id));
            teams.add(rekey(candidate, localOwner));
            changed = true;
        }
        if (!changed) return saved;
        var players = saved.players();
        if (!levels.isEmpty()) {
            var upgraded = new ObjectArrayList<PlayerResearchState.Snapshot>(players.size() + 1);
            boolean found = false;
            for (var player : players) {
                if (player.playerUuid().equals(localOwner)) {
                    found = true;
                    for (var entry : player.completedLevels().object2IntEntrySet()) {
                        if (entry.getIntValue() > levels.getInt(entry.getKey())) levels.put(entry.getKey(), entry.getIntValue());
                    }
                    upgraded.add(new PlayerResearchState.Snapshot(localOwner, levels, player.rewardClaims()));
                } else upgraded.add(player);
            }
            if (!found) upgraded.add(new PlayerResearchState.Snapshot(localOwner, levels, ObjectList.of()));
            players = upgraded;
        }
        return new ResearchWorldSnapshot(saved.serverTime(), players, teams, saved.downloads(), saved.downloadReceipts(), saved.diskImports());
    }

    private static boolean shortUid(String id) {
        return id.length() == 4 && HexFormat.isHexDigit(id.charAt(0)) && HexFormat.isHexDigit(id.charAt(1))
            && HexFormat.isHexDigit(id.charAt(2)) && HexFormat.isHexDigit(id.charAt(3));
    }

    private static boolean sameOwner(ResearchWorldSnapshot.Team team, UUID owner) {
        if (!team.knownMembers().isEmpty() && !team.knownMembers().equals(ObjectSet.of(owner))) return false;
        for (var entry : team.queue().entries()) if (!entry.requestedBy().equals(owner)) return false;
        for (var session : team.manualSessions()) if (session.contributor() != null && !session.contributor().equals(owner)) return false;
        return true;
    }

    private static ResearchWorldSnapshot.Team rekey(ResearchWorldSnapshot.Team old, UUID owner) {
        var id = owner.toString();
        var queue = old.queue();
        var sessions = new ObjectArrayList<ManualResearchSession>(old.manualSessions().size());
        for (var s : old.manualSessions()) {
            sessions.add(new ManualResearchSession(s.id(), id, s.queueKey(), s.contributor(), s.lastActivity(), s.status(), s.pauseReason(),
                s.work(), s.materialIntent(), s.completionIntent()));
        }
        return new ResearchWorldSnapshot.Team(id, ObjectSet.of(owner), old.blacklist(),
            new TeamOwnedResearch.Snapshot(id, Object2IntMaps.emptyMap()),
            new TeamResearchQueue.Snapshot(id, queue.entries(), queue.progress(), queue.nextEntryId(), queue.epoch(),
                queue.updatedAtWorldTime(), queue.lastGrantedEntryId(), queue.lastGrantedWorldTime()), sessions);
    }
}
