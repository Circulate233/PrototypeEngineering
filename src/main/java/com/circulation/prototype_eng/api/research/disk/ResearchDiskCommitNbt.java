package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Comparator;
import java.util.UUID;

/** Strict complete receipt codec shared by the single world operation and its physical database. */
public final class ResearchDiskCommitNbt {
    private ResearchDiskCommitNbt() {
    }

    public static NBTTagCompound encode(ResearchDiskCommit commit) {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
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

    public static ResearchDiskCommit decode(NBTTagCompound tag, ResearchRegistry registry) {
        StrictNbt.requireKeys(tag, "Version", "Operation", "Actor", "Team", "Database", "DatabaseTier", "Capacity", "DiskTier",
            "Entries", "Before", "After");
        if (StrictNbt.requireInt(tag, "Version") != 1) throw new IllegalArgumentException("Unsupported disk import receipt schema");
        var database = uuid(tag, "Database");
        int tier = StrictNbt.requireInt(tag, "DatabaseTier");
        int capacity = StrictNbt.requireInt(tag, "Capacity");
        int diskTier = StrictNbt.requireInt(tag, "DiskTier");
        var entries = decodeLevels(tag, "Entries");
        new ResearchDiskData(registry, diskTier, entries);
        var before = validated(database, tier, capacity, decodeLevels(tag, "Before"), registry);
        var after = validated(database, tier, capacity, decodeLevels(tag, "After"), registry);
        return new ResearchDiskCommit(uuid(tag, "Operation"), uuid(tag, "Actor"), StrictNbt.requireString(tag, "Team"),
            diskTier, entries, before, after);
    }

    private static DataCenterResearchSnapshot validated(UUID database, int tier, int capacity,
                                                        Object2IntMap<ResourceLocation> contents, ResearchRegistry registry) {
        var snapshot = new DataCenterResearchSnapshot(database, tier, capacity, 0, contents);
        return ResearchDataCenter.restore(snapshot, registry, () -> { }, () -> { }).snapshot();
    }

    private static UUID uuid(NBTTagCompound tag, String key) {
        var text = StrictNbt.requireString(tag, key);
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical disk receipt UUID: " + key);
        return id;
    }

    private static NBTTagList encodeLevels(Object2IntMap<ResourceLocation> levels) {
        var list = new NBTTagList();
        var ids = new ObjectArrayList<>(levels.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (var id : ids) {
            var entry = new NBTTagCompound();
            entry.setString("Id", id.toString());
            entry.setInteger("Level", levels.getInt(id));
            list.appendTag(entry);
        }
        return list;
    }

    private static Object2IntMap<ResourceLocation> decodeLevels(NBTTagCompound tag, String key) {
        var list = StrictNbt.requireList(tag, key, Constants.NBT.TAG_COMPOUND);
        var result = new Object2IntOpenHashMap<ResourceLocation>();
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Id", "Level");
            var id = StrictNbt.requireId(entry, "Id");
            int level = StrictNbt.requireInt(entry, "Level");
            if (level < 1 || result.containsKey(id)) throw new IllegalArgumentException("Invalid or duplicate disk receipt research");
            result.put(id, level);
        }
        return result;
    }
}
