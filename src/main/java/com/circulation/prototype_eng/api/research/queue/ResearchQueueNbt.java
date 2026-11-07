package com.circulation.prototype_eng.api.research.queue;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;
import java.util.UUID;

/** Saves queue facts and grant deduplication markers; pending transactions/plans are never encoded. */
@SuppressWarnings("unused")
public final class ResearchQueueNbt implements NbtCodec<TeamResearchQueue.Snapshot> {
    private final ResearchRegistry registry;
    private final NetworkResourceRegistry resources;

    public ResearchQueueNbt(ResearchRegistry registry, NetworkResourceRegistry resources) {
        this.registry = Objects.requireNonNull(registry);
        this.resources = Objects.requireNonNull(resources);
    }

    @Override public NBTTagCompound encode(TeamResearchQueue.Snapshot value) {
        TeamResearchQueue.restore(registry, resources, value);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setString("Team", value.teamId());
        tag.setLong("NextEntryId", value.nextEntryId());
        tag.setLong("Epoch", value.epoch());
        tag.setLong("UpdatedAt", value.updatedAtWorldTime());
        tag.setLong("LastGrantedEntryId", value.lastGrantedEntryId());
        tag.setLong("LastGrantedAt", value.lastGrantedWorldTime());
        var queue = new NBTTagList();
        for (var entry : value.entries()) {
            var saved = encodeKey(entry.key());
            saved.setString("RequestedBy", entry.requestedBy().toString());
            saved.setLong("CreatedAt", entry.createdAtWorldTime());
            queue.appendTag(saved);
        }
        tag.setTag("Queue", queue);
        var head = value.activeHead();
        tag.setBoolean("HasHead", head != null);
        if (head != null) { tag.setTag("Head", encodeKey(head)); }
        var progress = new NBTTagList();
        for (var entry : value.resourceProgress().object2LongEntrySet()) {
            var saved = new NBTTagCompound();
            saved.setString("Resource", entry.getKey().id().toString());
            saved.setLong("Amount", entry.getLongValue());
            progress.appendTag(saved);
        }
        tag.setTag("Progress", progress);
        return tag;
    }

    @Override public TeamResearchQueue.Snapshot decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "Team", "NextEntryId", "Epoch", "UpdatedAt", "LastGrantedEntryId",
            "LastGrantedAt", "Queue", "HasHead", "Head", "Progress");
        if (StrictNbt.requireInt(tag, "Version") != 1) { throw new IllegalArgumentException("Unknown research queue save version"); }
        var savedEntries = StrictNbt.requireList(tag, "Queue", Constants.NBT.TAG_COMPOUND);
        if (savedEntries.tagCount() > TeamResearchQueue.CAPACITY) { throw new IllegalArgumentException("Saved research queue exceeds capacity"); }
        var entries = new ObjectArrayList<TeamResearchQueue.Entry>();
        for (int index = 0; index < savedEntries.tagCount(); index++) {
            var entry = savedEntries.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Research", "Level", "EntryId", "RequestedBy", "CreatedAt");
            entries.add(new TeamResearchQueue.Entry(decodeKey(entry), uuid(StrictNbt.requireString(entry, "RequestedBy")),
                StrictNbt.requireLong(entry, "CreatedAt")));
        }
        var head = StrictNbt.requireBoolean(tag, "HasHead") ? decodeHead(StrictNbt.requireCompound(tag, "Head")) : null;
        if (head == null && tag.hasKey("Head")) { throw new IllegalArgumentException("Absent research head has saved head fields"); }
        var amounts = new Object2LongOpenHashMap<NetworkResourceType>();
        var progress = StrictNbt.requireList(tag, "Progress", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < progress.tagCount(); index++) {
            var entry = progress.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Resource", "Amount");
            var type = resources.require(StrictNbt.requireId(entry, "Resource"));
            if (amounts.containsKey(type)) { throw new IllegalArgumentException("Duplicate saved research resource"); }
            amounts.put(type, StrictNbt.requireLong(entry, "Amount"));
        }
        var saved = new TeamResearchQueue.Snapshot(StrictNbt.requireString(tag, "Team"), entries, head, amounts,
            StrictNbt.requireLong(tag, "NextEntryId"), StrictNbt.requireLong(tag, "Epoch"), StrictNbt.requireLong(tag, "UpdatedAt"),
            StrictNbt.requireLong(tag, "LastGrantedEntryId"), StrictNbt.requireLong(tag, "LastGrantedAt"));
        TeamResearchQueue.restore(registry, resources, saved);
        return saved;
    }

    private static NBTTagCompound encodeKey(TeamResearchQueue.Key key) {
        var tag = new NBTTagCompound();
        tag.setString("Research", key.researchId().toString());
        tag.setInteger("Level", key.targetLevel());
        tag.setLong("EntryId", key.queueEntryId());
        return tag;
    }
    private static TeamResearchQueue.Key decodeHead(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Research", "Level", "EntryId");
        return decodeKey(tag);
    }
    private static TeamResearchQueue.Key decodeKey(NBTTagCompound tag) {
        return new TeamResearchQueue.Key(StrictNbt.requireId(tag, "Research"), StrictNbt.requireInt(tag, "Level"),
            StrictNbt.requireLong(tag, "EntryId"));
    }
    private static UUID uuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) { throw new IllegalArgumentException("Noncanonical research requester UUID"); }
        return id;
    }
}
