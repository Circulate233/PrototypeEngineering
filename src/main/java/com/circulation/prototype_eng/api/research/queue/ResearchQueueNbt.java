package com.circulation.prototype_eng.api.research.queue;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
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
        this(registry, ResourceKeyNbt.INSTANCE);
    }

    public ResearchQueueNbt(ResearchRegistry registry, ResourceKeyNbt keys) {
        this.registry = registry;
        this.keys = keys;
    }

    @Override
    public NBTTagCompound encode(TeamResearchQueue.Snapshot value) {
        var tag = new NBTTagCompound();
        tag.setString("Team", value.teamId());
        tag.setLong("NextEntryId", value.nextEntryId());
        tag.setLong("Epoch", value.epoch());
        tag.setLong("UpdatedAt", value.updatedAtWorldTime());
        tag.setLong("LastGrantedEntryId", value.lastGrantedEntryId());
        tag.setLong("LastGrantedAt", value.lastGrantedWorldTime());
        var queue = new NBTTagList();
        var elements = value.entries();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var entry = elements.get(index1);
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
        var elements2 = value.progress();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var state = elements2.get(index2);
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
        var entries = new ObjectArrayList<TeamResearchQueue.Entry>();
        var queue = tag.getTagList("Queue", Constants.NBT.TAG_COMPOUND);
        if (queue.tagCount() > TeamResearchQueue.CAPACITY)
            throw new IllegalArgumentException("Saved research queue exceeds capacity");
        for (int index = 0; index < queue.tagCount(); index++) {
            var entry = queue.getCompoundTagAt(index);
            var key = new TeamResearchQueue.Key(new ResourceLocation(entry.getString("Research")), entry.getInteger("Level"), entry.getLong("EntryId"));
            entries.add(new TeamResearchQueue.Entry(key, UUID.fromString(entry.getString("RequestedBy")), entry.getLong("CreatedAt")));
        }
        var states = new ObjectArrayList<ResearchProgress.Snapshot>();
        var progress = tag.getTagList("Progress", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < progress.tagCount(); index++) {
            var saved = progress.getCompoundTagAt(index);
            var target = new ResearchProgress.Target(new ResourceLocation(saved.getString("Research")), saved.getInteger("Level"));
            var materials = new AEKey2LongMap.OpenHashMap();
            var materialList = saved.getTagList("Materials", Constants.NBT.TAG_COMPOUND);
            for (int materialIndex = 0; materialIndex < materialList.tagCount(); materialIndex++) {
                var material = materialList.getCompoundTagAt(materialIndex);
                var key = keys.decode(material.getCompoundTag("Key"));
                materials.put(key, material.getLong("Remaining"));
            }
            var resources = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            var resourceList = saved.getTagList("Resources", Constants.NBT.TAG_COMPOUND);
            for (int resourceIndex = 0; resourceIndex < resourceList.tagCount(); resourceIndex++) {
                var resource = resourceList.getCompoundTagAt(resourceIndex);
                var type = resourceType(target, new ResourceLocation(resource.getString("Resource")));
                resources.put(type, resource.getDouble("Amount"));
            }
            states.add(new ResearchProgress.Snapshot(target, saved.getLong("Elapsed"), materials, resources));
        }
        return new TeamResearchQueue.Snapshot(tag.getString("Team"), entries, states,
            tag.getLong("NextEntryId"), tag.getLong("Epoch"), tag.getLong("UpdatedAt"),
            tag.getLong("LastGrantedEntryId"), tag.getLong("LastGrantedAt"));
    }

    private NetworkResourceType resourceType(ResearchProgress.Target target, ResourceLocation id) {
        return registry.require(target.researchId()).stageForLevel(target.level()).resourceFormula().resourceType(id);
    }
}
