package com.circulation.prototype_eng.api.research.queue;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;
import java.util.UUID;

/**
 * Saves queue facts and grant deduplication markers; pending transactions/plans are never encoded.
 */
@SuppressWarnings("unused")
public final class ResearchQueueNbt implements NbtCodec<TeamResearchQueue.Snapshot> {
    private final ResearchRegistry registry;

    public ResearchQueueNbt(ResearchRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
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
        if (!id.toString().equals(text)) {
            throw new IllegalArgumentException("Noncanonical research requester UUID");
        }
        return id;
    }

    @Override
    public NBTTagCompound encode(TeamResearchQueue.Snapshot value) {
        TeamResearchQueue.restore(registry, value);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 2);
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
        if (head != null) {
            tag.setTag("Head", encodeKey(head));
        }
        var progress = new NBTTagList();
        for (var entry : value.resourceProgress().reference2DoubleEntrySet()) {
            var saved = new NBTTagCompound();
            saved.setString("Resource", entry.getKey().id().toString());
            saved.setDouble("Amount", entry.getDoubleValue());
            progress.appendTag(saved);
        }
        tag.setTag("Progress", progress);
        return tag;
    }

    @Override
    public TeamResearchQueue.Snapshot decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "Team", "NextEntryId", "Epoch", "UpdatedAt", "LastGrantedEntryId",
            "LastGrantedAt", "Queue", "HasHead", "Head", "Progress");
        int version = StrictNbt.requireInt(tag, "Version");
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("Unknown research queue save version");
        }
        var savedEntries = StrictNbt.requireList(tag, "Queue", Constants.NBT.TAG_COMPOUND);
        if (savedEntries.tagCount() > TeamResearchQueue.CAPACITY) {
            throw new IllegalArgumentException("Saved research queue exceeds capacity");
        }
        var entries = new ObjectArrayList<TeamResearchQueue.Entry>();
        for (int index = 0; index < savedEntries.tagCount(); index++) {
            var entry = savedEntries.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Research", "Level", "EntryId", "RequestedBy", "CreatedAt");
            entries.add(new TeamResearchQueue.Entry(decodeKey(entry), uuid(StrictNbt.requireString(entry, "RequestedBy")),
                StrictNbt.requireLong(entry, "CreatedAt")));
        }
        var head = StrictNbt.requireBoolean(tag, "HasHead") ? decodeHead(StrictNbt.requireCompound(tag, "Head")) : null;
        if (head == null && tag.hasKey("Head")) {
            throw new IllegalArgumentException("Absent research head has saved head fields");
        }
        var amounts = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        var progress = StrictNbt.requireList(tag, "Progress", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < progress.tagCount(); index++) {
            var entry = progress.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Resource", "Amount");
            var type = resourceType(head, StrictNbt.requireId(entry, "Resource"));
            if (amounts.containsKey(type)) {
                throw new IllegalArgumentException("Duplicate saved research resource");
            }
            amounts.put(type, version == 1 ? StrictNbt.requireLong(entry, "Amount") : StrictNbt.requireDouble(entry, "Amount"));
        }
        var saved = new TeamResearchQueue.Snapshot(StrictNbt.requireString(tag, "Team"), entries, head, amounts,
            StrictNbt.requireLong(tag, "NextEntryId"), StrictNbt.requireLong(tag, "Epoch"), StrictNbt.requireLong(tag, "UpdatedAt"),
            StrictNbt.requireLong(tag, "LastGrantedEntryId"), StrictNbt.requireLong(tag, "LastGrantedAt"));
        TeamResearchQueue.restore(registry, saved);
        return saved;
    }

    /**
     * Saved IDs resolve through this head's own formula singletons, without a resource registry.
     */
    private NetworkResourceType resourceType(TeamResearchQueue.Key head, ResourceLocation id) {
        if (head == null) throw new IllegalArgumentException("Resource progress has no research head");
        for (var type : registry.require(head.researchId()).stageForLevel(head.targetLevel()).resourceDemandForLevel(head.targetLevel()).keySet())
            if (type.id().equals(id)) return type;
        throw new IllegalArgumentException("Saved resource is not a demand of the research head: " + id);
    }
}
