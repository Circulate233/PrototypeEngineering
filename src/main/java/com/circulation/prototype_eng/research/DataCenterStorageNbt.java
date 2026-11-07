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

import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommitNbt;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;

/** Complete strict physical storage payload. Load order, capture epoch and runtime revision are not persisted. */
public final class DataCenterStorageNbt {
    private DataCenterStorageNbt() { }

    public static NBTTagCompound encode(DataCenterResearchSnapshot snapshot) {
        return encodeState(new ResearchDataCenter.State(snapshot, Map.of()));
    }

    public static NBTTagCompound encodeState(ResearchDataCenter.State state) {
        var snapshot = state.contents();
        var tag = new NBTTagCompound();
        tag.setInteger("Schema", 2);
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
        var receipts = new NBTTagList();
        var operations = new ObjectArrayList<>(state.receipts().keySet());
        operations.sort(Comparator.comparing(UUID::toString));
        for (var operation : operations) receipts.appendTag(ResearchDiskCommitNbt.encode(state.receipts().get(operation)));
        tag.setTag("DiskReceipts", receipts);
        return tag;
    }

    public static DataCenterResearchSnapshot decode(NBTTagCompound tag, ResearchRegistry registry) {
        return decodeState(tag, registry).contents();
    }

    public static ResearchDataCenter.State decodeState(NBTTagCompound tag, ResearchRegistry registry) {
        StrictNbt.requireKeys(tag, "Schema", "IdMost", "IdLeast", "Level", "Capacity", "Research", "DiskReceipts");
        if (StrictNbt.requireInt(tag, "Schema") != 2) {
            throw new IllegalArgumentException("Unsupported data-center storage schema");
        }
        var id = new UUID(StrictNbt.requireLong(tag, "IdMost"), StrictNbt.requireLong(tag, "IdLeast"));
        int level = StrictNbt.requireInt(tag, "Level");
        int capacity = StrictNbt.requireInt(tag, "Capacity");
        var list = StrictNbt.requireList(tag, "Research", Constants.NBT.TAG_COMPOUND);
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Id", "Level");
            var researchId = StrictNbt.requireId(entry, "Id");
            if (levels.containsKey(researchId)) {
                throw new IllegalArgumentException("Duplicate saved data-center research: " + researchId);
            }
            levels.put(researchId, StrictNbt.requireInt(entry, "Level"));
        }
        var snapshot = new DataCenterResearchSnapshot(id, level, capacity, 0, levels);
        var receipts = new Object2ObjectOpenHashMap<UUID, ResearchDiskCommit>();
        var savedReceipts = StrictNbt.requireList(tag, "DiskReceipts", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedReceipts.tagCount(); index++) {
            var receipt = ResearchDiskCommitNbt.decode(savedReceipts.getCompoundTagAt(index), registry);
            if (receipts.putIfAbsent(receipt.operationId(), receipt) != null)
                throw new IllegalArgumentException("Duplicate saved database import receipt");
        }
        var state = new ResearchDataCenter.State(snapshot, receipts);
        return ResearchDataCenter.restore(state, registry, () -> { }, () -> { }).persistentState();
    }
}
