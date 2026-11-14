package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.PlayerResearchStateNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperationNbt;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadNbt;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadReceipt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSessionNbt;
import com.circulation.prototype_eng.api.research.queue.ResearchQueueNbt;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;
import java.util.UUID;

/**
 * Composes player, team, queue, manual and physical station checkpoints without constructing owners.
 */
@SuppressWarnings("unused") // Public world persistence codec.
public final class ResearchWorldNbt implements NbtCodec<ResearchWorldSnapshot> {
    private final ResearchRegistry registry;
    private final PlayerResearchStateNbt players;
    private final ResearchQueueNbt queues;
    private final ManualResearchSessionNbt manual;
    private final ResearchDownloadNbt downloads;

    public ResearchWorldNbt(ResearchRegistry registry, ResourceKeyNbt keys) {
        this.registry = Objects.requireNonNull(registry);
        players = new PlayerResearchStateNbt(registry, keys);
        queues = new ResearchQueueNbt(registry, keys);
        manual = new ManualResearchSessionNbt(registry);
        downloads = new ResearchDownloadNbt(registry, keys);
    }

    @Override
    public NBTTagCompound encode(ResearchWorldSnapshot value) {
        Objects.requireNonNull(value);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 6);
        tag.setLong("ServerTime", value.serverTime());
        var savedPlayers = new NBTTagList();
        for (var player : value.players()) {
            savedPlayers.appendTag(players.encode(player));
        }
        tag.setTag("Players", savedPlayers);
        var teams = new NBTTagList();
        for (var team : value.teams()) {
            teams.appendTag(encodeTeam(team));
        }
        tag.setTag("Teams", teams);
        var operations = new NBTTagList();
        for (var operation : value.downloads()) operations.appendTag(downloads.encode(operation));
        tag.setTag("Downloads", operations);
        var receipts = new NBTTagList();
        for (var receipt : value.downloadReceipts()) receipts.appendTag(downloads.encode(receipt));
        tag.setTag("DownloadReceipts", receipts);
        var disks = new NBTTagList();
        for (var operation : value.diskImports()) disks.appendTag(ResearchDiskOperationNbt.encode(operation));
        tag.setTag("DiskImports", disks);
        return tag;
    }

    @Override
    public ResearchWorldSnapshot decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "ServerTime", "Players", "Teams", "Downloads", "DownloadReceipts", "DiskImports");
        if (StrictNbt.requireInt(tag, "Version") != 6) {
            throw new IllegalArgumentException("Unknown research world version");
        }
        var savedPlayers = StrictNbt.requireList(tag, "Players", Constants.NBT.TAG_COMPOUND);
        var playerList = new ObjectArrayList<PlayerResearchState.Snapshot>(savedPlayers.tagCount());
        for (int index = 0; index < savedPlayers.tagCount(); index++) {
            playerList.add(players.decode(savedPlayers.getCompoundTagAt(index)));
        }
        var savedTeams = StrictNbt.requireList(tag, "Teams", Constants.NBT.TAG_COMPOUND);
        var teamList = new ObjectArrayList<ResearchWorldSnapshot.Team>(savedTeams.tagCount());
        for (int index = 0; index < savedTeams.tagCount(); index++) {
            teamList.add(decodeTeam(savedTeams.getCompoundTagAt(index)));
        }
        var savedOperations = StrictNbt.requireList(tag, "Downloads", Constants.NBT.TAG_COMPOUND);
        var operationList = new ObjectArrayList<ResearchDownloadOperation>(savedOperations.tagCount());
        for (int index = 0; index < savedOperations.tagCount(); index++)
            operationList.add(downloads.decodeOperation(savedOperations.getCompoundTagAt(index)));
        var savedReceipts = StrictNbt.requireList(tag, "DownloadReceipts", Constants.NBT.TAG_COMPOUND);
        var receiptList = new ObjectArrayList<ResearchDownloadReceipt>(savedReceipts.tagCount());
        for (int index = 0; index < savedReceipts.tagCount(); index++)
            receiptList.add(downloads.decodeReceipt(savedReceipts.getCompoundTagAt(index)));
        var diskList = new ObjectArrayList<ResearchDiskOperation>();
        var disks = StrictNbt.requireList(tag, "DiskImports", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < disks.tagCount(); index++)
            diskList.add(ResearchDiskOperationNbt.decode(disks.getCompoundTagAt(index), registry));
        return new ResearchWorldSnapshot(StrictNbt.requireLong(tag, "ServerTime"), playerList, teamList, operationList, receiptList, diskList);
    }

    private NBTTagCompound encodeTeam(ResearchWorldSnapshot.Team value) {
        validateTeam(value);
        var tag = new NBTTagCompound();
        tag.setString("Team", value.teamId());
        var members = new NBTTagList();
        for (var member : value.knownMembers()) {
            members.appendTag(new NBTTagString(member.toString()));
        }
        tag.setTag("KnownMembers", members);
        var blacklist = new NBTTagList();
        for (var id : value.blacklist()) {
            blacklist.appendTag(new NBTTagString(id.toString()));
        }
        tag.setTag("Blacklist", blacklist);
        tag.setTag("Research", TeamOwnedResearch.CODEC.encode(value.research()));
        tag.setTag("Queue", queues.encode(value.queue()));
        var sessions = new NBTTagList();
        for (var session : value.manualSessions()) {
            sessions.appendTag(manual.encode(session));
        }
        tag.setTag("ManualSessions", sessions);
        return tag;
    }

    private ResearchWorldSnapshot.Team decodeTeam(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Team", "KnownMembers", "Blacklist", "Research", "Queue", "ManualSessions");
        var members = new ObjectLinkedOpenHashSet<UUID>();
        var savedMembers = StrictNbt.requireList(tag, "KnownMembers", Constants.NBT.TAG_STRING);
        for (int index = 0; index < savedMembers.tagCount(); index++) {
            var text = savedMembers.getStringTagAt(index);
            var member = UUID.fromString(text);
            if (!member.toString().equals(text) || !members.add(member)) {
                throw new IllegalArgumentException("Invalid or duplicate known team member");
            }
        }
        var blacklist = new ObjectLinkedOpenHashSet<ResourceLocation>();
        var savedBlacklist = StrictNbt.requireList(tag, "Blacklist", Constants.NBT.TAG_STRING);
        for (int index = 0; index < savedBlacklist.tagCount(); index++) {
            if (!blacklist.add(StrictNbt.requireId(savedBlacklist.getStringTagAt(index)))) {
                throw new IllegalArgumentException("Duplicate research blacklist id");
            }
        }
        var sessions = new ObjectArrayList<ManualResearchSession>();
        var savedSessions = StrictNbt.requireList(tag, "ManualSessions", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedSessions.tagCount(); index++) {
            sessions.add(manual.decode(savedSessions.getCompoundTagAt(index)));
        }
        var team = new ResearchWorldSnapshot.Team(StrictNbt.requireString(tag, "Team"), members, blacklist,
            TeamOwnedResearch.CODEC.decode(StrictNbt.requireCompound(tag, "Research")), queues.decode(StrictNbt.requireCompound(tag, "Queue")), sessions);
        validateTeam(team);
        return team;
    }

    private void validateTeam(ResearchWorldSnapshot.Team value) {
        // This source's codec is deliberately registry-independent; its real restore validates definitions.
        var source = new TeamOwnedResearch(value.teamId(), registry);
        source.restore(value.research());
        for (var id : value.blacklist()) {
            registry.require(id);
        }
    }
}
