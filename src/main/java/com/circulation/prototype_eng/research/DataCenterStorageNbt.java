package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Comparator;
import java.util.UUID;

/** Complete strict physical storage payload. Load order, capture epoch and runtime revision are not persisted. */
public final class DataCenterStorageNbt {
    private DataCenterStorageNbt() { }

    public static NBTTagCompound encode(DataCenterResearchSnapshot snapshot) {
        var tag = new NBTTagCompound();
        tag.setInteger("Schema", 1);
        tag.setLong("IdMost", snapshot.dataCenterId().getMostSignificantBits());
        tag.setLong("IdLeast", snapshot.dataCenterId().getLeastSignificantBits());
        tag.setInteger("Level", snapshot.level());
        tag.setInteger("Capacity", snapshot.capacity());
        var list = new NBTTagList();
        var ids = new ObjectArrayList<>(snapshot.storedResearch().keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (var id : ids) {
            var entry = new NBTTagCompound();
            entry.setString("Id", id.toString());
            entry.setInteger("Level", snapshot.storedResearch().getInt(id));
            list.appendTag(entry);
        }
        tag.setTag("Research", list);
        return tag;
    }

    public static DataCenterResearchSnapshot decode(NBTTagCompound tag, ResearchRegistry registry) {
        StrictNbt.requireKeys(tag, "Schema", "IdMost", "IdLeast", "Level", "Capacity", "Research");
        if (StrictNbt.requireInt(tag, "Schema") != 1) { throw new IllegalArgumentException("Unsupported data-center storage schema"); }
        var id = new UUID(StrictNbt.requireLong(tag, "IdMost"), StrictNbt.requireLong(tag, "IdLeast"));
        int level = StrictNbt.requireInt(tag, "Level");
        int capacity = StrictNbt.requireInt(tag, "Capacity");
        var list = StrictNbt.requireList(tag, "Research", Constants.NBT.TAG_COMPOUND);
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Id", "Level");
            var researchId = StrictNbt.requireId(entry, "Id");
            if (levels.containsKey(researchId)) { throw new IllegalArgumentException("Duplicate saved data-center research: " + researchId); }
            levels.put(researchId, StrictNbt.requireInt(entry, "Level"));
        }
        var snapshot = new DataCenterResearchSnapshot(id, level, capacity, 0, levels);
        return ResearchDataCenter.restore(snapshot, registry, () -> { }, () -> { }).snapshot();
    }
}
