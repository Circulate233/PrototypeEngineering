package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommitNbt;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * Complete strict physical storage payload. Load order, capture epoch and runtime revision are not persisted.
 */
public final class DataCenterStorageNbt {
    private DataCenterStorageNbt() {
    }

    public static NBTTagCompound encode(DataCenterResearchSnapshot snapshot) {
        return encode(snapshot, Map.of());
    }

    public static NBTTagCompound encodeState(ResearchDataCenter.State state) {
        return encode(state.contents(), state.receipts());
    }

    private static NBTTagCompound encode(DataCenterResearchSnapshot snapshot, Map<UUID, ResearchDiskCommit> diskReceipts) {
        var tag = new NBTTagCompound();
        tag.setInteger("Schema", 2);
        tag.setLong("IdMost", snapshot.dataCenterId().getMostSignificantBits());
        tag.setLong("IdLeast", snapshot.dataCenterId().getLeastSignificantBits());
        tag.setInteger("Level", snapshot.level());
        tag.setInteger("Capacity", snapshot.capacity());
        var list = new NBTTagList();
        var ids = new ObjectArrayList<>(snapshot.storedResearch().keySet());
        ids.sort(Comparator.naturalOrder());
        for (int index2 = 0; index2 < ids.size(); index2++) {
            var id = ids.get(index2);
            var entry = new NBTTagCompound();
            entry.setString("Id", id.toString());
            entry.setInteger("Level", snapshot.storedResearch().getInt(id));
            list.appendTag(entry);
        }
        tag.setTag("Research", list);
        var receipts = new NBTTagList();
        var operations = new ObjectArrayList<>(diskReceipts.keySet());
        operations.sort(Comparator.naturalOrder());
        for (int index1 = 0; index1 < operations.size(); index1++) {
            var operation = operations.get(index1);
            receipts.appendTag(ResearchDiskCommitNbt.encode(diskReceipts.get(operation)));
        }
        tag.setTag("DiskReceipts", receipts);
        return tag;
    }

    public static DataCenterResearchSnapshot decode(NBTTagCompound tag, ResearchRegistry registry) {
        return decodeState(tag, registry).contents();
    }

    public static ResearchDataCenter.State decodeState(NBTTagCompound tag, ResearchRegistry registry) {
        if (tag.getInteger("Schema") != 2) {
            throw new IllegalArgumentException("Unsupported data-center storage schema");
        }
        var id = new UUID(tag.getLong("IdMost"), tag.getLong("IdLeast"));
        int level = tag.getInteger("Level");
        int capacity = tag.getInteger("Capacity");
        var list = tag.getTagList("Research", Constants.NBT.TAG_COMPOUND);
        var levels = new Object2IntOpenHashMap<ResourceLocation>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            var researchId = new ResourceLocation(entry.getString("Id"));
            if (levels.containsKey(researchId)) {
                throw new IllegalArgumentException("Duplicate saved data-center research: " + researchId);
            }
            levels.put(researchId, entry.getInteger("Level"));
        }
        var snapshot = new DataCenterResearchSnapshot(id, level, capacity, 0, levels);
        var savedReceipts = tag.getTagList("DiskReceipts", Constants.NBT.TAG_COMPOUND);
        var receipts = new Object2ObjectOpenHashMap<UUID, ResearchDiskCommit>(savedReceipts.tagCount());
        for (int index = 0; index < savedReceipts.tagCount(); index++) {
            var receipt = ResearchDiskCommitNbt.decode(savedReceipts.getCompoundTagAt(index));
            if (receipts.putIfAbsent(receipt.operationId(), receipt) != null)
                throw new IllegalArgumentException("Duplicate saved database import receipt");
        }
        var state = new ResearchDataCenter.State(snapshot, receipts);
        ResearchDataCenter.validateSaved(state, registry);
        return state;
    }
}
