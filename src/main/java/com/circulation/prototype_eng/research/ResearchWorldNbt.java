package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.PlayerResearchStateNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperationNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragmentNbt;
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

import java.util.UUID;

/**
 * Composes player, team, queue, manual and physical station checkpoints without constructing owners.
 */
@SuppressWarnings("unused") // Public world persistence codec.
public final class ResearchWorldNbt implements NbtCodec<ResearchWorldSnapshot> {
    private final PlayerResearchStateNbt players;
    private final ResearchQueueNbt queues;
    private final ManualResearchSessionNbt manual;
    private final ResearchDownloadNbt downloads;
    private final ItemDeliveryFragmentNbt fragments;

    public ResearchWorldNbt(ResearchRegistry registry, ResourceKeyNbt keys) {
        players = new PlayerResearchStateNbt(registry, keys);
        queues = new ResearchQueueNbt(registry, keys);
        manual = new ManualResearchSessionNbt(registry);
        downloads = new ResearchDownloadNbt(registry, keys);
        fragments = new ItemDeliveryFragmentNbt(keys);
    }

    @Override
    public NBTTagCompound encode(ResearchWorldSnapshot value) {
        var tag = new NBTTagCompound();
        tag.setLong("ServerTime", value.serverTime());
        var savedPlayers = new NBTTagList();
        var elements5 = value.players();
        for (int index5 = 0; index5 < elements5.size(); index5++) {
            var player = elements5.get(index5);
            savedPlayers.appendTag(players.encode(player));
        }
        tag.setTag("Players", savedPlayers);
        var teams = new NBTTagList();
        var elements2 = value.teams();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var team = elements2.get(index2);
            teams.appendTag(encodeTeam(team));
        }
        tag.setTag("Teams", teams);
        var operations = new NBTTagList();
        var elements = value.downloads();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var operation = elements.get(index1);
            operations.appendTag(downloads.encode(operation));
        }
        tag.setTag("Downloads", operations);
        var receipts = new NBTTagList();
        var elements3 = value.downloadReceipts();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var receipt = elements3.get(index3);
            receipts.appendTag(downloads.encode(receipt));
        }
        tag.setTag("DownloadReceipts", receipts);
        var disks = new NBTTagList();
        var elements4 = value.diskImports();
        for (int index4 = 0; index4 < elements4.size(); index4++) {
            var operation = elements4.get(index4);
            disks.appendTag(ResearchDiskOperationNbt.encode(operation, fragments));
        }
        tag.setTag("DiskImports", disks);
        return tag;
    }

    @Override
    public ResearchWorldSnapshot decode(NBTTagCompound tag) {
        var savedPlayers = tag.getTagList("Players", Constants.NBT.TAG_COMPOUND);
        var playerList = new ObjectArrayList<PlayerResearchState.Snapshot>(savedPlayers.tagCount());
        for (int index = 0; index < savedPlayers.tagCount(); index++) {
            playerList.add(players.decode(savedPlayers.getCompoundTagAt(index)));
        }
        var savedTeams = tag.getTagList("Teams", Constants.NBT.TAG_COMPOUND);
        var teamList = new ObjectArrayList<ResearchWorldSnapshot.Team>(savedTeams.tagCount());
        for (int index = 0; index < savedTeams.tagCount(); index++) {
            teamList.add(decodeTeam(savedTeams.getCompoundTagAt(index)));
        }
        var savedOperations = tag.getTagList("Downloads", Constants.NBT.TAG_COMPOUND);
        var operationList = new ObjectArrayList<ResearchDownloadOperation>(savedOperations.tagCount());
        for (int index = 0; index < savedOperations.tagCount(); index++)
            operationList.add(downloads.decodeOperation(savedOperations.getCompoundTagAt(index)));
        var savedReceipts = tag.getTagList("DownloadReceipts", Constants.NBT.TAG_COMPOUND);
        var receiptList = new ObjectArrayList<ResearchDownloadReceipt>(savedReceipts.tagCount());
        for (int index = 0; index < savedReceipts.tagCount(); index++)
            receiptList.add(downloads.decodeReceipt(savedReceipts.getCompoundTagAt(index)));
        var diskList = new ObjectArrayList<ResearchDiskOperation>();
        var disks = tag.getTagList("DiskImports", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < disks.tagCount(); index++)
            diskList.add(ResearchDiskOperationNbt.decode(disks.getCompoundTagAt(index), fragments));
        return new ResearchWorldSnapshot(tag.getLong("ServerTime"), playerList, teamList, operationList, receiptList, diskList);
    }

    private NBTTagCompound encodeTeam(ResearchWorldSnapshot.Team value) {
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
        var elements6 = value.manualSessions();
        for (int index6 = 0; index6 < elements6.size(); index6++) {
            var session = elements6.get(index6);
            sessions.appendTag(manual.encode(session));
        }
        tag.setTag("ManualSessions", sessions);
        return tag;
    }

    private ResearchWorldSnapshot.Team decodeTeam(NBTTagCompound tag) {
        var members = new ObjectLinkedOpenHashSet<UUID>();
        var savedMembers = tag.getTagList("KnownMembers", Constants.NBT.TAG_STRING);
        for (int index = 0; index < savedMembers.tagCount(); index++) {
            members.add(UUID.fromString(savedMembers.getStringTagAt(index)));
        }
        var blacklist = new ObjectLinkedOpenHashSet<ResourceLocation>();
        var savedBlacklist = tag.getTagList("Blacklist", Constants.NBT.TAG_STRING);
        for (int index = 0; index < savedBlacklist.tagCount(); index++) {
            blacklist.add(new ResourceLocation(savedBlacklist.getStringTagAt(index)));
        }
        var sessions = new ObjectArrayList<ManualResearchSession>();
        var savedSessions = tag.getTagList("ManualSessions", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedSessions.tagCount(); index++) {
            sessions.add(manual.decode(savedSessions.getCompoundTagAt(index)));
        }
        return new ResearchWorldSnapshot.Team(tag.getString("Team"), members, blacklist,
            TeamOwnedResearch.CODEC.decode(tag.getCompoundTag("Research")), queues.decode(tag.getCompoundTag("Queue")), sessions);
    }

}
