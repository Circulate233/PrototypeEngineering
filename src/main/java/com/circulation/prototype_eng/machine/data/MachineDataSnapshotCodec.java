package com.circulation.prototype_eng.machine.data;

import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.machine.render.ControllerRenderSnapshotCodec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

/**
 * Full replacement schema; absence removes prior data, hosts and processes.
 */
public final class MachineDataSnapshotCodec {
    private MachineDataSnapshotCodec() {
    }

    public static byte[] encode(MachineDataSnapshot snapshot) {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setLong("Generation", snapshot.generation());
        tag.setLong("Revision", snapshot.revision());
        component(tag, snapshot.machine());
        var hosts = new NBTTagList();
        var elements2 = snapshot.hosts();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var host = elements2.get(index2);
            var entry = new NBTTagCompound();
            entry.setLong("Identity", host.identity());
            var slots = new NBTTagList();
            var elements = host.slots();
            for (int index = 0; index < elements.size(); index++) {
                var slot = elements.get(index);
                var item = new NBTTagCompound();
                item.setString("Name", slot.name());
                if (slot.process() != null) {
                    var process = new NBTTagCompound();
                    process.setLong("Identity", slot.process().identity());
                    process.setString("Recipe", slot.process().recipe().toString());
                    component(process, slot.process().data());
                    item.setTag("Process", process);
                }
                slots.appendTag(item);
            }
            entry.setTag("Slots", slots);
            hosts.appendTag(entry);
        }
        tag.setTag("Hosts", hosts);
        return ControllerRenderSnapshotCodec.encodeCompound(tag);
    }

    public static MachineDataSnapshot decode(byte[] bytes) {
        var tag = ControllerRenderSnapshotCodec.decodeCompound(bytes);
        if (tag.getInteger("Version") != 1)
            throw new IllegalArgumentException("Unknown machine data schema");
        var entries = tag.getTagList("Hosts", Constants.NBT.TAG_COMPOUND);
        var hosts = new ObjectArrayList<MachineDataSnapshot.Host>(entries.tagCount());
        var hostIds = new LongOpenHashSet(entries.tagCount());
        var processIds = new LongOpenHashSet();
        for (int h = 0; h < entries.tagCount(); h++) {
            var entry = entries.getCompoundTagAt(h);
            var items = entry.getTagList("Slots", Constants.NBT.TAG_COMPOUND);
            long hostId = entry.getLong("Identity");
            if (!hostIds.add(hostId)) throw new IllegalArgumentException("Duplicate data host identity");
            var slots = new ObjectArrayList<MachineDataSnapshot.Slot>(items.tagCount());
            var names = new ObjectOpenHashSet<String>();
            for (int s = 0; s < items.tagCount(); s++) {
                var item = items.getCompoundTagAt(s);
                MachineDataSnapshot.Process process = null;
                if (item.hasKey("Process")) {
                    var saved = item.getCompoundTag("Process");
                    long processId = saved.getLong("Identity");
                    if (!processIds.add(processId))
                        throw new IllegalArgumentException("Duplicate data process identity");
                    process = new MachineDataSnapshot.Process(processId, new ResourceLocation(saved.getString("Recipe")), component(saved));
                }
                var name = item.getString("Name");
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate data slot name");
                slots.add(new MachineDataSnapshot.Slot(name, process));
            }
            hosts.add(new MachineDataSnapshot.Host(hostId, slots));
        }
        return new MachineDataSnapshot(tag.getLong("Generation"), tag.getLong("Revision"), component(tag), hosts);
    }

    private static void component(NBTTagCompound tag, @Nullable ComponentData.Snapshot data) {
        if (data == null) return;
        tag.setLong("ComponentRevision", data.revision());
        tag.setTag("Data", data.data());
    }

    private static @Nullable ComponentData.Snapshot component(NBTTagCompound tag) {
        if (!tag.hasKey("Data") && !tag.hasKey("ComponentRevision")) return null;
        return new ComponentData.Snapshot(tag.getLong("ComponentRevision"), tag.getCompoundTag("Data"));
    }
}
