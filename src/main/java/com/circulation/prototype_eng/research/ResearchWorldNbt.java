package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
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
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.station.ResearchStationWork;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
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

    public ResearchWorldNbt(ResearchRegistry registry, NetworkResourceRegistry resources, ResourceKeyNbt keys) {
        this.registry = Objects.requireNonNull(registry);
        players = new PlayerResearchStateNbt(registry, keys);
        queues = new ResearchQueueNbt(registry, resources);
        manual = new ManualResearchSessionNbt(registry);
        downloads = new ResearchDownloadNbt(registry, keys);
    }

    private static NBTTagCompound encodeStationWork(ResearchStationWork.Saved value) {
        var tag = new NBTTagCompound();
        tag.setLong("LastGrantedTime", value.lastGrantedTime());
        var segment = value.segment();
        tag.setBoolean("HasSegment", segment != null);
        if (segment != null) {
            var saved = new NBTTagCompound();
            var key = new NBTTagCompound();
            key.setString("Research", segment.key().researchId().toString());
            key.setInteger("Level", segment.key().targetLevel());
            key.setLong("EntryId", segment.key().queueEntryId());
            saved.setTag("Key", key);
            var sites = new NBTTagList();
            for (var site : segment.sites()) {
                var entry = new NBTTagCompound();
                entry.setTag("Identity", site.identity().encode());
                var location = new NBTTagCompound();
                location.setString("Type", site.location().machineType().toString());
                location.setInteger("Dimension", site.location().dimension());
                var position = site.location().controllerPos();
                location.setInteger("X", position.getX());
                location.setInteger("Y", position.getY());
                location.setInteger("Z", position.getZ());
                entry.setTag("Location", location);
                if (site.controllerBlockId() != null) {
                    entry.setString("ControllerBlock", site.controllerBlockId().toString());
                }
                sites.appendTag(entry);
            }
            saved.setTag("Sites", sites);
            // SavedSegment returns a defensive copy; this envelope never owns a mutable snapshot tag.
            saved.setTag("Process", segment.processTag());
            tag.setTag("Segment", saved);
        }
        return tag;
    }

    private static ResearchStationWork.Saved decodeStationWork(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "LastGrantedTime", "HasSegment", "Segment");
        long granted = StrictNbt.requireLong(tag, "LastGrantedTime");
        if (!StrictNbt.requireBoolean(tag, "HasSegment")) {
            if (tag.hasKey("Segment")) {
                throw new IllegalArgumentException("Absent station materials have saved segment fields");
            }
            return new ResearchStationWork.Saved(granted, null);
        }
        var segment = StrictNbt.requireCompound(tag, "Segment");
        StrictNbt.requireKeys(segment, "Key", "Sites", "Process");
        var key = StrictNbt.requireCompound(segment, "Key");
        StrictNbt.requireKeys(key, "Research", "Level", "EntryId");
        var queueKey = new TeamResearchQueue.Key(StrictNbt.requireId(key, "Research"), StrictNbt.requireInt(key, "Level"),
            StrictNbt.requireLong(key, "EntryId"));
        var sites = new ObjectArrayList<ResearchStations.Site>();
        var savedSites = StrictNbt.requireList(segment, "Sites", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedSites.tagCount(); index++) {
            var site = savedSites.getCompoundTagAt(index);
            StrictNbt.requireKeys(site, "Identity", "Location", "ControllerBlock");
            var identity = PersistentMachineIdentity.decode(StrictNbt.requireCompound(site, "Identity"));
            var location = StrictNbt.requireCompound(site, "Location");
            StrictNbt.requireKeys(location, "Type", "Dimension", "X", "Y", "Z");
            sites.add(new ResearchStations.Site(identity, new MachineIdentity(StrictNbt.requireId(location, "Type"),
                StrictNbt.requireInt(location, "Dimension"), new BlockPos(StrictNbt.requireInt(location, "X"),
                StrictNbt.requireInt(location, "Y"), StrictNbt.requireInt(location, "Z"))),
                site.hasKey("ControllerBlock") ? StrictNbt.requireId(site, "ControllerBlock") : null));
        }
        // The envelope checks structure only. Recipe/revision/state validation needs the restored
        // real owner and frozen material definition in ResearchStationWork.resume.
        return new ResearchStationWork.Saved(granted, new ResearchStationWork.SavedSegment(queueKey, sites,
            StrictNbt.requireCompound(segment, "Process")));
    }

    @Override
    public NBTTagCompound encode(ResearchWorldSnapshot value) {
        Objects.requireNonNull(value);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 5);
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
        if (StrictNbt.requireInt(tag, "Version") != 5) {
            throw new IllegalArgumentException("Unknown research world version");
        }
        var playerList = new ObjectArrayList<PlayerResearchState.Snapshot>();
        var savedPlayers = StrictNbt.requireList(tag, "Players", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedPlayers.tagCount(); index++) {
            playerList.add(players.decode(savedPlayers.getCompoundTagAt(index)));
        }
        var teamList = new ObjectArrayList<ResearchWorldSnapshot.Team>();
        var savedTeams = StrictNbt.requireList(tag, "Teams", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedTeams.tagCount(); index++) {
            teamList.add(decodeTeam(savedTeams.getCompoundTagAt(index)));
        }
        var operationList = new ObjectArrayList<ResearchDownloadOperation>();
        var savedOperations = StrictNbt.requireList(tag, "Downloads", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedOperations.tagCount(); index++)
            operationList.add(downloads.decodeOperation(savedOperations.getCompoundTagAt(index)));
        var receiptList = new ObjectArrayList<ResearchDownloadReceipt>();
        var savedReceipts = StrictNbt.requireList(tag, "DownloadReceipts", Constants.NBT.TAG_COMPOUND);
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
        tag.setTag("StationWork", encodeStationWork(value.stationWork()));
        return tag;
    }

    private ResearchWorldSnapshot.Team decodeTeam(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Team", "KnownMembers", "Blacklist", "Research", "Queue", "ManualSessions", "StationWork");
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
            TeamOwnedResearch.CODEC.decode(StrictNbt.requireCompound(tag, "Research")), queues.decode(StrictNbt.requireCompound(tag, "Queue")), sessions,
            decodeStationWork(StrictNbt.requireCompound(tag, "StationWork")));
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
        var segment = value.stationWork().segment();
        if (segment != null && registry.require(segment.key().researchId()).stageForLevel(segment.key().targetLevel()).researchTier() < 1) {
            throw new IllegalArgumentException("Station materials require positive-tier research");
        }
    }
}
