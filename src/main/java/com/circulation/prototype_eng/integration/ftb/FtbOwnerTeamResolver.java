package com.circulation.prototype_eng.integration.ftb;

import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.feed_the_beast.ftblib.lib.data.ForgePlayer;
import com.feed_the_beast.ftblib.lib.data.TeamType;
import com.feed_the_beast.ftblib.lib.data.Universe;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Direct FTBLib 1.12.2 adapter bound to one actual server session.
 */
@SuppressWarnings("unused") // Server hosts construct this adapter explicitly and pass it to NetworkPlanner.capture.
public final class FtbOwnerTeamResolver implements OwnerTeamResolver {
    private final MinecraftServer server;

    public FtbOwnerTeamResolver(MinecraftServer server) {
        this.server = server;
    }

    private static UUID requirePlayerId(@Nullable ForgePlayer player, String teamId, String role) {
        if (player == null || player.getId() == null) {
            throw new IllegalStateException("FTBLib team " + teamId + " contains an invalid " + role + " UUID");
        }
        return player.getId();
    }

    private static @Nullable OwnerTeamType mapType(TeamType type) {
        if (type == TeamType.PLAYER) {
            return OwnerTeamType.PLAYER;
        }
        if (type == TeamType.SERVER) {
            return OwnerTeamType.SERVER;
        }
        return null;
    }

    private static OwnerTeamResolution rejected(UUID ownerId, OwnerTeamResolutionStatus status, String message) {
        return OwnerTeamResolution.rejected(ownerId, status, message);
    }

    @Override
    public OwnerTeamResolution resolve(UUID ownerId) {
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("FTBLib owner resolution requires the bound server thread");
        }
        if (!Universe.loaded()) {
            return rejected(ownerId, OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE, "FTBLib universe is not loaded");
        }
        var universe = Universe.get();
        if (universe.server != server) {
            return rejected(ownerId, OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE,
                "FTBLib universe belongs to another server session");
        }
        var owner = universe.getPlayer(ownerId);
        if (owner == null) {
            return rejected(ownerId, OwnerTeamResolutionStatus.OWNER_UNKNOWN, "FTBLib has no persistent owner " + ownerId);
        }
        var team = owner.team;
        if (team == null) {
            return rejected(ownerId, OwnerTeamResolutionStatus.NO_TEAM, "Owner " + ownerId + " has no FTBLib team");
        }
        if (team.universe != universe) {
            throw new IllegalStateException("FTBLib owner team belongs to another universe");
        }
        if (!team.isValid()) {
            return rejected(ownerId, OwnerTeamResolutionStatus.INVALID_TEAM, "FTBLib owner team is invalid");
        }
        var type = mapType(team.type);
        if (type == null) {
            return rejected(ownerId, OwnerTeamResolutionStatus.UNSUPPORTED_TEAM_TYPE,
                "FTBLib team type " + team.type + " is not persistent machine ownership");
        }
        String teamId = team.getUIDCode();
        var members = new ObjectOpenHashSet<UUID>();
        var elements = team.getMembers();
        for (int index = 0; index < elements.size(); index++) {
            var member = elements.get(index);
            members.add(requirePlayerId(member, teamId, "member"));
        }
        if (!members.contains(ownerId)) {
            return rejected(ownerId, OwnerTeamResolutionStatus.INVALID_TEAM,
                "FTBLib team " + teamId + " does not contain queried owner " + ownerId);
        }
        var teamOwner = team.getOwner();
        var teamOwnerId = teamOwner == null ? null
            : requirePlayerId(teamOwner, teamId, "owner");
        return OwnerTeamResolution.resolved(ownerId, teamId, type, members, teamOwnerId);
    }
}
