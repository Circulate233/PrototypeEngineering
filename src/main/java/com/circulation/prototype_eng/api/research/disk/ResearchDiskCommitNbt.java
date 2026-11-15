package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Comparator;
import java.util.UUID;

/**
 * Complete receipt codec shared by the single world operation and its physical database.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class ResearchDiskCommitNbt {
    private ResearchDiskCommitNbt() {
    }

    public static NBTTagCompound encode(ResearchDiskCommit commit) {
        var tag = new NBTTagCompound();
        tag.setString("Operation", commit.operationId().toString());
        tag.setString("Actor", commit.actor().toString());
        tag.setString("Team", commit.teamId());
        tag.setString("Database", commit.before().dataCenterId().toString());
        tag.setInteger("DatabaseTier", commit.before().level());
        tag.setInteger("Capacity", commit.before().capacity());
        tag.setInteger("DiskTier", commit.diskTier());
        tag.setTag("Entries", encodeLevels(commit.entries()));
        tag.setTag("Before", encodeLevels(commit.before().storedResearch()));
        tag.setTag("After", encodeLevels(commit.after().storedResearch()));
        return tag;
    }

    public static ResearchDiskCommit decode(NBTTagCompound tag) {
        var database = uuid(tag, "Database");
        int tier = tag.getInteger("DatabaseTier");
        int capacity = tag.getInteger("Capacity");
        int diskTier = tag.getInteger("DiskTier");
        var entries = decodeLevels(tag, "Entries");
        var before = snapshot(database, tier, capacity, decodeLevels(tag, "Before"));
        var after = snapshot(database, tier, capacity, decodeLevels(tag, "After"));
        return new ResearchDiskCommit(uuid(tag, "Operation"), uuid(tag, "Actor"), tag.getString("Team"),
            diskTier, entries, before, after);
    }

    private static DataCenterResearchSnapshot snapshot(UUID database, int tier, int capacity,
                                                       Object2IntMap<ResourceLocation> contents) {
        return new DataCenterResearchSnapshot(database, tier, capacity, 0, contents);
    }

    private static UUID uuid(NBTTagCompound tag, String key) {
        var text = tag.getString(key);
        return UUID.fromString(text);
    }

    private static NBTTagList encodeLevels(Object2IntMap<ResourceLocation> levels) {
        var list = new NBTTagList();
        var ids = new ObjectArrayList<>(levels.keySet());
        ids.sort(Comparator.naturalOrder());
        for (int index1 = 0; index1 < ids.size(); index1++) {
            var id = ids.get(index1);
            var entry = new NBTTagCompound();
            entry.setString("Id", id.toString());
            entry.setInteger("Level", levels.getInt(id));
            list.appendTag(entry);
        }
        return list;
    }

    private static Object2IntMap<ResourceLocation> decodeLevels(NBTTagCompound tag, String key) {
        var list = tag.getTagList(key, Constants.NBT.TAG_COMPOUND);
        var result = new Object2IntOpenHashMap<ResourceLocation>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            var id = new ResourceLocation(entry.getString("Id"));
            int level = entry.getInteger("Level");
            if (level < 1 || result.containsKey(id))
                throw new IllegalArgumentException("Invalid or duplicate disk receipt research");
            result.put(id, level);
        }
        return result;
    }
}
