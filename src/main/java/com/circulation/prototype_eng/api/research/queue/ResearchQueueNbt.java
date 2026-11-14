package com.circulation.prototype_eng.api.research.queue;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.UUID;

/**
 * Saves queue order together with target work ticks, remaining materials and accumulated network resources.
 */
public final class ResearchQueueNbt implements NbtCodec<TeamResearchQueue.Snapshot> {
    private final ResearchRegistry registry;
    private final ResourceKeyNbt keys;

    @SuppressWarnings("unused") // Public API using the default AE resource decoder.
    public ResearchQueueNbt(ResearchRegistry registry) {
        this(registry, new ResourceKeyNbt());
    }

    public ResearchQueueNbt(ResearchRegistry registry, ResourceKeyNbt keys) {
        this.registry = registry;
        this.keys = keys;
    }

    @Override
    public NBTTagCompound encode(TeamResearchQueue.Snapshot value) {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 3);
        tag.setString("Team", value.teamId());
        tag.setLong("NextEntryId", value.nextEntryId());
        tag.setLong("Epoch", value.epoch());
        tag.setLong("UpdatedAt", value.updatedAtWorldTime());
        tag.setLong("LastGrantedEntryId", value.lastGrantedEntryId());
        tag.setLong("LastGrantedAt", value.lastGrantedWorldTime());
        var queue = new NBTTagList();
        for (var entry : value.entries()) {
            var saved = new NBTTagCompound();
            saved.setString("Research", entry.key().researchId().toString());
            saved.setInteger("Level", entry.key().targetLevel());
            saved.setLong("EntryId", entry.key().queueEntryId());
            saved.setString("RequestedBy", entry.requestedBy().toString());
            saved.setLong("CreatedAt", entry.createdAtWorldTime());
            queue.appendTag(saved);
        }
        tag.setTag("Queue", queue);
        var progress = new NBTTagList();
        for (var state : value.progress()) {
            var saved = new NBTTagCompound();
            saved.setString("Research", state.target().researchId().toString());
            saved.setInteger("Level", state.target().level());
            saved.setLong("Elapsed", state.elapsedTicks());
            var materials = new NBTTagList();
            for (var entry : state.remainingMaterials().object2LongEntrySet()) {
                var material = new NBTTagCompound();
                material.setTag("Key", keys.encode(entry.getKey()));
                material.setLong("Remaining", entry.getLongValue());
                materials.appendTag(material);
            }
            saved.setTag("Materials", materials);
            var resources = new NBTTagList();
            for (var entry : state.resources().reference2DoubleEntrySet()) {
                var resource = new NBTTagCompound();
                resource.setString("Resource", entry.getKey().id().toString());
                resource.setDouble("Amount", entry.getDoubleValue());
                resources.appendTag(resource);
            }
            saved.setTag("Resources", resources);
            progress.appendTag(saved);
        }
        tag.setTag("Progress", progress);
        return tag;
    }

    @Override
    public TeamResearchQueue.Snapshot decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "Team", "NextEntryId", "Epoch", "UpdatedAt", "LastGrantedEntryId", "LastGrantedAt", "Queue", "Progress");
        if (StrictNbt.requireInt(tag, "Version") != 3)
            throw new IllegalArgumentException("Unsupported research queue save version");
        var entries = new ObjectArrayList<TeamResearchQueue.Entry>();
        var queue = StrictNbt.requireList(tag, "Queue", Constants.NBT.TAG_COMPOUND);
        if (queue.tagCount() > TeamResearchQueue.CAPACITY)
            throw new IllegalArgumentException("Saved research queue exceeds capacity");
        for (int index = 0; index < queue.tagCount(); index++) {
            var entry = queue.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Research", "Level", "EntryId", "RequestedBy", "CreatedAt");
            var key = new TeamResearchQueue.Key(StrictNbt.requireId(entry, "Research"), StrictNbt.requireInt(entry, "Level"), StrictNbt.requireLong(entry, "EntryId"));
            entries.add(new TeamResearchQueue.Entry(key, UUID.fromString(StrictNbt.requireString(entry, "RequestedBy")), StrictNbt.requireLong(entry, "CreatedAt")));
        }
        var states = new ObjectArrayList<ResearchProgress.Snapshot>();
        var progress = StrictNbt.requireList(tag, "Progress", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < progress.tagCount(); index++) {
            var saved = progress.getCompoundTagAt(index);
            StrictNbt.requireKeys(saved, "Research", "Level", "Elapsed", "Materials", "Resources");
            var target = new ResearchProgress.Target(StrictNbt.requireId(saved, "Research"), StrictNbt.requireInt(saved, "Level"));
            var materials = new AEKey2LongMap.OpenHashMap();
            var materialList = StrictNbt.requireList(saved, "Materials", Constants.NBT.TAG_COMPOUND);
            for (int materialIndex = 0; materialIndex < materialList.tagCount(); materialIndex++) {
                var material = materialList.getCompoundTagAt(materialIndex);
                StrictNbt.requireKeys(material, "Key", "Remaining");
                var key = keys.decode(StrictNbt.requireCompound(material, "Key"));
                if (materials.containsKey(key)) throw new IllegalArgumentException("Duplicate saved research material");
                materials.put(key, StrictNbt.requireLong(material, "Remaining"));
            }
            var resources = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            var resourceList = StrictNbt.requireList(saved, "Resources", Constants.NBT.TAG_COMPOUND);
            for (int resourceIndex = 0; resourceIndex < resourceList.tagCount(); resourceIndex++) {
                var resource = resourceList.getCompoundTagAt(resourceIndex);
                StrictNbt.requireKeys(resource, "Resource", "Amount");
                var type = resourceType(target, StrictNbt.requireId(resource, "Resource"));
                if (resources.containsKey(type))
                    throw new IllegalArgumentException("Duplicate saved research resource");
                resources.put(type, StrictNbt.requireDouble(resource, "Amount"));
            }
            states.add(new ResearchProgress.Snapshot(target, StrictNbt.requireLong(saved, "Elapsed"), materials, resources));
        }
        var result = new TeamResearchQueue.Snapshot(StrictNbt.requireString(tag, "Team"), entries, states,
            StrictNbt.requireLong(tag, "NextEntryId"), StrictNbt.requireLong(tag, "Epoch"), StrictNbt.requireLong(tag, "UpdatedAt"),
            StrictNbt.requireLong(tag, "LastGrantedEntryId"), StrictNbt.requireLong(tag, "LastGrantedAt"));
        TeamResearchQueue.restore(registry, result);
        return result;
    }

    private NetworkResourceType resourceType(ResearchProgress.Target target, ResourceLocation id) {
        for (var type : registry.require(target.researchId()).stageForLevel(target.level()).resourceDemandForLevel(target.level()).keySet())
            if (type.id().equals(id)) return type;
        throw new IllegalArgumentException("Saved resource is not a research requirement: " + id);
    }
}
